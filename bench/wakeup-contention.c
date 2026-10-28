/* Do wake-ups between threads slow each other down when made in one process, as descriptor calls do on macOS? N pairs
 * of threads, as threads of this process or a process per pair, ping-pong a wake-up: each thread wakes its partner,
 * then waits to be woken. Ways to wake and wait:
 *
 *   user       each thread blocks in kevent on a kqueue of its own; its partner wakes it by triggering an EVFILT_USER
 *              filter there, with kevent on that kqueue, as ReactorPool does today
 *   user-poll  the same, polling kevent without blocking
 *   port       each thread blocks in kevent on its kqueue, which watches a Mach port of its own (EVFILT_MACHPORT);
 *              its partner wakes it by sending a message to the port, which it then receives with mach_msg
 *   port-poll  the same, polling kevent without blocking
 *   mach       each thread blocks in mach_msg, receiving on its port; no kqueue
 *   mach-poll  the same, polling mach_msg with a zero timeout
 *   ulock      each thread waits on a word of its own with os_sync_wait_on_address; its partner changes the word and
 *              wakes it with os_sync_wake_by_address_any; no kqueue, no port
 *
 *   cc -O2 -o /tmp/wakeup-contention bench/wakeup-contention.c
 *   /tmp/wakeup-contention <mode> threads|procs N [round trips]
 *
 * kevent takes the process's descriptor table lock to find its kqueue; mach_msg finds its port without a lock that
 * every thread of the task shares; os_sync_wait_on_address uses no descriptor or port at all. See "macOS's
 * per-process locks" in docs/io/runtime.md.
 */
#include <mach/mach.h>
#include <os/os_sync_wait_on_address.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/event.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static const char *mode;
static long rounds;

/* One thread of a pair: what it waits on, and how its partner reaches it. */
struct waiter {
  int kq;
  mach_port_t port;
  _Atomic uint32_t word;
  uint32_t seen;
};

static int polling(void) { return strstr(mode, "-poll") != NULL; }

static void waiter_open(struct waiter *w) {
  w->kq = kqueue();
  w->word = 0;
  w->seen = 0;
  if (mach_port_allocate(mach_task_self(), MACH_PORT_RIGHT_RECEIVE, &w->port) != KERN_SUCCESS) abort();
  if (mach_port_insert_right(mach_task_self(), w->port, w->port, MACH_MSG_TYPE_MAKE_SEND) != KERN_SUCCESS) abort();
  struct kevent change;
  if (!strncmp(mode, "user", 4)) EV_SET(&change, 1, EVFILT_USER, EV_ADD | EV_CLEAR, 0, 0, 0);
  else if (!strncmp(mode, "port", 4)) EV_SET(&change, w->port, EVFILT_MACHPORT, EV_ADD, 0, 0, 0);
  else return;
  if (kevent(w->kq, &change, 1, 0, 0, 0) != 0) abort();
}

static void wake(struct waiter *target) {
  if (!strncmp(mode, "user", 4)) {
    struct kevent trigger;
    EV_SET(&trigger, 1, EVFILT_USER, 0, NOTE_TRIGGER, 0, 0);
    if (kevent(target->kq, &trigger, 1, 0, 0, 0) != 0) abort();
  } else if (!strcmp(mode, "ulock")) {
    atomic_fetch_add_explicit(&target->word, 1, memory_order_release);
    os_sync_wake_by_address_any(&target->word, sizeof(uint32_t), OS_SYNC_WAKE_BY_ADDRESS_NONE);
  } else {
    mach_msg_header_t msg = {0};
    msg.msgh_bits = MACH_MSGH_BITS(MACH_MSG_TYPE_COPY_SEND, 0);
    msg.msgh_size = sizeof(msg);
    msg.msgh_remote_port = target->port;
    msg.msgh_id = 1;
    if (mach_msg(&msg, MACH_SEND_MSG, sizeof(msg), 0, MACH_PORT_NULL, MACH_MSG_TIMEOUT_NONE, MACH_PORT_NULL) !=
        MACH_MSG_SUCCESS)
      abort();
  }
}

/* Receives one message on `w`'s port: blocking, or only if one is there; returns whether it got one. */
static int receive(struct waiter *w, int block) {
  struct {
    mach_msg_header_t header;
    mach_msg_trailer_t trailer;
  } buf;
  mach_msg_return_t r = mach_msg(&buf.header, MACH_RCV_MSG | (block ? 0 : MACH_RCV_TIMEOUT), 0, sizeof(buf), w->port,
                                 block ? MACH_MSG_TIMEOUT_NONE : 0, MACH_PORT_NULL);
  if (r == MACH_RCV_TIMED_OUT) return 0;
  if (r != MACH_MSG_SUCCESS) abort();
  return 1;
}

static void await_wake(struct waiter *w) {
  if (!strncmp(mode, "user", 4) || !strncmp(mode, "port", 4)) {
    struct kevent ev;
    struct timespec zero = {0, 0};
    while (kevent(w->kq, 0, 0, &ev, 1, polling() ? &zero : 0) == 0) {}
    if (!strncmp(mode, "port", 4) && !receive(w, 0)) abort();
  } else if (!strcmp(mode, "ulock")) {
    while (atomic_load_explicit(&w->word, memory_order_acquire) == w->seen)
      os_sync_wait_on_address(&w->word, w->seen, sizeof(uint32_t), OS_SYNC_WAIT_ON_ADDRESS_NONE);
    w->seen++;
  } else if (polling()) {
    while (!receive(w, 0)) {}
  } else {
    receive(w, 1);
  }
}

struct pair {
  struct waiter a, b;
};

static void *first(void *p) {
  struct pair *pair = p;
  for (long i = 0; i < rounds; i++) {
    wake(&pair->b);
    await_wake(&pair->a);
  }
  return 0;
}

static void *second(void *p) {
  struct pair *pair = p;
  for (long i = 0; i < rounds; i++) {
    await_wake(&pair->b);
    wake(&pair->a);
  }
  return 0;
}

static void *run_pair(void *unused) {
  struct pair *pair = calloc(1, sizeof(struct pair));
  waiter_open(&pair->a);
  waiter_open(&pair->b);
  pthread_t a, b;
  pthread_create(&b, 0, second, pair);
  pthread_create(&a, 0, first, pair);
  pthread_join(a, 0);
  pthread_join(b, 0);
  return 0;
}

int main(int argc, char **argv) {
  if (argc < 4) {
    fprintf(stderr, "usage: %s user|user-poll|port|port-poll|mach|mach-poll|ulock threads|procs N [round trips]\n",
            argv[0]);
    return 2;
  }
  mode = argv[1];
  int n = atoi(argv[3]);
  rounds = argc > 4 ? atol(argv[4]) : 200000;
  if (n < 1 || n > 32) return 2;
  struct timespec t0, t1;
  clock_gettime(CLOCK_MONOTONIC, &t0);
  if (!strcmp(argv[2], "threads")) {
    pthread_t ts[32];
    for (int i = 0; i < n; i++) pthread_create(&ts[i], 0, run_pair, 0);
    for (int i = 0; i < n; i++) pthread_join(ts[i], 0);
  } else {
    for (int i = 0; i < n; i++)
      if (fork() == 0) {
        run_pair(0);
        _exit(0);
      }
    for (int i = 0; i < n; i++) wait(0);
  }
  clock_gettime(CLOCK_MONOTONIC, &t1);
  double seconds = (t1.tv_sec - t0.tv_sec) + (t1.tv_nsec - t0.tv_nsec) / 1e9;
  printf("%-9s %-7s x%d: %.2f us per round trip per pair, %.0fk round trips/s in all\n", mode, argv[2], n,
         seconds * 1e6 / rounds, n * rounds / seconds / 1e3);
  return 0;
}
