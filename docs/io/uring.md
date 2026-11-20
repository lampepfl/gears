# The io_uring backend

`UringBackend` (`io-uring/`, package `gears.async.io.uring`) is a backend for Scala Native on Linux 6.0 or later,
through liburing. It runs on `io-poller`'s pool (`WorkpoolScheduler`, `PoolOpWorker`): each worker's poller is a ring
of its own (`Ring`), which its commands' entries go to and whose completions it reaps on its own thread. Everything
else is the pool's, as for the other pool backends: futures, the timer heap and time limits, the op protocol, handles
and their owner, and blocking code, name lookups and opening files, which are done off the workers. The sockets,
pipes and FIFOs are opened with `io-poller`'s POSIX layer.

## A command

A read, a write, an accept, a connect, a datagram's send or receive, a pooled read and a file's read, write or sync
are commands (`Command`). A command is tried at once with a non-blocking system call (`tryAtOnce`); if that would
block, it becomes one submission queue entry on its performer's worker's ring (`prepare`), and the completion that
answers it goes to `finish`, which says whether it is done or is to be submitted again. The next wait of the worker
submits what it prepared and waits for completions in one `io_uring_enter`; a worker with entries in the kernel first
watches its completion queue for up to 20 µs (`Ring.SpinNanos`), which saves a round trip its sleep and wake-up.

The kernel owns an entry's buffers from its submission until its completion has been reaped, or until a synchronous
cancel (`IORING_REGISTER_SYNC_CANCEL`, Linux 6.0) has returned: a command claims its handle's work for as long, so that
the descriptor is never closed, and its number reused, under an entry that names it.

## Taking a command back

While its entry is in the kernel, a command is not claimed (see `TimerHeapOp`): a cancel of its performer, or its time
limit, which its worker's heap keeps, settles it at once. The performer then takes the entry back, on the command's
own worker, since only that worker touches its ring, and waits, whatever its group says, until the kernel has let go:

- an entry still prepared, or one a submission left in the queue (a slot that still has its user data), becomes a
  no-op;
- one in the kernel is cancelled synchronously: `0` means the kernel let go without doing it; `-ENOENT` that it
  finished first, its completion in the ring.

A completion that comes first claims the command (`claimAtWork`), and the performer gets its outcome as usual. One that
comes after the command was settled is applied all the same, and the performer returns it rather than its cancel or
time limit: the work was done, and a read's bytes are in its buffer.

The take-back goes to the worker as a ring task of its own, never through its inbox, whose tasks a worker with nothing
to do may take, or the pool's starvation monitor move to another worker.

## Closing

A close marks the handle closing (`requestClose`), so that no use begins, then cancels the entries on its descriptor on
every ring that has some (`IORING_ASYNC_CANCEL_FD`), wakes those rings, and fails the commands waiting for their turn
or for a pool's buffer. An entry still prepared as the close begins becomes a no-op as its worker submits; one
submitted as the close begins is cancelled after the submission. The descriptor is closed once the last command has
let go of the handle's work, which completes the close.

## Pooled reads

A pooled read of a socket waits for readiness holding no buffer, and takes one as it reads while the pool has more
than half free: idle connections hold at most half the pool between them. A read that finds no buffer free waits for
one (`NativePool.park`), and every release starts the waiting reads again on their workers.

## A ring that fails

A ring that fails for good cancels each entry still in the kernel, waiting, before it is torn down, since tearing it
down does not wait for a kernel worker still running an entry; its commands then fail, and the worker gives its poller
up, which is fatal to the program, as for every pool backend.

## Tests

The testkit's contracts run on the default pool, on one worker and, for IP streams, on two (`UringSuites`), with
`io-poller`'s run-end contract. `UringSuite` checks what only io_uring has: a read cancelled as its data arrives either
completes with it or takes nothing; a cancel of a read whose queue slot a newer entry has takes back the right one; a
close between a read's preparation and its submission, and closes reaching entries on other workers' rings; a failing
ring, a kernel worker's read in it; pooled reads holding at most half the pool, waiting while it is empty, and ended by
a cancel, a time limit or a close; and that every handle is close-on-exec.
