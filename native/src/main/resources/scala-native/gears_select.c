// `select` for gears' FIFO watcher (gears.async.io.internal.FifoWatcher): on macOS the only call that reports a FIFO
// whose writer closed. Unlimited, so that descriptors past FD_SETSIZE can be watched: on macOS this macro selects
// select$DARWIN_EXTSN, which takes a set of any size; elsewhere select takes one as large as nfds needs.
#define _DARWIN_UNLIMITED_SELECT 1
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/select.h>

// Waits until one of the `count` descriptors in `fds` is readable as select reports it, and writes 1 into `ready[i]`
// for each that is, 0 for the others. Returns how many are, or -1 with errno set.
int gears_select_readable(const int *fds, int count, int *ready) {
  int max = -1;
  for (int i = 0; i < count; i++)
    if (fds[i] > max) max = fds[i];
  // A set of 32-bit words, bit fd % 32 of word fd / 32: macOS's layout, and on a little-endian machine glibc's too.
  size_t words = (size_t)max / 32 + 1;
  uint32_t *set = calloc(words, sizeof(uint32_t));
  if (set == NULL) return -1;
  for (int i = 0; i < count; i++) set[fds[i] / 32] |= 1u << (fds[i] % 32);
  int n = select(max + 1, (fd_set *)set, NULL, NULL, NULL);
  if (n >= 0)
    for (int i = 0; i < count; i++) ready[i] = (set[fds[i] / 32] >> (fds[i] % 32)) & 1;
  int saved = errno;
  free(set);
  errno = saved;
  return n;
}
