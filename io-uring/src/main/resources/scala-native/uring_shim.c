#define _GNU_SOURCE

#if defined(__linux__) && __has_include(<liburing.h>)
#include <liburing.h>
#include <string.h>

int gears_io_uring_spin_check(struct io_uring *ring) {
  if (io_uring_cq_ready(ring)) return 1;
  if (IO_URING_READ_ONCE(*ring->sq.kflags) & IORING_SQ_TASKRUN) return 2;
#if defined(__x86_64__) || defined(__i386__)
  __builtin_ia32_pause();
#elif defined(__aarch64__)
  __asm__ __volatile__("yield");
#endif
  return 0;
}

// - no SQPOLL: the kernel's head moves only in this thread's io_uring_enter
// - a slot the kernel took is reused: it is this entry only while it still has the entry's user data
int gears_io_uring_sqe_pending(struct io_uring *ring, struct io_uring_sqe *sqe, unsigned long long user_data) {
  unsigned head = io_uring_smp_load_acquire(ring->sq.khead);
  unsigned index = (unsigned)(sqe - ring->sq.sqes);
  return ((index - head) & ring->sq.ring_mask) < ring->sq.sqe_tail - head && sqe->user_data == user_data;
}

struct io_uring_sqe *gears_io_uring_get_sqe(struct io_uring *ring) {
  return io_uring_get_sqe(ring);
}

unsigned gears_io_uring_sq_ready(struct io_uring *ring) {
  return io_uring_sq_ready(ring);
}

void gears_io_uring_cq_advance(struct io_uring *ring, unsigned nr) {
  io_uring_cq_advance(ring, nr);
}

int gears_io_uring_init(unsigned entries, struct io_uring *ring, unsigned completions) {
  struct io_uring_params params;
  memset(&params, 0, sizeof(params));
  // - COOP_TASKRUN: completion work runs when the worker enters, flagged by TASKRUN_FLAG
  params.flags = IORING_SETUP_CQSIZE | IORING_SETUP_COOP_TASKRUN | IORING_SETUP_TASKRUN_FLAG;
  params.cq_entries = completions;
  return io_uring_queue_init_params(entries, ring, &params);
}

size_t gears_io_uring_size(void) {
  return sizeof(struct io_uring);
}

unsigned gears_io_uring_features(struct io_uring *ring) {
  return ring->features;
}

int gears_io_uring_cq_overflowing(struct io_uring *ring) {
  return io_uring_cq_has_overflow(ring);
}

int gears_io_uring_get_events(struct io_uring *ring) {
  return io_uring_get_events(ring);
}

int gears_io_uring_submit_and_wait(struct io_uring *ring, long long nanoseconds) {
  struct io_uring_cqe *cqe;
  struct __kernel_timespec ts;
  if (nanoseconds < 0) return io_uring_submit_and_wait_timeout(ring, &cqe, 1, NULL, NULL);
  ts.tv_sec = nanoseconds / 1000000000LL;
  ts.tv_nsec = nanoseconds % 1000000000LL;
  return io_uring_submit_and_wait_timeout(ring, &cqe, 1, &ts, NULL);
}

static int cancel(struct io_uring *ring, struct io_uring_sync_cancel_reg *reg, int wait) {
  // - -1/-1 waits until the kernel has let go; 0/0 does not wait for a kernel worker (-ETIME)
  reg->timeout.tv_sec = wait ? -1 : 0;
  reg->timeout.tv_nsec = wait ? -1 : 0;
  return io_uring_register_sync_cancel(ring, reg);
}

int gears_io_uring_cancel_now(struct io_uring *ring, unsigned long long user_data, int wait) {
  struct io_uring_sync_cancel_reg reg;
  memset(&reg, 0, sizeof(reg));
  reg.addr = user_data;
  return cancel(ring, &reg, wait);
}

int gears_io_uring_cancel_fd(struct io_uring *ring, int fd) {
  struct io_uring_sync_cancel_reg reg;
  memset(&reg, 0, sizeof(reg));
  reg.fd = fd;
  reg.flags = IORING_ASYNC_CANCEL_FD | IORING_ASYNC_CANCEL_ALL;
  return cancel(ring, &reg, 0);
}
#endif
