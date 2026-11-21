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

## Receiving on a connection

A connection's first read of at most 16 KiB that has to wait arms a multishot receive (`Receiver`) on its worker's ring,
which fills the worker's provided buffers (`BufferRing`, 512 of 4 KiB, a buffer ring) as bytes arrive, whether or not a
read is waiting. A read then copies from them rather than going to the kernel: a socket's read no longer arms a poll of
its own every time, which is most of what a ping-pong costs over a one-shot read. The receive stays armed until the
stream ends or fails, the connection closes, or no buffer is free; then the next read that waits arms it again.

What a receive got is its worker's alone, so every read of the connection is performed on that worker: one performed
elsewhere is handed to it as a ring task, as a command whose turn has come is, and taken back there if its performer is
cancelled. Every read takes what was received first, a vectored or pooled one too, and goes to the kernel itself only
while the receive is idle, nothing taken and nothing more to come; a larger read, or one of these, stops an armed
receive, takes what came before the stop, and then reads from the kernel, which saves a copy of every byte of a bulk
transfer. A cancelled or timed-out read loses nothing: what arrives after is the next read's.

An armed receive is work claimed on its handle, so a close cancels it and the descriptor closes once it has ended; the
buffers it held are given back. A connection holding 16 buffers with no read waiting stops receiving, and the kernel's
flow control then holds its sender back; the kernel receives what is already there in one burst before the stop lands,
which may take every free buffer for a while, and a worker with none free reads from the kernel itself. A worker spins
on its completion queue while a read waits on a receive, as it does while a command is in the kernel.

Multishot receives are turned off with `gears.io.uring.multishot=false`, or `GEARS_IO_URING_MULTISHOT=false`.

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
a cancel, a time limit or a close; and that every handle is close-on-exec. `ReceiveSuite` checks receives: reads in
order however they cut what came, bytes that came with no read waiting, a large, vectored or pooled read after a
receive, a cancel or time limit losing nothing, a close giving every buffer back, the end of the stream, a stopped
receive holding its sender back, a worker with no buffer free, reads performed from other workers, and a failing ring.
The whole suite also runs with receives turned off.
