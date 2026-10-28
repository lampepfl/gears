# Why the interface is this shape

[README.md](README.md) describes the interface as it is: the capabilities, the two kinds, `Io`, and the semantics every
backend keeps. This note records the decisions behind it: for each, what it replaced and why. Handles, which became
objects of each backend's own class, have a note of their own ([handles.md](handles.md)), as do file ops
([files.md](files.md)) and the JVM's backends ([jvm.md](jvm.md)).

## Capabilities are types, asked for by intersection

What only some backends can do is a trait mixed into the backend, each narrowing `ops` to its own (`IpSockets`,
`RegularFiles`, `Pipes`, `Resolving`, `Blocking`, `UnixStreams`, `UnixDatagrams`, `Fifos`), and a program asks
for the intersection it needs (`IpSockets & RegularFiles`, or `Io[IpSockets]` in a helper). The alternative is one
`Backend` with every op, failing at run time where a backend lacks one. The backends differ in fact: NIO has neither
Unix datagram sockets nor FIFOs, since the JDK has no Unix datagram channels and can only block on a FIFO, and a
browser backend would have no sockets or files of these kinds at all. A missing capability is then a compile error at
the place that needs it, not an exception in production, and a helper states what it needs in its signature.

Addresses are typed by family for the same reason: `IpAddress` and `UnixAddress`, and `send` takes an address of its
socket's family.

## Readiness and blocking ops are capabilities, and so is the kind

The base `Backend` was once every op a backend has plus reactors, readiness (`whenReady`, `readNow`, `writeNow`) and
blocking ops. Each of the three became something a backend may or may not be: running ops on reactors became a kind
(`ReactorBackend`, against `CallerBackend`), and readiness and blocking ops became capabilities. What forced it was a
backend with no reactor, which performs each op on the calling thread: first an experiment over the JDK's blocking
channels, now NIO's default, `NioOnCaller`. Such a backend has no loop to run a readiness callback on and no reactor to
submit to; with the old shape it would have had to fake both. The base is now what every backend has: streams, timers,
closing, and pools of buffers.

`Blocking` promises the same on either kind (skipped if cancelled before it starts, interrupted if running, its result
never written after a cancel); an early version documented it as a reactor's alone, which `NioOnCaller` contradicted.
NIO does not have `Readiness`: it was dropped from NIO when its caller path stopped being a reactor's side door, since
nothing needed it there and kqueue and Network.framework kept it. It later left the interface altogether ("Readiness is
kqueue's own", below).

## Readiness is an op that completes

`whenReady(handle, interest)(body)` ran `body` on the reactor thread and waited again while it returned false. A higher
level would inherit a closure run on a thread it does not own, in the lowest layer. It is now `ready(handle, interest)`,
a `Repeatable` op that completes once the handle is ready, level-triggered: the caller uses the handle without waiting
(`readNow`, `writeNow`) until it would block, then performs the op again, which completes at once while the handle is
still ready. On kqueue it is the same one-shot, level-triggered filter the closure waited on. Readiness is then an op
like any other: cancelled, timed out and raced the same way. It later left the interface ("Readiness is kqueue's own",
below).

## Closing a handle fails the ops pending on it

`Ops.close` makes every op pending on the handle fail with an `IOException` before the close completes, on whichever
reactor it waits, a `perform` waiting on one throwing it. Before, a reactor's close said only that handles belong to
the caller: closing deregistered the channel's key, but the command attached to it was never completed, and a future
performing it waited for ever. That was found by a check ported from the reactor-less backend, where a close wakes the
waiter on the handle's own selector. The rule is what the JDK does for a thread blocked on a channel that another
closes, and what makes a stale op fail rather than complete on whatever reuses the number ([handles.md](handles.md)).

On kqueue it also removed a layering fault: a thread-local list of listeners that the system-call layer called on
close, so that only the closing thread's reactor forgot the descriptor. Now the handles' close ends the descriptor's
generation and tells every reactor, from any thread. NIO's reactor finds the pending commands through its own map of
waiting commands rather than the selector's key, since the select that the wake-up ends may already have dropped the
closed channel's key.

## A time limit is part of the submission

`Submitter.submit`, `trySubmit` and `Io.perform` take `timeoutMillis` (`Backend.NoTimeout`, negative, for none). The
alternative was gears' `withTimeout`: a future for the op, a future for a timer, and the loser cancelled. That is two
extra futures and, on a backend where the timer lives elsewhere, two hand-offs per op. With the limit on the
submission, a reactor keeps the op in its timer heap beside the timers (`TimerHeap`, whose entry is the base of every
op), removes the entry when the op finishes first, and withdraws a due op exactly as a cancel would, failing it with
`TimeoutException`. On a caller backend the limit bounds the retries and the wait. A read that timed out has done
nothing: its buffer is as it was and the handle as usable as before, which no JDK API gives.

`pingpong` against `pingpong-deadline` (a read with a limit each round trip), NIO over Unix sockets, JDK 27, macOS, one
worker, median of 5, µs per op:

| scenario | caller path, before | caller path, after | pool, before | pool, after |
| --- | --- | --- | --- | --- |
| pingpong | 6.70 | 7.16 | 9.63 | 9.68 |
| pingpong-deadline | 10.24 | 6.02 | 9.64 | 9.37 |

The limit had cost 3.5 µs a round trip on the caller path, the two hand-offs of the timer, and now costs nothing. On
the pool the timer was already on the worker's own reactor. gears' `withTimeout` still races, for code that is not an
op; since `Io` is the program's `AsyncOperations` (below), its timer is at least the backend's.

## `Io`: where a program runs, which has a backend and is not one

`perform` belongs to `Io[+B <: Backend]`, which has `val backend: B` and adds only where ops run. The shapes it
replaced, in order:

- **`perform` on the backend.** A reactor backend's op can only be performed where a worker can run it, and a bare
  backend cannot know whether it is on one. With `perform` on `Io`, and the runtime the only maker of an `OnPool`,
  performing a reactor backend's op without a pool is a type error, not a run-time check.
- **A path-dependent proof, `backend.Context`.** It could not be narrowed: a context for `r` is not one for `r` seen as a
  `ReactorBackend`.
- **A contravariant `Context[-B]`.** It could be narrowed, but cost every helper a second context parameter.
- **`Io` as a refinement in each capability** (`type Io <: Io & IpSockets { type Op = ... }`), so that a backend
  with several capabilities had an `Io` with all of them. It needed no type parameter, but an `Io` was a `Backend`, and
  each reactor backend had an `Io` class restating its capabilities.
- **A common supertype, `Facilities`**, mixed into both a backend and its `Io`, so that an `Io` was no longer a
  `Backend`. It still restated the backend's capabilities, and an `Io` had a `type Io`.

Now a capability is a `Backend` narrowed to its ops and knows nothing of `Io`; `Io` is covariant, so an `Io` over a
`IpSockets & RegularFiles` is an `Io[IpSockets]`; and handing an `Io` to the runner as if it were a backend does not
type-check, with no guard needed. The per-backend `Io` classes are gone. What it costs: a run-time test of a capability
is on the backend (`io.backend match { case b: (Blocking & io.backend.type) => ... }`), and a value holding such a
backend needs its type written, or the compiler loses that its `Op` is `io.Op`. The `Io` is passed explicitly because
the op's type is path-dependent on it (`io.Op` needs `io`), which is a better reason than use.

## The kinds: `OnPool` a contract, `OnCaller` anyone's

`OnPool` declares what a pool offers a program and has no body: `perform` and `yield` over its backend's ops. The
runtime implements it (`gears.async.io.internal.Pools.bound`). Before, `OnPool` had bodies calling the runtime, then
delegated to a `Pool` object the runner bound each backend to, then mixed in a forwarding trait; each step moved
runtime out of the interface, and the last removed the forwarding along with the per-backend `Io` classes, since the
runtime can return an `OnPool` directly. `OnCaller` is a final class over any `CallerBackend`, performing through
`CallerBackend.performHere`, which is `private[io]`, so it needs no runner and works from any `Async` context.

`OnPool` also had `submit`, an op started with a caller's own completion and returned as a `Submission`, which an
independent review made a `Cancellable` linked to the caller's group, so that a group's end cancels a leftover
submission and waits for it, and then an `Async.Source` of the op's outcome. It was later removed: ops in flight
together are futures performing them, and nothing but a bench used it ([README.md](README.md)).

## `Io` is the program's `AsyncOperations`

`Io` extends gears' `AsyncOperations`: `sleep` performs a timer op, so gears' `sleep`, `withTimeout` and `Timer` run on
the backend's timers, and `OnPool` overrides `yield` with the pool's own, which queues the resume on the worker without
spawning a task. The runner gives the program its `Io` as the `AsyncOperations` beside its `Async`. This replaced a
`yieldNow` of the I/O library's own and a module of free functions (`perform(r, op)`, `submit`) that existed only to
reach the right reactor: a program now uses gears' own names, and its timers do not depend on gears' default scheduler.
Checked on every backend: `sleep(20)` sleeps, and `withTimeout` around a read throws `TimeoutException` with the buffer
untouched.

With `perform` on `Io`, the backend-level copy of an op's attachment (`CompletionBackend`), which existed only for the
gears waiter, went too: the attachment belongs to the op and is read through any submitter, and `ReactorBackend` is a
`Backend` and a reactor factory, nothing between.

## The runner is out of the interface

The interface once had `Backend.start(runner, workers, policy)` on every backend, and a `Runner`, so that every
backend started the runtime itself. Now `Io.run(using backend)` dispatches once, by the backend's kind,
with intersection patterns and no cast, and `run(workers, policy)` exists only for a `ReactorBackend`, the one kind
with workers and a policy. `run` returns the program's value on both platforms, as `Async.blocking` does: before, every
contract test smuggled its result out in a `var`.

## The interface names nothing of the runtime, and the build keeps it so

The interface refers to the gears library and to nothing of the runtime: no pool, worker, scheduler, policy or thread.
At first that was a review finding to check by hand; then three modules made a reference from the interface to the
runtime a compile error. The interface has since moved into the gears core, in `shared/`, which compiles for Scala.js
too, where the runtime (`jvm-native/`, `jvm/`, `native/`) does not exist, so the same reference still fails a build.
What is hidden from programs within `gears.async.io` is hidden by package ([README.md](README.md)).

The interface is one file per concept (`Submitter`, `Backend`, `Io`, `Ops`, `Addresses`, `BufferPool`), and the
implementation the backends share (`Blockers`, `TimerHeap`, `HandleTable`) is in `gears.async.io.internal`, so the
interface package is interface only.

## One set of contracts for every backend

The checks through `perform` alone, abstract over the backend and over how a platform starts a program, are
`FuturesContract`, beside `ReactorContract` and the per-capability contracts in `io-testkit`; each backend instantiates
them in each of its modes, and a backend written elsewhere can run them (`gears-io-testkit`). A check found on one
backend runs on all: the cancellation and close checks above were found on one and fixed on every one.

## The independent reviews

Two reviewers, given the code and the goal's wording and told to distrust the README, checked each clause. What they
found and what changed:

- The Native runner matched on `KqueueReactor` to print counters, so the build made it depend on the kqueue backend:
  the reactor's `toString` carries its counters and the runner prints its reactors.
- `Blocking` was documented as reactor-only while `NioOnCaller` has it: it now promises the same on either kind.
- `HandleTable`, implementation, was in the interface: it moved to `gears.async.io.internal`.
- `run` returned `Unit`: it returns the program's value.
- A submitted op was outside the group that made it, and the cancellation guarantee a wrapper relies on was stated in
  the README rather than on `Io.perform`: both fixed, the guarantee now on `perform`.
- The second review asked for a contract check that a scope's end cancels what was submitted in it, for the streaming
  wrapper's failure to reach its consumer and for chunks read ahead to be released when the consumer stops early, and
  for the wrapper to live outside `gears.async.io` so that it sees only what a program sees: all done (`higherlevel`).
  The early-stop check found a bug in every backend: a pooled read failing on the read call threw without releasing
  the buffer it had taken, so the pool could not be closed.

A script turned each clause into greps for a while and was audited and then removed: what it measured is now the
build's module boundaries, the types and the contract suites.

## A handle's kind is its type

A handle's type said which backend it belonged to, not what it named, so an op on the wrong kind of handle (a read on a
listener, an accept on a connection, an IP send on a Unix datagram socket) compiled, and each backend failed it
differently, one by crashing. The alternatives were a kind recorded in every handle and checked as each op is built,
which every backend would have to do the same way, or the kind in the type. The type won: the compiler refuses the
misuse, nothing is checked or stored at runtime, and the openers already knew what they opened. The cost is in
programs, which name `io.Connection` or `io.Listener` rather than `io.Handle`, as they name the capabilities they need;
and in FIFOs, whose direction was a `Boolean` and is now two openers, since it picks the type. See
[`handles.md`](handles.md), "Kinds of handle, as types".


## A listener binds as `SO_REUSEADDR` does, with no flag

`listen` took `reuseAddr`, and without it refused a port whose earlier connections lingered in `TIME_WAIT`. Network.framework
cannot refuse such a port when the connections were its own, from any process: they are in no table a public call
reads, and a socket held bound fails its own bind (probed on macOS 27.0). It emulated the flag with a socket bound and
closed before it bound, which missed a connection made and ended in between. The alternatives were the flag as a
capability Network.framework lacks, or no flag. No flag won: Go, libuv and Rust's standard library always bind this
way, a second listener on a port is refused all the same, and refusing a lingering port is the restart that waits half a
minute, which the option exists to avoid. A strict listen can come back as a capability if a program needs one.

## Readiness is kqueue's own

`Readiness` (`ready`, `readNow`, `writeNow`) was a capability that only kqueue had: NIO dropped it, and
Network.framework's connections have no readiness to report. Nothing written against the interface needed it. Its
only users were two demos, which drained a pipe or a FIFO after waiting for it, and used a pending `ready` as an op to
cancel: a repeatable `read` does both, completing once bytes are there with all that are, and a read cancelled before
they come has taken none. A server written as a state machine of completions on one reactor (`ReactorServerSocket`)
does what kqueue's readiness-driven server does, and runs on every reactor backend, Network.framework's included. So
it left the interface: the ops say what is done, not what is waited for. Kqueue keeps `ready`, `readNow` and
`writeNow` on `KqueueOps`, beside its `Command`s, for code written against kqueue, and the checks of them moved to its
own suites. Readiness of a descriptor handed to another library, the one use the completion ops cannot cover, needs
the descriptor too, which the interface never hands out. The blend of kqueue and Network.framework under way then has
no capability it would have to refuse for some of its handles.

## The Network.framework backend runs on kqueue's reactors

The Network.framework backend had a reactor of its own, and, to keep one wait for everything, put pipes and FIFOs on
Dispatch sources and emulated a bound datagram socket with a flow per peer. That emulation could not keep a kernel
socket's promises: from a fixed port, a send to a peer that had not sent first was lost, since Network.framework
refuses an outbound flow from a port its listener holds (probed), and per-peer flows needed an idle limit that lost
packets in its turn. Apple's own advice (TN3151) is Network.framework for TCP and for UDP between two peers, and BSD
sockets for what it does not support; and over loopback it uses the kernel's sockets at several times kqueue's cost.
So the backend became kqueue's reactors, which run another facility's ops as `Notified` ops woken by a user event on
the kqueue (`ReactorExtension`), with Network.framework for TCP and connected datagram sockets off loopback and
kqueue's code for the rest: Unix domain sockets, which it gained, bound datagram sockets, loopback, pipes, FIFOs and
files. The interface did not change: what goes where is the backend's choice, made by each opener's address and each
op's handle, and `NWFrameworkBackend.Everywhere` keeps loopback on Network.framework for checking it.

## Datagram sockets are bound or connected, and Unix sockets need no IP

A datagram socket was one kind, `Datagram`, opened by `datagram(local, remote)` with either address optional, so
whether it was bound or connected was known only at run time: a connected socket's send to another address, a bound
one's send with no address, and a receive asking a connected socket for a sender it can only have one of, all compiled
and failed with an `IllegalArgumentException` (`notThePeer`, `notConnected`). They are now two kinds, as stream sockets
are a `Listener` and a `Connection`: `bindDatagram(local)` opens a `BoundDatagram`, which sends to an address and
receives with its sender, and `connectDatagram(local | null, remote)` a `ConnectedDatagram`, which sends and receives
with no address; Unix datagram sockets likewise (`UnixBoundDatagram`, `UnixConnectedDatagram`). The refusals are gone,
compile errors in their place. The Network.framework backend made the split a matter of routing too: a bound socket is
kqueue's, a connected one Network.framework's off this host.

`UnixStreams` and `UnixDatagrams` extended `IpSockets`, so a backend with Unix sockets had to have IP ones, and code
taking a Unix connection asked for IP sockets to accept or shut it down. Each family is now its own capability. The ops
a stream socket of either family has once open (`accept`, `acceptAll`, `shutdownOutput`) are `StreamSocketOps`, the
capability `StreamSockets`, which `IpSockets` and `UnixStreams` both extend: code serving connections of either family
asks for that, and code opening sockets of both asks for both, `IpStreams & UnixStreams`.

IP sockets were still one capability, TCP and UDP together, as BSD sockets bundle them; but hosts differ there too:
Deno has TCP and puts UDP behind a flag, Cloudflare Workers have outbound TCP and no UDP. So the capabilities now go by
address family and transport, one each, none implying another: `IpStreams` (`IpStreamOps`: `connect`, `listen`),
`IpDatagrams` (`IpDatagramOps`), `UnixStreams` and `UnixDatagrams`, with `IpSockets` the alias for both IP ones, and
`IpSockets` for a backend with both. The contracts split alike: `IpStreamContract`, `IpDatagramContract`, and
`SocketContract` for both with the checks that use both. Whether a host can listen as well as connect, which Workers
cannot, is not split until a backend needs it.

## Socket options are asked of the backend when the program runs, and `NoDelay` is on

Socket options are not capabilities, as the socket families are: whether one is there depends on the host as much as on
the backend (NIO's `SO_REUSEPORT` on the system, its keepalive timings on the JDK, kqueue's on macOS's version), so a
type would promise what some hosts cannot keep. They are keys (`SocketOption`), given as a socket opens, the one shape
every backend can honour, Network.framework's own sockets taking them only then; `supports` and `canChange` say what
this backend has here, and an op built with an option it lacks fails as it is built, as the JDK's `setOption` throws and
unlike Netty's, which logs and ignores. What needs machinery of its own (packet info, ECN, batching) will be a
capability. `NoDelay` is on unless set off, on every backend, as Go has it: TLS records, HTTP/2 frames and WebSocket
messages are small writes, which Nagle's algorithm with delayed acknowledgements can hold back by tens of milliseconds;
each backend left it off before, as its system does.

## Timers take a duration, and a deadline timer moves

`timer` took whole milliseconds, and the only way to re-arm a pending timer was to cancel what performed it and perform
it anew, which through a future costs a thrown cancellation, about 20 µs on Scala Native. QUIC re-arms its loss,
acknowledgement and pacing timers for almost every packet. So `timer` takes a `FiniteDuration`, to what the backend can
wait for, and `deadlineTimer` is a timer whose deadline moves while it is pending, from any thread: later for nothing
but a store, the reactor filing it again when the old deadline comes, as Go's runtime and Netty do; earlier by filing it
again, or waking its reactor. A time limit on a timer's submission still fails it when it comes first.


## Reads and writes take several buffers

A frame's header and its payload (a TLS record, an HTTP/2 or WebSocket frame, HTTP headers with a body) are written
with one call rather than two or a copy into one buffer: `write(handle, bufs)` drains them in order, `read(handle, bufs)`
fills them in order, and `send` takes several buffers as one packet, on IP and Unix datagram sockets alike, each with the rules of its one-buffer form (at
least one byte, a read into buffers with no room refused, an empty write completing at once, a datagram never empty).
kqueue uses `readv`, `writev` and `sendmsg`; NIO its scattering and gathering channels, joining the buffers of a bound
datagram send, which has no gathering form; the Network.framework backend gathers a write into one buffer the framework
then owns, the one copy a single write already costs there.

Pooled writes, a buffer from the backend's pool handed over for good and released once sent, were weighed alongside and
left for later: they save the copy only where a backend copies (Network.framework, about 3% of a 128 KB message over
loopback; io_uring's registered buffers and zero-copy sends; Node), nothing on kqueue or NIO, and their completion rule
differs (complete only once all is handed over). To measure on Network.framework, and to settle with an io_uring backend.

A pooled vectored read was weighed too. Scattering into pool buffers means nothing, since a vectored read is the caller
placing the bytes and a pooled read the backend; what could mean something is a pooled read completing with several
pool buffers, a chain, each released on its own. It saves nothing on kqueue or NIO, where one read into one buffer is
already the least, nor on the Network.framework backend, which copies received content, however many regions Dispatch
holds it in, into one pool buffer in one pass (`nwf_data_copy`), so that nothing of Dispatch's reaches the reactor. It
would pay on io_uring, whose receive bundles fill several provided buffers in one completion: to settle with an
io_uring backend, with pooled writes.

## A datagram socket's and a listener's ops take turns

One op reading a handle and one writing it could be pending at a time, a second refused: the rule streams need, since
two reads would take a stream's bytes in no order and two writes interleave theirs. It held for datagram sockets and
listeners too, where nothing calls for it: a receive, a send and an accept are each whole, and a QUIC endpoint's
connections all send on one socket. A program had to funnel them through one task of its own. Their ops now take turns,
as Go's fd lock queues a socket's concurrent callers: one submitted while another is pending waits behind it, on any
submitter, in the order they came, its time limit running and a cancel taking it out of the queue (`Turns`, which each
backend's handle has one of per direction). They take turns rather than wait at once: on kqueue, one reactor at a time
owns a descriptor's read filter, so receives waiting on several reactors would leave all but one unwoken. Streams keep
the refusal.

## The public interface is `Backend` and `Io`

`Submitter`, `Reactor` and `Completion`, and `Io`'s kinds `OnPool` and `OnCaller`, were in `gears.async.io`, beside
`Backend` and `Io`, as if a program might submit ops to reactors itself or make its own `Io`. A program performs ops
with the `Io` the runner gives it, and nothing more is supported, so they are in `gears.async.io.internal` now: what
the runtime, the backends and their contract suites use. Submitting to a reactor directly is still possible, as
`ReactorServerSocket` shows, but unsupported. So are a backend's kinds, `ReactorBackend` and `CallerBackend`: the
runner tells them apart, and `Io.run` takes any `Backend`, its pool form refusing a caller backend, which
has nothing for a pool to run, with an `IllegalArgumentException`; it gives a pool's program an `Io`, no longer an
`OnPool`. One reason for queueing a datagram socket's ops in the backends (see above) was that a program submitting to
reactors itself should have the same rule; that reason is gone with this, the others stand: a backend may do better
than queue, and an op's time limit and cancel cover its wait.

## The runner chooses its pool

`Io.run` took a pool's size and its `TakePolicy` as a program's choice, with one worker unless told
otherwise. A program should not need to know how its futures are carried, so `run` now has no knobs: over a backend
with reactors it starts a pool of one worker per available processor, under the affine policy, and over any other it
runs on the caller. The knobs are internal (`ReactorRuns.run(workers, policy)`, and `TakePolicy` with it), for the
benches, demos and tests that compare them; a caller backend given to it is refused, as a pool would have nothing to
run.

## Unix sockets take options, and what a backend supports is asked per kind

Options were for IP sockets alone, though a Unix socket has buffers to size as any socket does. The Unix openers
(`connect`, `listen`, `bindDatagram`, `connectDatagram`) take `SocketOptions` now, and the Unix ops have
`SocketOptionOps`; of the options, the buffer sizes apply to Unix sockets, each `SocketOption.Kind` having a Unix
counterpart, and TCP's and IP's (`NoDelay`, keepalive, don't-fragment, reuse-port) are refused on them as the op is
built. `supports` and `canChange` take the kind of socket: on the Network.framework backend the answer differs by
family, its Unix sockets being kqueue's, which take the buffer sizes and change them, where its own IP sockets take
neither.

## `Io.run`

The runner was `ReactorFutures.run`, one object per platform, named for reactors, which a program no longer sees. It is
`Io.run` now, on `Io`'s companion in the shared interface, so a program names only `Io` and its backend; each
platform's implementation is internal (`internal.Runner`, with Scala Native's suspension support beside it), and Scala.js,
which has no backend yet, refuses.

## A time limit is a duration

`Io.perform(op, timeoutMillis: Int)`, with `Backend.NoTimeout` (-1) for none, sat beside timers that take a
`FiniteDuration`. It is `perform(op, timeout: FiniteDuration)` now, and `perform(op)` has no limit, so there is no
sentinel to pass; the limit is kept to the millisecond, rounded up so that it is never sooner than asked, and its
rules, what a cancelled or timed-out op leaves behind, are stated on `perform` rather than on the internal
`Submitter.submit`. Within, the kinds of `Io` still take milliseconds (`performWithin`).

## `RegularFiles`

The capability of files was `Files`, which collided with `java.nio.file.Files`, imported by most code that handles
files, and claimed more than it has: it opens regular files and refuses anything else, FIFOs among them, which are
`Fifos`. It is `RegularFiles` now, the system's own term; its ops stay `FileOps`, and its handles `File`.

## Any buffer, on every backend

kqueue's system calls need a buffer's own memory, which a buffer on the heap has none of, nor, on Scala Native, one
from `ByteBuffer.allocateDirect`, which is on the heap there; nor will Scala Native give a read-only buffer's. Such a
buffer failed in the op's system call with a bare `UnsupportedOperationException`, then was refused as the op was
built, while NIO took it: which buffers worked depended on the backend. Now every backend takes any buffer, kqueue's
and the Network.framework backend's copying through memory of their own for one with none (`NativeBuffer.staged`),
so that only a buffer with native memory behind it costs no copy; and every backend refuses, as the op is built, a
read-only buffer to read into.
