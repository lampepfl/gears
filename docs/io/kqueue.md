# The kqueue backend: a design note

`KqueueBackend` (`io-backends/kqueue`, package `gears.async.io.kqueue`) is the reactor backend for Scala Native on
macOS, and in principle FreeBSD, which shares kqueue. Each worker of the pool owns one `KqueueReactor`, a kqueue it
polls on its own thread (see [`runtime.md`](runtime.md) for the pool). This note records what an op costs, the
reductions that brought it there and why each works, what profiling found, how the backend compares with cats-effect and
fs2, what a burst of connections taught, how errors in kqueue itself are handled, and what is open. The changes it
proposes to Scala Native, and the workarounds for them, are in [`scala-native.md`](scala-native.md); how the numbers
were taken is in [`bench.md`](bench.md). Unless a table says otherwise, numbers are microseconds per operation, the
median of 5 runs of release-fast builds against the Scala Native nightly, on an M1 Pro running macOS 27, measured with
`bench/compare.sc`.

A gears `perform` of a read that found data already waiting used to cost about 7 system calls: a `kevent` to register,
a wait, the wake pipe, and Scala Native's signal-state calls. It now costs one, the `read` itself, and an op that has
to wait costs no `kevent` of its own either.

## What each operation costs

On the reactor's thread, by design:

| Operation | System calls | Where |
| --- | --- | --- |
| Read, write, accept, receive or send that can proceed | 1 `read`/`write`/`accept`/..., tried before waiting | `KqueueReactor.submit` |
| A read that has to wait | none, after the first on a descriptor, which adds its edge-triggered read filter (1 `kevent`); then the read when ready | `waitToRead` |
| Any other command that has to wait (writes, connects, readiness) | 1 `kevent` to arm a one-shot filter, then the command when ready | `arm` |
| A connect that waits | 1 `kevent`, adding the one-shot write filter and the persistent read filter together | `armWithReads` |
| Each turn of the loop | none after a turn that delivered completions (it polls at least every 16 turns); otherwise up to 4 polls that return at once, then 1 waiting `kevent` | `runOnce`, `pollBeforeBlocking` |
| Set, fire or cancel a timer | none | `TimerHeap` |
| Cancel a waiting read | none; other commands: 1 `kevent` (`EV_DELETE`) | `cancel` |
| Close a descriptor | 1 `close`; the kernel drops its filters | `KqueueHandles` |
| Resume a task, or resolve a promise, on the reactor's thread | none | the worker's queues, `resolved` |
| Wake the loop from another thread | 1 `kevent(NOTE_TRIGGER)`, merged with any already pending | `wakeup` |
| Accept a connection | 1 `accept`: the accepted socket is already non-blocking | `NonBlocking.accept` |
| Open a socket or pipe | 1 `ioctl(FIONBIO)` to make it non-blocking, besides opening it | `PosixSockets.setNonBlocking` |

Counted per operation on the client's thread with `bench/syscalls.sc` (lldb breakpoints; see [`bench.md`](bench.md)),
affine pool, current build ([`bench/results/syscalls-gears-affine.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/syscalls-gears-affine.json)):

| scenario | per | system calls |
| --- | --- | --- |
| `pingpong` | round trip | 1 `read`, 1 `write`, 2.8 `kevent` polls (the spins before blocking) and 0.1 waits |
| `concurrent` | message | 1 `read`, 1 `write`, 0.26 `kevent` |
| `pipelined` | message | 1 `write` |
| `connections` | connection | `connect`, `ioctl`, `close`, 1 `kevent` to register, 4.1 polls and 0.3 waits, 1 `read`, 1 `write` |

No `read` is wasted and no filter registered twice: steady traffic is down to the data calls and polls. Under the
debugger replies are slow, so the split between polls and waits leans to waits and is only indicative. What Scala
Native added on top, a `sigprocmask` and `sigaltstack` pair per continuation resume and per `@blocking` call, is gone in
programs that link this backend (see [`scala-native.md`](scala-native.md)).

## The reductions, and why each works

The reductions together, measured on the single-threaded scheduler before the pool existed, with the hand-written loop
over `KqueueLoop` (the `@raw` reference; see [`bench.md`](bench.md)) at each end ([`bench/results/overall.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/overall.json)):

| scenario | start | after | raw loop at start | raw loop after |
| --- | --- | --- | --- | --- |
| pingpong | 28.6 | 8.1 (−72%) | 6.6 | 5.4 |
| pingpong-deadline | 32.0 | 8.2 (−74%) | 8.3 | 6.3 |
| concurrent | 8.1 | 4.0 (−50%) | 2.2 | 1.5 |
| pipelined | 9.5 | 0.67 (−93%) | 0.87 | 0.49 |
| blocking | 16.3 | 6.4 (−61%) | 4.8 | 4.0 |
| timers | 1.33 | 0.22 (−83%) | 1.15 | 0.66 |
| fairness, per message | 13.4 | 3.6 (−73%) | | |
| mixed, per round trip | 46.2 | 27.8 (−40%) | | |

Gears over the reactor went from 3.4–11 times the hand-written loop's time to 1.3–2.6 times. `timers` beats the raw
loop, since user-space timers make no system calls where the raw loop uses kernel timers.

### Work queued on the reactor's thread does not wake it

A resume or a resolved promise on the reactor's thread is queued and run between turns; only work queued from another
thread calls `wakeup`. On the gears key-value client (200,000 pipelined commands) this removed all 406 wake-pipe writes
and their draining reads. The wake pipe itself is gone: waking is one `kevent` triggering an `EVFILT_USER` event, with
nothing to drain and no descriptors to hold. Wake-ups are merged with an atomic flag, which the loop clears when it sees
the event, so only the first `wakeup` since makes the call. `wakeLock` keeps a trigger from reaching the kqueue's number
after `close` has released it. Both changes together ([`bench/results/remove-wakeup-pipe.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/remove-wakeup-pipe.json)): `pingpong` 28.5 → 18.6,
`pipelined` 9.0 → 7.3, `blocking` 16.1 → 7.3.

### Ops performed at once

A read, write, accept, receive or send is performed as it is submitted, and registered only if it would block
(`Command.tryFirst`). A connect and a readiness op still wait: a connect's error check passes while the connection is in
progress, and readiness promises its body runs once the descriptor is ready. A command that finished at once has its
completion queued for the next turn, never run inside `submit`, and a turn delivers only what was queued when it began,
so one busy descriptor cannot starve the rest.

Trying first alone made things worse (`pingpong` +67%, `pipelined` +108%): each finished command cost a turn that still
polled kqueue. So a turn that delivered completions skips the poll, polling at least every `PollEvery` (16) turns so
that other descriptors are still seen. Together: `pingpong` 18.5 → 12.1, `pipelined` 7.4 → 3.1, `mixed` 36.7 → 32.3
([`bench/results/try-first.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/try-first.json)). Skipping polls cost the `fairness` ticker its kernel timers, which only a poll saw;
user-space timers, checked every turn, restored it.

`perform` goes further on the runtime's side: an op that finishes at once returns without suspending, within budgets
that keep it fair (see [`runtime.md`](runtime.md)).

### Edge-triggered, persistent read filters

A read that has to wait registers the descriptor's read filter once, `EV_ADD | EV_CLEAR`; later waits cost no system
call, and cancelling a read only clears the slot. Each slot tracks read readiness (unknown, ready, not ready), so a read
is tried first only when it is likely to succeed:

- An edge with no read pending marks the descriptor ready. A read that would block marks it not ready, and the next
  read waits for an edge.
- A read event reports how much is waiting, bytes or, on a listening socket, pending connections. The slot keeps that
  count (`readAvailable`) less what each read or accept takes since (`Command.transferred`), and marks the descriptor
  not ready once it runs out (`settleAfterRead`), so the next read waits for an edge rather than trying a call that
  would block. This took the server's second, empty `accept` per connection away (`connections` 47.5 → 45.8,
  [`bench/results/readable-counts.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/readable-counts.json)).
- End of stream (`EV_EOF`) is reported once by an edge-triggered filter but lasts, so a descriptor stays ready after it.
- Only commands whose `false` means "would block" use it (`Command.edgeTriggered`: the reads, accepts and receives).
  Other commands may return false meaning "not finished" with data still waiting, and an edge-triggered filter would
  never fire again for them, so they keep one-shot, level-triggered filters.
- A connect that waits adds the socket's persistent read filter in the same `kevent` as its one-shot write filter
  (`Command.registersReads`). kqueue reports data already waiting when a filter is added, so the socket starts out not
  ready and its first read waits for an edge instead of trying: one registration and one `read` fewer per connection on
  the client, with no measurable time (`connections` 43.5 → 43.1, [`bench/results/connect-registers-reads.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/connect-registers-reads.json)).
- Regular files and FIFOs are marked in `DescriptorTable` to be tried always (`markAlwaysTry`): a regular file's
  `EVFILT_READ` fires only while the offset is short of the end, and on macOS a FIFO whose writer closed never reports
  the end, whether the filter was added before the close or after, so a short read there must leave readiness unknown
  rather than wait for an edge that never comes. The contract suite found it. A read already waiting when the writer
  closes would still wait for ever: probed on macOS 27.0.1, once a FIFO's writer has closed `EVFILT_READ` never fires,
  whether added before the close or after, `poll` reports nothing either, and `FIONREAD` and `st_size` are 0 as they
  are with the writer open and nothing written; only `select` reports it readable, waking when the writer closes. So a
  read that waits on an always-tried descriptor is also watched by `FifoWatcher`, a `select` thread started when the
  first such read waits, which tells the reactor once the descriptor is readable, and the read is tried again, as
  libuv does with its `select` thread (`uv__stream_try_select`). It covers `ready` too, which on a regular file at its
  end would otherwise wait for ever, since the filter fires only short of the end, where `select` says a file is always
  readable. A descriptor is forgotten before it is closed, so a reused number is not watched; a watch is one-shot. Sockets and pipes keep the guess, which saves a would-block read
  in every round trip. See [`files.md`](files.md) for the file ops.

Edge-triggered reads alone made `concurrent` 30% faster and `pingpong` 11% slower ([`bench/results/edge-triggered-reads.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/edge-triggered-reads.json)):
the thread now blocked right after sending, without the system calls that used to give the reply time to arrive. Polling
a few times before blocking (see [`runtime.md`](runtime.md)) recovered it.

**Write filters stay one-shot.** Writes are tried first and nearly always succeed, so a write filter is armed only when
one would block, and is gone once it fires; nothing is left to clean up when a descriptor moves to another reactor.
`bench/write-events.c` shows what a persistent edge-triggered write filter would cost: on a connection in ping-pong, with
no write ever waiting, it fires once per turn, when the peer reads and frees send-buffer space (1.00 per turn over a Unix
socket pair, 0.99 over TCP on loopback, once per drain rather than per message). That is a wake-up or a poll for nothing
in every round trip, against a registration saved only when a write blocks.

### Timers in user space

A timer gets a deadline from `System.nanoTime` and goes into a binary min-heap on the reactor's thread (`TimerHeap`,
ordered by deadline, then submission), and each `KqueueTimer` keeps its heap index, so cancelling it is an O(log n)
removal. Setting or cancelling makes no system call: a blocking poll waits no longer than the earliest deadline, and due
timers fire after the turn's events, only those submitted before the turn began, so a completion that resubmits a
zero-length timer cannot stall the loop. `timers` 1.27 → 0.22, the raw loop's kernel timers taking 1.09, and a deadline
armed and cancelled per round trip costs nothing measurable over plain `pingpong` ([`bench/results/user-space-timers.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/user-space-timers.json)).
Due timers fire every turn, polled or not, so the `fairness` ticker gets one tick per message again (`fairness-tick`
46.9 → 4.1).

### `Unblocked` bindings

Every posixlib `@blocking` call switches the thread's GC state, which on arm64 captured registers with libc `setjmp`, two
more system calls each on macOS. `gears.async.io.internal.Unblocked` binds `read`, `write`, `accept`, `sendto`,
`recvmsg`, `ioctl` and the `kevent` that only registers or polls without `@blocking`, since none of them can block on a
non-blocking descriptor. The `kevent` that waits keeps posixlib's binding, so that a collection need not wait for it.
`pingpong` 9.4 → 7.8 (−17%), `concurrent` 4.4 → 3.9 (−12%), `pipelined` 2.6 → 2.3 (−12%), `blocking` 6.9 → 6.3
([`bench/results/unblocked-syscalls.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/unblocked-syscalls.json)). Why the fix belongs in Scala Native is in [`scala-native.md`](scala-native.md).

### Fewer calls per connection

On macOS and FreeBSD an accepted socket inherits the listening socket's `O_NONBLOCK` (accept(2)), so accepting takes
the `accept` alone, without two `fcntl` calls; elsewhere it still sets the mode. Setting non-blocking mode takes one
`ioctl(FIONBIO)` instead of `fcntl(F_GETFL)` and `fcntl(F_SETFL)`, through `Unblocked`, since posixlib's `ioctl` is
`@blocking` and has no `FIONBIO`. Together `connections` 46.6 → 45.1 ([`bench/results/accept-fewer-syscalls.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/accept-fewer-syscalls.json)).

### Batched registrations: tried and dropped

`kevent` takes a list of changes with the wait, but after the reductions above there is almost nothing left to batch:
registration changes are about 0 per operation in every steady-state scenario. Only a new connection still makes one.
Queuing changes in the reactor and applying them with the next poll's `kevent`, or on their own, marked `EV_RECEIPT`,
before a delete or a close on the reactor's thread or once 64 were queued, worked: a refused change failed its op in the
next turn rather than at `submit`. It gained 2.7% on `connections` with one worker and 1.5% with four, within noise
elsewhere ([`bench/results/batched-registrations.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/batched-registrations.json)): not enough for a second path for registrations and for
failures that no longer surface at `submit`. Worth revisiting for workloads dominated by short connections, or by
cancelled one-shot waits, each of which still costs an `EV_DELETE`.

### Read event fields in one call: tried and dropped

`dispatch` reads each event field through posixlib's `scalanative_kevent_get`, one call per field. Reading `struct
kevent` at the offsets its leading fields share on 64-bit macOS and FreeBSD gained about 1% (−0.6% to −1.6%, median of 7
runs, at the edge of the noise; [`bench/results/direct-event-fields.json`](https://github.com/bishabosha/gears/blob/9b296dba/bench/results/direct-event-fields.json)). Not worth depending on the struct's layout,
and it suggests per-event C calls are not where the time goes.

## Profiling findings

Sampling `pipelined` with macOS's `sample` (release-fast, with Scala Native's continuation resume patched to `_setjmp`)
put self time at: I/O system calls 42%, thread-local lookups in the Scala Native runtime (`_tlv_get_addr`) 21%, GC 14%,
continuation stack copying 8%, gears 3%, and the reactor with its gears bridge about 1.4%. What the backend controls is
small; the rest is the runtime's cost per allocation and per thread-local access.

- **Allocations per op.** Per ping-pong round trip (four `perform`s; counted with lldb on `scalanative_GC_alloc*`)
  allocations went from 113 to 58: each op is its own record while pending, so `submit` allocates nothing; ops carry an
  attachment (as kqueue's `udata` or io_uring's `user_data`), where `perform` keeps its waiter and reuses it for the
  op's next submission, with a generation so that a late cancel cannot reach a later submission; polls without waiting
  share one zero `timespec`; each reactor allocates its event buffer once. `pipelined` −10%, `fairness` −6%, `timers`
  −5% ([`bench/results/reuse-op-records.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/reuse-op-records.json)). The 58 left are nearly all gears' and Scala Native's.
- **Ops built per call are cheap.** `pingpong-fresh-ops`, building its ops anew for every message, ran 8.29 µs a
  round trip against `pingpong`'s 8.20. Keeping one op of each kind per descriptor in the reactor, retargeted when
  built, left it at 8.36 and was dropped: callers who want no allocation keep their ops.
- **Thread-local lookups** are mostly Scala Native's safepoint polls (66% of them by caller); see
  [`scala-native.md`](scala-native.md).

## Against cats-effect and fs2

`bench/ce-bench` writes the same scenarios with cats-effect 3.7.1 and fs2-io 3.14.0, whose Scala Native runtime has the
design of gears' pool: workers that take tasks from one another, each polling its own kqueue. Same message size, counts
and result lines; the echo server and the clients each run on a runtime of their own. One worker, conditional
yieldpoints, rerun on the branch as proposed ([`bench/results/2026-10-28-native-vs-cats-effect.json`](https://github.com/bishabosha/gears/blob/8ee048a2/bench/results/2026-10-28-native-vs-cats-effect.json); the first run,
before the affine default and the timers' futures, is [`bench/results/vs-cats-effect.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/vs-cats-effect.json)):

| scenario | gears | hand-written loop | cats-effect |
| --- | --- | --- | --- |
| pingpong | 7.40 | 4.39 | 23.4 |
| pingpong-deadline | 7.46 | 5.39 | 27.0 |
| concurrent | 2.16 | 1.19 | 10.2 |
| connections | 30.2 | 20.3 | 156 |
| pipelined | 0.74 | 0.52 | 2.16 |
| timers | 19.5 | 0.60 | 1.01 |
| fairness (tick) | 1.66 (3.32) | | 2.30 (3.47) |
| mixed (compute) | 15.5 (31.0) | | 1177 (19.3) |

`timers` arms each timer by a future and cancels half of them, which is costly on Scala Native ("What is open"
below); with timers submitted as completions it was 0.18.

- **I/O costs cats-effect 3–5 times what it costs gears.** In ping-pong both its sides sleep in `kevent64` for every
  message, two thread wake-ups per round trip, where gears' polls before blocking leave 0.6, and it makes about 24
  system calls a round trip to gears' 17 at the time, 8 of them `@blocking` calls with the signal-state pair that
  `Unblocked` avoids.
- **`mixed`**: with a compute fiber always runnable, its worker polls kqueue rarely, so a round trip waits about 60
  compute slices; gears polls after every round of tasks.
- **Not like for like**: `blocking` (`IO.blocking` runs on the calling thread and hands the worker's role to another
  thread, where gears hands the task to a blocker thread and back) and `timers` (a fiber per timer). `blocking-io`
  shows the hand-over's cost when each blocking task is followed by I/O: gears 14.22 µs (`blocking` alone 5.68), the
  hand-written loop 10.62 (3.39), cats-effect 22.17 (0.07) ([`bench/results/2026-10-28-native-vs-cats-effect.json`](https://github.com/bishabosha/gears/blob/8ee048a2/bench/results/2026-10-28-native-vs-cats-effect.json)). cats-effect's
  hand-over is off the task's path; gears' task waits for two hops.
- **Responsiveness.** `starvation` is cats-effect's CPU starvation check as a scenario: `concurrent`'s round trips
  while a compute fiber per core works in slices, and a checker sleeping 1 ms over and over, recording how late it wakes
  (lateness in µs; one worker from the rerun above, four from [`bench/results/native-starvation-4-workers.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/native-starvation-4-workers.json)):

  | | gears, 1 worker | cats-effect, 1 thread | gears, 4 workers | cats-effect, 4 threads |
  | --- | --- | --- | --- | --- |
  | p50 | 79 | 885 | 17 | 1092 |
  | p99 | 459 | 3257 | 199 | 2916 |
  | max | 683 | 3688 | 262 | 3424 |
  | round trip, µs/op | 4.4 | 13.6 | 3.8 | 13.6 |

On more workers the comparison is in [`runtime.md`](runtime.md): gears stays 3–6 times faster on `pingpong`,
`connections`, `pipelined` and `timers`.

## macOS's per-process locks, in brief

On macOS every system call on a descriptor (`read`, `write`, `kevent`) takes the process's descriptor-table lock twice,
and `sigprocmask` that sets a mask takes the process lock, so a pool of kqueue reactors in one process cannot scale I/O
beyond about one busy thread: `concurrent` gets slower on 4 workers than on 1. Plain C shows the same. Linux has no
such lock (measured), nor has FreeBSD's descriptor lookup (from its source), and the only way measured to scale on macOS
is several processes. SwiftNIO's POSIX transport, an event loop and kqueue per thread as here, meets the same lock;
Apple's way round it is Network.framework (see [`nwframework.md`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/docs/io/nwframework.md)). The measurements, the XNU source
and the designs tried around it are in [`runtime.md`](runtime.md); the `setjmp` override, which takes the signal mask
out of every continuation resume in programs that link this backend, is in [`scala-native.md`](scala-native.md).

## A burst of connections

A JVM client opening 10,000 connections at once to a kqueue server saw most of them reset mid-response. Two causes,
reproduced on one machine:

- **The server ended at the open-file limit.** A macOS shell's soft limit is 256; the JVM raises its own to the hard
  limit at start (`-XX:+MaxFDLimit`), Scala Native did not, so around connection 250 `accept` failed with `EMFILE`, the
  failure left the demo's accept loop, the process ended and the kernel reset every connection: 1,500 of 10,000
  succeeded. Now the Native runner raises the soft limit (`FileLimit`), `NonBlocking.accept` retries `ECONNABORTED` (a
  queued connection its client gave up on) as the JDK's accept does, and the demo servers accept through
  `Sockets.serve` (the testkit's demos), which outlives a failed accept, waiting 10 ms after one.
- **The listen queue overflows.** macOS caps a listener's backlog at `kern.ipc.somaxconn`, 128, and resets connections
  arriving while it is full, whatever the server: a JDK `ServerSocket` with backlog 16 lost every one of 1,000. Listeners
  now ask for 4096, which the kernel caps, so raising the limit (`sudo sysctl -w kern.ipc.somaxconn=4096`) takes effect.
  At 128, the accept loop's place in the pool mattered: an op finishes without suspending once per turn, so each further
  accept waited behind every connection future spawned so far. `acceptAll` takes every pending connection, at most
  `max`, in one op, and `Sockets.serve` uses it with 64: in five alternating pairs against one accept at a time, 89.9% of
  10,000 answered on average against 83.8%, against 94–99% for a JDK server with a thread blocked in `accept`. The rest
  is the listen queue's 128. The bench's `--connections` should stay at or under 128 with more than one worker, for the
  same reason.

## Errors in kqueue itself

Errors from kqueue fail the op they are for, and the loop goes on:

- A filter kqueue refuses to register, such as with `EBADF` for a descriptor closed behind the reactor's back, takes
  the command out of its slot and fails it alone (`registrationFailure`), queued for the next turn, or thrown by
  `trySubmit`, which leaves the op free to be submitted again. Before, the throw left the command in its slot and every
  later command on the descriptor failed with "already pending".
- An `EV_ERROR` event in a poll fails the command pending on that filter, and its slot forgets what it knew of reading
  (`dispatchError`).
- A filter deletion, for a cancelled command or a read filter left over from another reactor, is best effort: a filter
  left over fires once and finds nothing.
- A poll interrupted by a signal (`EINTR`, which `kevent` returns rather than restarting) reports no events.

Only a poll failing otherwise, which means the kqueue itself is broken, still throws; the worker then reports it and
gives up only after 16 such turns in a row (see [`runtime.md`](runtime.md)). `LoopErrorSuite` registers a filter for a
descriptor that is not open, from `submit` and from `trySubmit`. No interrupted poll is tested: none can be provoked
without a signal handler. A completion that throws is reported rather than ending the turn; that is the runtime's, in
[`runtime.md`](runtime.md).

**SIGPIPE.** Every socket gets `SO_NOSIGPIPE` when created (0x1022 on macOS, 0x0800 on FreeBSD; posixlib defines
neither), so writing to a peer that has gone fails that connection with `EPIPE` rather than ending the process; accepted
sockets inherit it. The write ends of pipes and FIFOs get macOS's per-descriptor `F_SETNOSIGPIPE` (73). FreeBSD has no
per-descriptor equivalent for those, so there a write to a pipe or FIFO whose reader has closed still raises `SIGPIPE`
unless the program ignores the signal.

## Closing a handle

A handle is a `KqueueBackend.Handle` holding the descriptor, and closing it is as [`handles.md`](handles.md) describes
("Proposal 3, as built"): a close concurrent with a use leaves the descriptor's close to the last use, as Go does. What
is the reactor's: `DescriptorTable`, process-wide, keeps a generation per descriptor that every close bumps, and a close
tells every reactor, which fails the commands pending on the descriptor and forgets its registration, before the number
can be reused; an event that arrives for the old generation is recognised as stale. The kernel drops a closed
descriptor's filters itself, so a close costs only the `close`. How a descriptor moves between reactors is in
[`runtime.md`](runtime.md).

## Another facility's ops on the same reactor

The Network.framework backend runs on kqueue's reactors, and comes in a pull request of its own, with what it adds to
them: ops of another I/O facility beside kqueue's (`Notified`), attempted as they are submitted and again each time
the facility notifies the reactor, woken by a user event of the facility's own (`ReactorExtension`), and handles for
its I/O objects that kqueue's own ops refuse (see
[`nwframework.md`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/docs/io/nwframework.md)).

## What it shares with NIO's reactor

What kqueue's reactor and NIO's do alike is `ReactorCore`, with what their ops keep in `LoopOp` (both in
`jvm-native/.../internal`): the timer heap and moved deadlines, handles' turns, the ready and closing queues, promises
and blockers, submission past the backend's own kinds, cancel and time-outs, `close`, the turn itself (`runOnce`),
and the openers' bookkeeping (`LoopOpensAtOnce`, `LoopOpensPipe`, `LoopConnects`): what an opener keeps of what it
opened, and `abandon`, which closes it and empties its slots if the submission is withdrawn; each backend says only
how a handle is opened and closed again. Submission refuses an op in one order on every backend, the loop reactors and
`NioOnCaller` alike: a slot still holding a value, then the op as it stands (`LoopOp.refusal`: a read into a buffer
with no room, a receive into one with no capacity, an empty packet on a connected socket), then its handle's turn held
by another op. NIO had checked the second in `perform`, each time the op was performed, after the turn. Each reactor keeps how it waits: its commands' submission (`submitOwn`, tried before any other type
test), withdrawal and dropping on close, failing what was pending on handles closed meanwhile (`deliverClosed`), what
else it delivers each turn (`deliverOwn`: FIFOs and notified ops here, regular files and interest changes on NIO), and
the poll (`poll`).

The two had delivered a close's failures at different points in a turn, for no reason either recorded: kqueue queued
them to run before the ready queue, and drained the closed descriptors again after the timers, a pass the next turn's
first one made redundant; NIO failed them after the ready queue and resolved promises. Now a turn fails them first, on
both, directly, before anything else it delivers. Only the order of completions within a turn changed; a close still
fails its handle's ops at the start of the turn after it is noticed, and wakes a blocked poll.

A close op's own completion goes through `ReactorCore` too, however it finishes (`LoopOp.closes`): at once, as a
promise completed by the last use of the descriptor on another thread or on the loop's own. It is queued in `closing` and runs at the end of the next turn, after that
turn's `deliverClosed`. Only a close done at once had been: one that waited for a use in progress completed as any
promise does, so a use ending on the loop thread, in a completion or a dispatch, closed the descriptor and resolved the
close in the middle of a turn, and the close completed at its end, a turn before the read pending on the handle failed.
Cancelling a close whose completion is
queued now returns false, as for any op that has finished: before, kqueue's close, a promise, was detached from the
queue it was in, and the loop failed taking its completion again.

Waking the loop from another thread is `ReactorCore`'s too (`wakeup`): every waker first asks (`wakeRequested`), and
the loop, about to block, marks itself parked and then checks the ask (`mayBlock`), so that one always sees the other
and no wake-up is lost; a spin before blocking ends as soon as one is asked (`wakeAsked`). Whether a wake-up also
signals a loop that is awake is each backend's choice, by measurement (`signalsWhileAwake`). NIO signals only while
parked, sparing `Selector.wakeup`'s write to its pipe. kqueue signals while awake too, as it always had: not signalling
took `blocking` from 5.5 to 60 µs a task, and to 25 with the spin ending early. The signal's `kevent` call keeps the
blocker thread that resolved the task awake for its length; without it the thread went back to sleep at once, and
each next hand-off paid a thread wake-up, the loop finding the task done only after its spins.

## What is open

- **`fairness` on four workers is bimodal**: about half the runs give the ticking future a turn every 14 µs, the rest
  every 3.4, the I/O beside it twice as fast in the slow mode (0.8 against 1.7 µs an op); 16 and 17 slow runs in 30,
  before and after the wake-up was shared, so it predates it. Which mode a run settles in is likely where the pool's
  stealing leaves the ticker relative to the I/O, and is to be looked into in [`runtime.md`](runtime.md)'s terms.
- **Cancelling a gears `Future` is expensive on Scala Native**, which `timers` shows now that it arms each timer by a
  future and cancels half of them: 20.7 µs a timer (release, 7 runs) against 0.41 when timers were submitted with
  completions, 0.61 for the hand-written loop and 1.13 for cats-effect ([`bench/results/2026-10-25-timers-native.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/2026-10-25-timers-native.json)).
  The cause is in gears and Scala Native, not the reactor; see [`runtime.md`](runtime.md).
- **FreeBSD** is untested. The backend should build there, `SO_NOSIGPIPE` has its FreeBSD value, and `setjmp_override.S`
  is for macOS on arm64 only. FreeBSD's descriptor lookups do not take a per-process lock, so its pool should scale
  where macOS's does not (see [`runtime.md`](runtime.md)).
- **Batching registrations**, revisited for workloads of short connections or many cancelled one-shot waits.
- **A blocking task's hand-over**: passing the worker's reactor and queue to a spare thread while a task blocks, as
  cats-effect does, rather than two hops to a blocker thread and back (see `blocking-io` above).

## The plain key-value client's race, fixed

The hand-written kqueue key-value client (`kqueueExampleKeyValue`, the `kv4`, `kv6` and `kv` commands) failed now and
then with `ENOTCONN` ("Socket is not connected"), seen once in a full demo run on 2026-10-28. Its send was a command
tried as it was submitted, before the socket was writable; checking a non-blocking connect then (`SO_ERROR`) passes
while it is still in progress, so the write went to a socket not yet connected, and its read, submitted at the start
too, could fail the same way. Its send now waits for writability until the connection is verified, as the backend's
own connect does (`ConnectTo.tryFirst`), and its read starts once the first send has completed. The demo suite skipped
the check against the plain demos meanwhile, and runs it again.
