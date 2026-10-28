# gears I/O on the JVM: the two NIO backends, and what carries a program's futures

On the JVM a gears future is a virtual thread, and suspending it blocks only it. So `perform` is one call on every
platform, and what differs is how an op waits and which threads carry the futures waiting on it. NIO
(`gears.async.io.nio`, `io-backends/nio/`) gives two backends with the same ops and handles
(`NioCapabilities`): `NioOnCaller`, a caller backend and the default, and `NioBackend`, a reactor backend whose
program runs on the reactor pool. This note describes both, why the default is what it is, the measurements that
decided it, and what was tried and dropped on the way. The interface is in [README.md](README.md), handles in
[handles.md](handles.md), file ops in [files.md](files.md), the reactor pool in [runtime.md](runtime.md), and how to
run the bench that every number here comes from in [bench.md](bench.md).

Unless a table says otherwise, the measurements are the bench's scenarios on macOS arm64, JDK 27, one worker on each
side, in µs per op, the median of 5 runs; files are under `bench/results/`. The JDK's own blocking channels on virtual
threads (`JdkBench`, the `jdk-vthreads` build) and cats-effect with fs2 (`bench/ce-bench`) ran the same scenarios.

## Cancellation must leave the handle open

This constraint decides more of the design than any measurement. A gears program cancels ops as a matter of course:
`withTimeout` and `Async.select` cancel the loser of a race, and a group cancels what is left when it ends. The
interface therefore promises that a cancelled or timed-out op leaves its handle usable and its buffer untouched
([README.md](README.md), "What the interface fixes"). The JDK cannot cancel a blocking channel call except by
interrupting the thread, and an interrupted call closes the channel. A backend over the JDK's blocking channels would
turn every timeout into a lost connection, so it cannot keep the contract. fs2 on cats-effect's selector poller, Tokio
and Go all keep the handle open when an operation is cancelled, for the same reason.

With public API only, keeping the handle open means waiting on readiness ourselves, and that means a `Selector`. Both
backends do: `NioOnCaller` on a selector of each handle's own, `NioBackend` on its reactors' selectors.

## `NioOnCaller`, the default

`NioOnCaller` is a `CallerBackend`: each op is done on the calling thread, which is a virtual thread for any future of
the program. It needs nothing of the JDK beyond its public interface: `Io.run(using NioOnCaller)` runs the
program on gears' own virtual threads, on the JDK's scheduler, and hands it an `Io` that performs on the calling thread
(internally an `OnCaller`, which works from any `Async` context). Per kind of op (`NioOnCaller.performHere`):

- **A command** (read, write, accept, connect, send, receive) is tried at once, since a non-blocking channel may be
  used from any thread. If it would block it is tried again after a `Thread.yield`, up to `gears.io.nio.retries` times
  (8 by default). In request-response traffic the reply often arrives within microseconds, and a yield puts the caller
  behind every other virtual thread ready to run, so the retries are fair to the rest of the program. Then it waits on
  the handle's own selector for that direction (`SelectorWait`) and is tried again when woken. A run of 32 commands
  that finish at once yields once (`HereBudget`), so that a stream of them still gives up the thread.
- **A timer** parks the caller until its time (`TimerWait`, `LockSupport.parkNanos`). A timer of no time yields, as on
  a reactor its completion would run a turn later.
- **A blocking op** runs on the caller (`BlockingHere`), under the blocker pool's rules: skipped if cancelled before
  it starts, interrupted if cancelled while running, its result then not written. A time limit is enforced by one
  daemon thread (`gears-io-nio-timeouts`) that interrupts it.
- **An op done at once**, such as a close or a listen, is done.

A time limit runs from the call. It bounds the yields and the wait, the selector's `select` being given what is left,
so an op whose time runs out throws `TimeoutException` having done nothing.

**A close** marks the handle closed, closes the channel and wakes the callers waiting on its selectors, then returns:
each woken caller tries its command again, which throws the JDK's `ClosedChannelException`. So a `perform` waiting on
the handle fails because of the close, and can no longer succeed once it has begun, but may throw after the close's
`perform` has returned, on its own thread: a caller backend has no turn to order the two in. Waiting for the woken
callers inside the close would not order them either, since each throws after leaving its wait, and would make a close
block on other threads. The reactors keep the stronger order of `Ops.close` for an op pending on the reactor the close
runs on.

**Cancellation** sets a flag and calls `Selector.wakeup()` on the selector the caller waits in, or unparks a timer.
Nothing is interrupted, the handle stays open, and a later read gets the data. Each wait is a `Cancellable` linked to
the caller's group (`cancellationScope`), so cancelling the future ends it.

**The handle's own selectors.** A `Selectable` (`NioHandles.scala`) makes a selector for reading and one for writing
the first time an op waits in that direction (`enterWait`), and gives them back once the handle is closed and no op
waits (`exitWait`). They come from `SelectorPool`, which keeps up to 256 idle ones, since opening a selector on macOS
makes a kqueue and a wake-up pipe, three descriptors, and closing it closes them. A `selectNow` before a selector is
reused drops the closed channel's key, which also lets the JDK release that channel's descriptor, so the peer sees
the close at once. On a virtual thread, `select` makes one non-blocking `kevent`, parks on the JDK's own poller only if
nothing is ready, and makes one more on waking: no thread of the backend's own, and no pinning.

So a connection that waits in both directions holds its socket and two selectors: seven descriptors on macOS, where a
selector is three, and five on Linux, where it is two (an epoll and an eventfd), as counted by opening 100 selectors
on JDK 27 and counting the process's descriptors; most connections only ever wait to read, and hold four and three. A
reactor backend holds one per connection. The pool bounds the idle selectors, not those in use, so a server with
10,000 idle connections needs about 40,000 descriptors on macOS.

## `NioBackend` on the reactor pool

`NioBackend` is a `ReactorBackend`. A `NioReactor` drives one `Selector` on the thread that runs it: a command is tried
when submitted and otherwise waits on its channel's key, whose interest ops follow the commands pending on it and are
brought up to date once per turn, just before the select, so a read finishing and the next one submitted from its
completion change nothing. Ready keys are collected and dispatched after the select returns, so completions never run
inside the selector. Timers and ops with a time limit are a heap on the loop thread (`TimerHeap`) that bounds how long a
select blocks, and `wakeup` writes to the selector only when the loop is blocked in it or about to be. Blocking ops and
`resolve` run on the shared blocker pool (`Blockers`), of platform threads: virtual ones would inherit the scheduler of
the virtual thread starting them, so under the pool a blocking op took one of its workers, and a reactor closing on the
only worker waited for an interrupted sleep that needed that worker to wake.

The program's futures are virtual threads whose scheduler is the `ReactorPool` (`VirtualCarriers`,
`VirtualReactorScheduler`, `CarriedPool`): the workers carry them and drive the reactors, as on Scala Native, so an op
goes to the reactor of the worker carrying its future, may finish there at once, and its completion resumes the future
on that worker under the pool's `TakePolicy`. The JDK keeps a virtual thread's scheduler and its carrier to itself, so
this reaches `ThreadBuilders.VirtualThreadBuilder(Executor)` and `Thread.currentCarrierThread` by reflection, which
needs `--add-opens java.base/java.lang=ALL-UNNAMED`. `Io.run` checks before any thread starts
(`VirtualCarriers.check`) and says what is missing. `-Dgears.io.nio.backend=reactors` selects it in the demos and the
bench.

**The pin, against JEP 491.** From JDK 24 a virtual thread that blocks on a contended monitor is unmounted, and the
JDK's `VirtualThread-unblocker` thread later resubmits its continuation to the thread's scheduler, which is the pool.
Coming from a thread that is no worker, it goes to the pool's shared queue, and any free worker takes it: the future
has changed carrier without parking. Submitting an op assumes it cannot: it reads the current worker and submits
straight into that worker's reactor, which is not thread-safe, and a reactor parked in `select` got a submission from a
foreign thread with no wake-up. It showed as hangs of `ReactorCarriersOnFourSuite` in 4 of 10 runs, never on one
worker, which takes its own resubmitted continuation back. The fix holds the virtual thread on its carrier
(`jdk.internal.vm.Continuation.pin`, as the JDK itself does when submitting from a virtual thread) from reading the
worker to the end of the submit, and releases it before the wait parks, which pinned would park the worker's thread;
the waiter's state is an `AtomicReference`, since its listener may be registered from another worker than the one
completing the op (`Pools.scala`). This needs `--add-opens java.base/jdk.internal.vm=ALL-UNNAMED` as well. Pinned, the
suites passed 21 runs of 21; ping-pong cost the same (9.81 against 9.94 µs) and `concurrent` 10% more (2.12 against
2.36). Scala Native's pool is unaffected, since nothing moves a continuation there; the atomic waiter is shared with it,
measured within noise of a plain field.

## The two against each other, and against the JDK and cats-effect

The current design, NIO over Unix sockets, median of 3 (the `no-reactor` comparison; starvation in ms):

| scenario | `NioOnCaller` | `NioBackend` on the pool |
| --- | --- | --- |
| pingpong | 5.46 | 9.37 |
| pingpong-deadline | 5.36 | 9.35 |
| concurrent | 6.43 | 1.94 |
| concurrent-offload | 9.84 | 6.05 |
| connections | 90.9 | 44.6 |
| pipelined | 1.52 | 0.45 |
| fairness (tick) | 2.11 (1.69) | 1.18 (2.35) |
| mixed (compute) | 6.39 (24.6) | 16.6 (33.2) |
| starvation p99 / max, ms | 1.32 / 2.08 | 0.33 / 1.02 |
| pingpong over TCP | 24.6 | |
| concurrent over TCP | 10.22 | |

`NioOnCaller` wins wherever one connection's latency counts (ping-pong, `mixed`, where a ping-pong runs beside a
computation), since the reply is usually caught by a retry on the caller's own thread with no thread woken.
`NioBackend` wins with many connections and on streams: one `select` serves them all, from one thread, where on
`NioOnCaller` every waiting future makes its own system calls. On macOS those contend for the per-process descriptor
lock (see [kqueue.md](kqueue.md)); Linux has no such lock, and is where the gap is still to be measured.

Against the JDK's blocking channels and cats-effect, rerun on the branch as proposed, over Unix sockets (median of 5;
starvation's lateness in µs; [`bench/results/2026-10-28-jvm-vs-jdk-cats-effect.json`](https://github.com/bishabosha/gears/blob/8ee048a2/bench/results/2026-10-28-jvm-vs-jdk-cats-effect.json)):

| scenario | `NioOnCaller` | `NioBackend` | JDK blocking channels | cats-effect |
| --- | --- | --- | --- | --- |
| pingpong | 6.82 | 7.18 | 8.85 | 72.2 |
| pingpong-deadline | 6.89 | 7.29 | 11.74 | 75.2 |
| concurrent | 8.25 | 2.04 | 11.56 | 15.4 |
| connections | 127 | 84.0 | 39.4 | 492 |
| pipelined | 2.26 | 0.52 | 0.97 | 26.3 |
| blocking-io | 7.12 | 15.0 | 14.7 | 76.1 |
| timers | 1.19 | 1.69 | 0.29 | 0.63 |
| fairness (tick) | 2.44 (1.21) | 1.36 (2.71) | 1.85 (9.84) | 22.8 (0.20) |
| mixed (compute) | 7.76 (39.0) | 17.3 (34.6) | 10.6 (29.9) | 2323 (19.0) |
| starvation p50 / p99 / max | 200 / 1094 / 2288 | 79 / 318 / 935 | 197 / 409 / 761 | 1297 / 1385 / 4082 |

`NioOnCaller`'s `mixed` compute is its yield's trip through the timer ("How `NioOnCaller` yields", below). The
earlier comparison, with TCP too (measured before the hand-off ladder below was cut, which left latency and `mixed`
unchanged and cost `concurrent` a fifth; `jvm-nio-jdk-tuned*.json`):

| scenario | `NioOnCaller` | `NioBackend` | JDK blocking channels | cats-effect, TCP | `NioOnCaller`, TCP | `NioBackend`, TCP |
| --- | --- | --- | --- | --- | --- | --- |
| pingpong | 5.9 | 9.9 | 10.6 | 25.0 | 25.7 | 19.4 |
| concurrent | 5.8 | 2.1 | 10.7 | 6.8 | 7.1 | 4.4 |
| pipelined | 1.30 | 0.43 | 1.07 | 1.40 | 2.19 | 0.75 |
| blocking-io | 5.9 | 16.5 | 18.3 | 25.6 | 26.5 | 31.1 |
| mixed (compute) | 8.5 (24.8) | 16.6 (33.3) | 11.1 (21.8) | 997 (16.3) | 29.1 (25.9) | 25.7 (43.5) |

- **The JDK's blocking channels** are behind `NioOnCaller` on one connection and behind `NioBackend` with many
  connections and on streams, ahead of both only at `connections` and of `NioBackend` at `mixed`; and they cannot keep
  a handle open on cancel in any case.
- **cats-effect over Unix sockets** is not on its selector: fs2 3.14 does JVM Unix sockets as blocking calls on its
  blocking pool, 3 to 25 times slower than NIO (ping-pong 52.2 µs, `jvm-vs-cats-effect.json`), which is fs2's Unix
  sockets and not cats-effect's runtime. Over TCP, on its selector, it is level with `NioOnCaller` on one connection,
  between the two with many, and its `mixed` starves the ping-pong behind a compute fiber that only `cede`s (about 1 ms
  a round trip). Its `blocking` runs on the calling thread and is not comparable.
- **Over TCP** the loopback round trip is about 20 µs, so the retries rarely catch the reply and every read pays a wait
  on its own selector, which costs more than the pool's one `select`.

**Starvation and lateness.** The `starvation` scenario, after cats-effect's `CpuStarvationCheck`, runs `concurrent`'s
round trips while a compute task per core works in slices, yielding between them, and a checker sleeps 1 ms over and
over, recording how late it wakes (the sleep's own overhead is about 0.3 ms unloaded). A thread of the backend's own is
what the OS keeps from a core when the carriers saturate them: with ops crossing a platform reactor thread,
`NioOnCaller`'s predecessor had a p99 of 5.85 ms and a maximum of 10.96 ms; with every op and timer on the caller, 1.32
and 2.08 (table above). The JDK's blocking channels, over runs three times longer, reached 2.26 ms p99 and 11.3 ms at
most (`jvm-timers-on-caller-starvation.json`). The pool, whose workers are the only threads, keeps the best tail.

**How `NioOnCaller` yields.** Its `yield` parks for a nanosecond (`Runner.yieldHere`): the virtual thread unmounts,
and the JDK scheduler's timer wakes it from outside the carriers' pool, so it waits behind the threads woken before
it, those whose handles became ready among them. It was gears' own `yield`, which resumes the caller through a task
spawned on the scheduler. Spawned from a carrier, the task and the caller's resumption are both queued on that
carrier's own queue, which a pool empties before it takes work from outside, where the poller's and the timer's
wake-ups come from. With a compute future per core yielding between slices, the wake-ups waited as long as there was
computing: `starvation`'s p50 was 520 µs, and once, in the fifth of five runs, its round trips never ran again in
600 s, the dump showing every reader woken and waiting for a carrier. `Thread.yield` is worse: on JDK 27 a virtual
thread that yields gets its carrier back while the carrier has nothing queued locally, and 20 of 21 runs of
`starvation` went past 30 s, their warm-up's p99 31 ms where it finished, as `JdkBench.yieldTurn` had recorded. Parking, `starvation`'s p50 fell to 209 µs and its p99
from 1.45 to 1.15 ms, with no run of 40 stalling, round trips unchanged; a yield now costs a trip through the timer,
which made `mixed`'s slice of computation 39 µs, from 26. The reactor pool's `yield` is its own and was not affected.

## The retries

The retry count was measured over Unix sockets and TCP, on macOS and in Linux (Docker), and under `tc netem` delay:

- **TCP on macOS:** 8 retries nearly double one connection's round trip (81 µs against 45 with none), and halve
  `concurrent`'s with 64 connections (8.3 against 19).
- **Linux loopback (Docker):** they help one connection too (17 against 74 µs), since waking a thread there costs tens
  of µs whoever wakes it: the JDK's blocking channels, which wait on its poller, are no faster than the handle's own
  selector (ping-pong 56 against 49).
- **`tc netem`**, every packet delayed by 50 or 500 µs: the count makes no difference (within 7%).
- **Unix sockets on macOS**, the policies of the next section's table: none against 8 is level on ping-pong (6.51
  against 6.65), 8 is far better on `concurrent` (8.21 against 18.90) and `concurrent-offload` (11.02 against 19.07),
  worse on `pipelined` (2.27 against 1.39), where a reader that tries eagerly reads smaller pieces.

8 is the default as the best balance found; 16 or 32 looked better overall, and a sweep is open.

## Why a wait costs what it does

A blocked op costs two OS wake-ups on every JVM path measured. On the handle's own selector, the JDK's poller thread
sees the readiness and unparks the virtual thread, and a carrier must then be scheduled to run it. With a selector
thread, as on the `Selector` floor measured below, the caller hands the registration to that thread, waking it, and is
woken by it in turn. The JDK's blocking channels are the first kind, registering with the poller from the caller's own
thread, which is why the `Selector` floor costs twice theirs on one connection (17.1 against 8.8 µs a round trip,
`jvm-selector-floor.json`). The kqueue pool on Scala Native is cheaper because the worker that sees readiness runs the
continuation itself, and spins briefly before blocking (see [kqueue.md](kqueue.md) and [runtime.md](runtime.md)). The
retries are how `NioOnCaller` avoids both wake-ups when the reply comes within microseconds; on Linux, where a wake-up
costs tens of µs, they pay even for one connection.

Waiting on the handle's own selector in the waiting virtual thread was measured against the alternatives built on
public API, before it went into a backend (Unix sockets; `jvm-selector-here.json`, the last column
`jvm-selector-here-tuned.json`):

| scenario | selector thread (floor) | own selector, no retries | JDK blocking channels | own selector, 32 yields |
| --- | --- | --- | --- | --- |
| pingpong | 16.4 | 6.8 | 10.5 | 4.8 |
| concurrent | 8.5 | 16.6 | 10.8 | 6.6 |
| connections | 34 | 30 | 25 | 22 |
| mixed | 17.6 | 7.2 | 11.1 | 4.6 |

Without retries it was the slowest with many connections. JFR on `concurrent` put the extra time in the two `kevent`s
`select` makes on the handle's selector and in registering that selector's descriptor with the JDK's poller, almost
all of it system time on macOS's descriptor lock. Yielding before the wait removed most of it: 1 to 3 yields made
things worse, and from 16 the scenarios levelled off. Holding the JDK's scheduler to two carriers
(`-Djdk.virtualThreadScheduler.parallelism=2`) helps further on macOS (ping-pong 3.0, `concurrent` 1.45), but that is
the deployer's process-wide setting, not a library's.

## What this needed of gears

Two fixes to gears' virtual-thread support (`VThreadSupport`, and `FactoryVThreadSupport`, its copy with the threads
from the pool's factory) came out of this work. `UnlinkedAwaitListener` resumed a suspension as a fresh task whose
`resume` waited for a result a scheduled boundary never set, so the last resumer of every future parked for ever; the
boundary now sets one, and the listener resumes through `resumeAsync`, which hands the input to the suspended thread
directly (a completion arriving while the listener is being added is still a task, so that `yield` still yields). And
a synchronous `resume` could miss a result that a `resumeAsync` cleared before it looked; results are now counted.

## Tried and dropped

**`VThreadBackend`, a backend with no reactor.** First it made the JDK's blocking call on the caller's virtual thread,
which the JDK parks on its own poller, cancelling by interrupt. An interrupted call closes the channel, so a cancelled
read lost its connection, and it was 6 µs a round trip slower than the JDK alone (16.6 against 10.6). Rebuilt over
non-blocking channels, with the retries, the wait on the handle's own selector and cancellation by `wakeup`, it was the
lowest-latency gears way over Unix sockets (ping-pong 5.5 against the pool's 10.1) with a lower worst-case wake-up than
NIO then had (2.9 against 6.9 ms, one event in three thousand), but 20 to 60% slower on `concurrent` and
`concurrent-offload`, and without a reactor, blocking ops or submission. Everything it pioneered moved into NIO's
caller path (`WaitsHere.scala`), its checks into the shared `FuturesContract`, and it was retired.

**The hand-off ladder, with a `retryBelow` gate.** Before `NioOnCaller`, the default was `NioBackend` with a side door
for gears' own virtual threads: a pool of reactor threads carrying no futures, and an op tried on the caller,
retried, waited for on the handle's own selector while fewer than `retryBelow` ops (32) were waiting, and otherwise
handed to a reactor thread whose completion woke the caller. The first version, which always handed over, paid two
thread hand-offs per op (ping-pong 48.8 µs against 8.7 on the pool, `jvm-backends.json`). The ladder kept one
`select` for many connections, but every completion crossed a platform thread the OS could preempt: cutting it
reduced the `starvation` p99 from 5.85 to 1.32 ms and the maximum from 10.96 to 2.08 ms, for `concurrent` 20% slower
(5.37 to 6.43 µs; 41% over TCP), `connections` 16% and `concurrent-offload` 53%, all macOS's descriptor lock. Whether
some hand-off should come back is a question for Linux measurements.

**The waiters driving one shared selector in turn.** The blend public API allows between no backend thread and one
`select` for all: ops waited on a selector the waiters shared, whichever arrived while none selected becoming the
leader, which selected for all, woke the owners of ready keys and handed the role on. Correct, and slower in every
mode (median of 3; lateness in µs, `starvation` three times as long):

| mode | concurrent | concurrent-offload | pipelined | starvation p99 | max |
| --- | --- | --- | --- | --- | --- |
| off | 5.37 | 6.45 | 1.22 | 2117 | 14086 |
| for the overflow | 13.2 | 16.9 | 1.63 | 4798 | 14194 |
| for all | 14.7 | 17.0 | 1.35 | 3648 | 10808 |

Each wait costs more hops than the hand-off it replaces: a follower arming while the leader selects must wake it, the
leader selects again, and the follower is woken by one hop more; and the leader is woken for every arm, so nothing is
batched. Batching the arms would make the leader a reactor thread again, as a virtual thread, which measured worse.

**Spinning instead of yielding.** Busy spinning before the wait (`Thread.onSpinWait`) gave ping-pong 1.6 µs, but holds
the carrier and starved work handed to other virtual threads (`concurrent-offload` 26 against 10). On the reactor
thread, `selectNow` polls before blocking cut ping-pong (33 to 23 µs at the time) but each poll was a system call on
the descriptor lock (`blocking` from 9.5 to between 18 and 25); waiting on memory for the next op held up whatever the reactor had
just woken (`blocking` up to 86 µs). A yield puts the caller behind everything else ready to run, so nothing spins.

**The reactor on a virtual thread**, so that it would take turns inside the scheduler instead of competing with the
carriers for the cores: the `starvation` p99 rose from 4.8 to 6.4 ms, its own wake-up then queuing behind the futures'.

**Retry policies that adapt.** Measured against fixed counts (Unix sockets, macOS):

| | fixed 8 | fixed 0 | scaled | adaptive | `NioBackend` |
| --- | --- | --- | --- | --- | --- |
| pingpong | 6.65 | 6.51 | 8.34 | 6.48 | 7.20 |
| concurrent | 8.21 | 18.90 | 7.85 | 13.85 | 2.13 |
| concurrent-offload | 11.02 | 19.07 | 13.07 | 18.70 | 7.26 |
| pipelined | 2.27 | 1.39 | 2.58 | 2.18 | 0.49 |
| mixed | 7.20 | 10.34 | 10.06 | 6.99 | 17.01 |

`scaled` retried as many times as other commands were waiting, up to 32: level with 8 under load, worse on one
connection, which then got about one retry. `adaptive` kept a budget per channel and direction, doubled past where a
retry caught the data and halved when none did: best on one connection, worse under load, where misses halved it. Both
were dropped: behaviour that depends on measurements taken while running is harder to reason about and to test, so the
count stays fixed, as kqueue's spin count does.

**The JDK's own poller, directly.** The blocking channels are fast because they register with the JDK's internal
poller (`sun.nio.ch.Poller`) from the caller's own thread. That class is not exported, and using it needs
`--add-exports java.base/sun.nio.ch=ALL-UNNAMED`, which the default backend exists to avoid; the handle's own selector
reaches the same poller through public API, at the cost of a nested kqueue or epoll. Registering with kqueue or epoll
from the caller through the foreign function API would remove the hand-offs as well; it is not built.

## Open

- **A hang of the pipe check on four workers.** Once, in a full run of the JVM suites, `ReactorCarriersOnFourSuite`'s
  "two futures stream bytes through a pipe" never finished: both futures parked waiting for their ops, every reactor
  idle in `select`, one of the two being resumed. It points at a completion or resume lost between workers. Not
  reproduced since.
- **A sweep of fixed retry counts**: 16 or 32 looked better overall than 8.
- **Linux.** macOS's descriptor lock is the price of `NioOnCaller` with many connections and of the retries on
  `concurrent`; Linux has no such lock, but wakes threads more slowly. The comparison of the two backends, and of
  whether any hand-off to a reactor should come back, is to be made there.
- **`NioBackend` on four workers** was once measured slower than on one (ping-pong 8.7 to 23 µs, where kqueue stayed at
  7.1, `jvm-backends-4-workers.json`), before the pin and the pool's later changes, and not looked into since.
- **`connections` on `NioOnCaller`** (90.9 µs against the pool's 44.6; the JDK's blocking channels took 24): opening, registering and
  closing channels per connection, not profiled.
