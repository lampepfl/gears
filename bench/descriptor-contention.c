/* Do system calls on descriptors slow each other down when made from threads of one process, but not from separate
 * processes? Each of N threads, or N forked processes, works on descriptors of its own, which nothing else touches:
 *
 *   getppid   calls getppid(), which names no descriptor, for comparison
 *   fstat     calls fstat() on its own descriptor
 *   rw        writes one byte to its own socket pair and reads it back from the other end
 *   sigread   reads its signal mask with sigprocmask, as libc's setjmp does on macOS
 *   sigset    sets its signal mask to what it already is with sigprocmask(SIG_SETMASK), as libc's longjmp does
 *   jmp       setjmp then longjmp, as Scala Native's continuations do on every resume
 *   _jmp      _setjmp then _longjmp, which leave the signal mask and alternate stack alone
 *
 * The ping-pong modes run N pairs of threads (as threads, or a process per pair), each pair ping-ponging 64-byte
 * messages over its own non-blocking Unix socket pair, each side polling without blocking rather than sleeping:
 *
 *   pingpong     retries `read` until the message is there
 *   kqueue       macOS and FreeBSD: a kqueue per thread with an edge-triggered read filter, polled with kevent,
 *                then `read`, as gears' reactor does
 *   epoll        Linux: the same with an epoll instance per thread and EPOLLET
 *   uring        Linux: an io_uring per thread (single issuer, task work deferred to the thread's own
 *                io_uring_enter calls); each side submits its write and its next read together, one
 *                io_uring_enter, then polls for the read's completion, entering the kernel to run deferred
 *                completions (io_uring_get_events) between peeks
 *   uring-fixed  as uring, with the socket and the ring's own descriptor registered, so no request or io_uring_enter
 *                looks up a descriptor
 *
 *   cc -O2 -o /tmp/descriptor-contention bench/descriptor-contention.c [-luring on Linux]
 *   /tmp/descriptor-contention <mode> threads|procs N [iterations]
 *
 * On macOS, every descriptor call takes the process's descriptor table lock twice, and setting the signal mask takes
 * the process lock, so with threads the time per call grows with N, and with processes it does not; on Linux neither
 * does. See "macOS's per-process locks" in docs/io/runtime.md.
 */
#include <fcntl.h>
#if defined(__APPLE__) || defined(__FreeBSD__)
#include <sys/event.h>
#endif
#ifdef __linux__
#include <liburing.h>
#include <sys/epoll.h>
#endif
#include <setjmp.h>
#include <signal.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static const char *what;
static long iterations;

static void *calls(void *unused) {
  int sv[2];
  if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) abort();
  struct stat st;
  char c = 0;
  sigset_t mask;
  sigprocmask(0, NULL, &mask);
  for (long i = 0; i < iterations; i++) {
    if (!strcmp(what, "getppid")) getppid();
    else if (!strcmp(what, "sigread")) sigprocmask(0, NULL, &mask);
    else if (!strcmp(what, "sigset")) sigprocmask(SIG_SETMASK, &mask, NULL);
    else if (!strcmp(what, "jmp")) {
      jmp_buf env;
      if (setjmp(env) == 0) longjmp(env, 1);
    } else if (!strcmp(what, "_jmp")) {
      jmp_buf env;
      if (_setjmp(env) == 0) _longjmp(env, 1);
    }
    else if (!strcmp(what, "fstat")) fstat(sv[0], &st);
    else {
      if (write(sv[0], &c, 1) != 1) abort();
      if (read(sv[1], &c, 1) != 1) abort();
    }
  }
  return 0;
}

/* One side of a ping-pong pair: its socket, and whatever it polls with. */
struct side {
  int fd;
#if defined(__APPLE__) || defined(__FreeBSD__)
  int kq;
#endif
#ifdef __linux__
  int ep;
  struct io_uring ring;
  int fixed;
#endif
};

static void side_open(struct side *s, int fd) {
  s->fd = fd;
#if defined(__APPLE__) || defined(__FreeBSD__)
  if (!strcmp(what, "kqueue")) {
    s->kq = kqueue();
    struct kevent change;
    EV_SET(&change, fd, EVFILT_READ, EV_ADD | EV_CLEAR, 0, 0, 0);
    if (kevent(s->kq, &change, 1, 0, 0, 0) != 0) abort();
  }
#endif
#ifdef __linux__
  if (!strcmp(what, "epoll")) {
    s->ep = epoll_create1(0);
    struct epoll_event ev = {.events = EPOLLIN | EPOLLET, .data.fd = fd};
    if (epoll_ctl(s->ep, EPOLL_CTL_ADD, fd, &ev) != 0) abort();
  } else if (!strncmp(what, "uring", 5)) {
    if (io_uring_queue_init(8, &s->ring, IORING_SETUP_SINGLE_ISSUER | IORING_SETUP_DEFER_TASKRUN) != 0) abort();
    s->fixed = !strcmp(what, "uring-fixed");
    if (s->fixed) {
      if (io_uring_register_files(&s->ring, &fd, 1) != 0) abort();
      if (io_uring_register_ring_fd(&s->ring) != 1) abort();
    }
  }
#endif
}

#ifdef __linux__
enum { READ_DONE = 1, WRITE_DONE = 2 };

/* Queues a read or write of 64 bytes, tagged so that its completion can be told apart. */
static void uring_prep(struct side *s, char *b, int is_write) {
  struct io_uring_sqe *sqe = io_uring_get_sqe(&s->ring);
  int fd = s->fixed ? 0 : s->fd;
  if (is_write) io_uring_prep_write(sqe, fd, b, 64, 0);
  else io_uring_prep_read(sqe, fd, b, 64, 0);
  if (s->fixed) sqe->flags |= IOSQE_FIXED_FILE;
  io_uring_sqe_set_data64(sqe, is_write ? WRITE_DONE : READ_DONE);
}

/* Reaps completions, polling, until the outstanding read has finished; writes are checked as they finish. */
static void uring_await_read(struct side *s) {
  for (;;) {
    struct io_uring_cqe *cqe;
    if (io_uring_peek_cqe(&s->ring, &cqe) != 0) {
      io_uring_get_events(&s->ring);
      continue;
    }
    int tag = (int)io_uring_cqe_get_data64(cqe), res = cqe->res;
    io_uring_cqe_seen(&s->ring, cqe);
    if (res != 64) abort();
    if (tag == READ_DONE) return;
  }
}
#endif

/* Reads one 64-byte message, polling as the mode says until it is there. */
static void side_read(struct side *s, char *b) {
  while (read(s->fd, b, 64) != 64) {
#if defined(__APPLE__) || defined(__FreeBSD__)
    if (!strcmp(what, "kqueue")) {
      struct kevent ev;
      struct timespec zero = {0, 0};
      while (kevent(s->kq, 0, 0, &ev, 1, &zero) == 0) {}
    }
#endif
#ifdef __linux__
    if (!strcmp(what, "epoll")) {
      struct epoll_event ev;
      while (epoll_wait(s->ep, &ev, 1, 0) == 0) {}
    }
#endif
  }
}

static void side_write(struct side *s, char *b) {
  if (write(s->fd, b, 64) != 64) abort();
}

static void *echo(void *p) {
  struct side s;
  side_open(&s, *(int *)p);
  char b[64];
#ifdef __linux__
  if (!strncmp(what, "uring", 5)) {
    // A read is always outstanding; each echo goes in with the next read, into the other buffer.
    char bufs[2][64];
    uring_prep(&s, bufs[0], 0);
    io_uring_submit(&s.ring);
    for (long i = 0; i < iterations; i++) {
      uring_await_read(&s);
      uring_prep(&s, bufs[i & 1], 1);
      if (i + 1 < iterations) uring_prep(&s, bufs[(i + 1) & 1], 0);
      io_uring_submit(&s.ring);
    }
    return 0;
  }
#endif
  for (long i = 0; i < iterations; i++) {
    side_read(&s, b);
    side_write(&s, b);
  }
  return 0;
}

static void *client(void *p) {
  struct side s;
  side_open(&s, *(int *)p);
  char b[64] = {0};
#ifdef __linux__
  if (!strncmp(what, "uring", 5)) {
    char r[64];
    for (long i = 0; i < iterations; i++) {
      uring_prep(&s, b, 1);
      uring_prep(&s, r, 0);
      io_uring_submit(&s.ring);
      uring_await_read(&s);
    }
    return 0;
  }
#endif
  for (long i = 0; i < iterations; i++) {
    side_write(&s, b);
    side_read(&s, b);
  }
  return 0;
}

static int is_pingpong(void) {
  return !strcmp(what, "pingpong") || !strcmp(what, "kqueue") || !strcmp(what, "epoll") || !strncmp(what, "uring", 5);
}

/* One unit of work: a loop of calls, or a ping-pong pair over a socket pair of its own. */
static void *unit(void *unused) {
  if (!is_pingpong()) return calls(0);
  int sv[2];
  if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) abort();
  fcntl(sv[0], F_SETFL, O_NONBLOCK);
  fcntl(sv[1], F_SETFL, O_NONBLOCK);
  pthread_t a, b;
  pthread_create(&a, 0, echo, &sv[1]);
  pthread_create(&b, 0, client, &sv[0]);
  pthread_join(a, 0);
  pthread_join(b, 0);
  return 0;
}

int main(int argc, char **argv) {
  if (argc < 4) {
    fprintf(stderr, "usage: %s <mode> threads|procs N [iterations]\n", argv[0]);
    return 2;
  }
  what = argv[1];
#if !defined(__APPLE__) && !defined(__FreeBSD__)
  if (!strcmp(what, "kqueue")) return fprintf(stderr, "kqueue is macOS and FreeBSD only\n"), 2;
#endif
#ifndef __linux__
  if (!strcmp(what, "epoll") || !strncmp(what, "uring", 5)) return fprintf(stderr, "%s is Linux only\n", what), 2;
#endif
  int n = atoi(argv[3]);
  iterations = argc > 4 ? atol(argv[4]) : 500000;
  if (n < 1 || n > 64) return 2;
  struct timespec t0, t1;
  clock_gettime(CLOCK_MONOTONIC, &t0);
  if (!strcmp(argv[2], "threads")) {
    pthread_t ts[64];
    for (int i = 0; i < n; i++) pthread_create(&ts[i], 0, unit, 0);
    for (int i = 0; i < n; i++) pthread_join(ts[i], 0);
  } else {
    for (int i = 0; i < n; i++)
      if (fork() == 0) {
        unit(0);
        _exit(0);
      }
    for (int i = 0; i < n; i++) wait(0);
  }
  clock_gettime(CLOCK_MONOTONIC, &t1);
  double seconds = (t1.tv_sec - t0.tv_sec) + (t1.tv_nsec - t0.tv_nsec) / 1e9;
  if (is_pingpong())
    printf("%-11s %-7s x%d: %.2f us per round trip per pair, %.0fk round trips/s in all\n", what, argv[2], n,
           seconds * 1e6 / iterations, n * iterations / seconds / 1e3);
  else
    printf("%-8s %-7s x%d: %.3f us per iteration per thread\n", what, argv[2], n, seconds * 1e6 / iterations);
  return 0;
}
