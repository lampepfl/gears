# The reactor pool: a design note

The runtime in `gears.async.io.internal` (core, `jvm-native/`) runs a program over a `ReactorBackend` on a pool of
workers, each a thread driving a reactor of its own. kqueue's and NIO's reactors run on it, and the Network.framework backend's, which are kqueue's; this
note is about the pool itself, with its measurements taken on kqueue, where it was built. It records the pool's shape
and why, the take policies and how they compare, how tasks and descriptors move between threads, the budgets that keep
the fast path fair, how failures are contained, and what macOS's per-process locks mean for scaling, with the three
designs tried around them and reverted. NIO's use of the pool is in [`jvm.md`](jvm.md), Network.framework's in
[`nwframework.md`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/docs/io/nwframework.md), and kqueue's reactor in [`kqueue.md`](kqueue.md). Unless a table says otherwise,
numbers are microseconds per operation, the median of 5 runs of release-fast Scala Native builds on an M1 Pro running
macOS 27, with client and server each a pool of the size given (see [`bench.md`](bench.md)).

## Internal concurrency primitives

An operation submitted through `Io.perform` is now its own Gears source and cancellation-group member. Both NIO and
kqueue's `LoopOp` extend `ReactorOperation`, a thin specialization of `gears.async.io.internal.AsyncOperation[Unit]`. There is no
attached `OpWaiter`, per-operation callback adapter, or general-purpose promise on this path. The attachment remains
available to low-level callers. `ReactorBackend.Op` requires this contract at compile time.

`AsyncOperation` is a reusable, single-consumer source. The consumer claims a generation before submission, preventing
overlapping uses, and releases it after completion. Cancellation dispatches to a `gears.async.io.internal.SerialExecutor`, whose
tasks cannot be stolen. The worker implements that executor with its existing inbox. Cancellation only requests a
stop: the producer completes the source after it has released borrowed buffers, and the consumer waits outside its
cancellation group until that acknowledgement. A queued cancel checks the generation before touching the poller, so
it cannot cancel a later use on another worker. Completion and listener registration use one atomic slot, with no
listener collection. A reusable source is deliberately not a Future, whose result must remain stable forever.

Groups also capture the operation's generation and owner while holding their membership lock, before unlink can
release that use. This closes the earlier race where a group saved the operation itself, then called `cancel` after
it had been reused elsewhere. The internal `CancellationSnapshot` contract adds no allocation to submission or
successful completion: a request is allocated only when cancellation starts, and doubles as the owner's inbox task.
It captures fields only, with no callbacks or member locks held under the group lock.

Cross-thread earlier-deadline updates coalesce while a notification is queued for that timer and poller. Repeated
updates only publish the latest deadline and read a volatile marker; they allocate no queue nodes and make no wakeup
calls. The poller clears its marker before reading the deadline. A concurrent move is therefore observed by that
refile or queues another notification. An old poller cannot clear a newer owner's marker.
A queued deadline update checks that the timer still belongs to its poller before reading its heap index. An update
queued before cancellation must not remove an entry from the old heap after the timer has moved to another poller.
Regression tests cover group snapshots delayed across reuse on the same and another worker, cancellation by the new
group, joining an already-cancelled group, and stale timer updates with both empty and occupied old heaps. The timer
tests are in the shared reactor contract, and pass on both NIO and kqueue.

`gears.async.io.internal.CooperativeBudget` shares the immediate-completion policy. Every drain has 128 credits, and a task can use
one while peers are queued; lone tasks share a separate 32-credit burst allowance per drain. Every immediate completion, including a failed
attempt, consumes a drain credit. Starting another task does not replenish those credits. Once exhausted, submission
still tries the system call, but queues completion so the caller suspends and the worker gets another poll turn.
Poller-resumed tasks are also capped at 256 per drain, so a large completion burst still permits intervening polls.
NIO and kqueue share this attempt/queue/error path in `ReactorCore.attemptNow`; kqueue retains its edge-readiness and
available-byte tracking. The budget check is inlined, so its lazy peer check does not allocate a closure.

The scheduler still has one poller per worker. Ordinary Gears futures and their continuations run on those workers;
under the default affine policy, poller completions and cancellation acknowledgements resume on their owning worker.
`gears.async.io.internal.RunQueue` keeps the existing stealing algorithm inside the I/O runtime. Its owner-local backlog transfer
only removes a task after successfully enqueueing it, including when a hybrid-policy queue fills mid-transfer.

Gears' default `AsyncOperations.yield` now uses the same singleton source as the reactor runtime did: it forces a
scheduling boundary without creating a passive Future and resolver. `NioOnCaller` retains its platform-specific
virtual-thread yield, which also gives JDK I/O wakeups a turn.

`RuntimeCostSuite` provides a reproducible JVM diagnostic for immediate EOF reads, suspended timers and group
cancellation. It prints medians after warmup and has no machine-dependent timing assertions. Functional contracts
additionally exercise continuous immediate readiness alongside a sibling timer and cancellation of a blocker whose
cleanup finishes later. Local benchmark output belongs under the ignored `bench/results/` directory.

### Composable backend primitives

Shared primitives should own one policy or lifecycle rather than require a backend to inherit a combined state
machine. `AsyncOperation` owns consumer completion and acknowledged cancellation; `BlockingRun` owns execution and
interruption of blocking work; `FreeListPool` owns reusable storage. Readiness registration stays in each reactor.

`PooledBufferRead` composes a pool, an output slot and a backend read function. It owns the buffer only for that
synchronous attempt: would-block and exceptions return it, while success or EOF transfers a flipped buffer to the
consumer. Its `finally` also returns a buffer if publication fails. It knows nothing of descriptors, selector keys,
edge readiness, timers, or scheduling. NIO and kqueue use the same ownership algorithm, and kqueue derives its
readiness hint from the returned byte count rather than retaining a separate filled-buffer flag.

### Native verification through Metals MCP

Metals' MCP runner delegates non-JVM suites to BSP `buildTarget/test`, so sbt links and executes the Native test
binary with its own adapter. Native compiler-plugin options identify the platform even when sbt's target metadata
incorrectly advertises JVM. No project-level JVM bridge or Native test-adapter dependency is required.

Select the Native module explicitly for shared sources. For example, invoke MCP `test` with:

```json
{
  "testClass": "gears.async.io.internal.AsyncOperationReuseSuite",
  "testFile": "/Users/jamie/workspace/gears/jvm-native/src/test/scala/async/io/internal/AsyncOperationReuseSuite.scala",
  "module": "rootNative-test",
  "verbose": true
}
```

Use `ioKqueue-test` for the kqueue contract and future suites. Sbt's BSP test endpoint supports suite selection;
individual `testName` selection is explicitly rejected for non-JVM suites. JVM test selection uses the existing
runner, with an exact-suite BSP fallback if presentation-compiler discovery is unavailable. Test summaries are collected from BSP reports, or sbt's standard summary when no report is emitted.

The updated Metals runtime was verified against all 15 selected Native suites directly through MCP: 73 core tests
and 201 kqueue tests passed, with one existing ignore. The focused JVM run passed 260 tests. These cover cancellation
snapshots across operation reuse, stale deadline notifications, bounded immediate readiness, and group identity.
Native execution also exposed test-only deadlocks: MUnit's by-name `assert` holds a shared clue monitor. Awaited
continuations and latch waits now return into local values before assertions, so they cannot hold that monitor while
waiting for another test thread. The temporary `NativeRegressionSuites` bridge and its
build dependency were removed after direct execution was verified. Older Metals releases with the JVM-only MCP test
launcher need the updated runner before using these requests.

Native release benchmarking also exposed a group-membership bug: `ReadIntoBuffer` is a case class whose hash changes
with its mutable buffer. A read placed in the group's spill set could complete but fail to unlink, leaving scope
shutdown waiting forever. Group membership now uses identity in both the single slot and the lazy spill map.
`CompletionGroupIdentitySuite` covers mutable hashes, equal distinct members and duplicate registration; the shared
future contract also exercises a pending buffer read alongside a child future and checks that its scope completes.
Three identity tests failed before the fix; the five current identity and vacancy checks pass.

### Bounded fairness and deadline notifications

The fairness policy bounds every drain to 128 immediate completions in total, gives each task one immediate
completion with peers queued, and limits lone-task bursts to 32 additional completions per drain. This retains
frequent poll opportunities without timing-dependent policy decisions. The notification marker coalesces repeated
earlier-deadline updates while preserving cancellation and cross-poller reuse checks.

`ReactorOperation` does not require readiness, a descriptor, or a syscall attempt. A completion-only backend may
always return false from `trySubmit`, then deliver completion on its poller. The synthetic `CompletionBackendSuite`
checks external completion, same-worker resumption, and delayed cancellation acknowledgement without a selector.
No io_uring implementation is included.

Single-member `CompletionGroup`s avoid allocating the identity map until a second distinct member is added. The
cancellation flag is volatile for the fast-path check from other workers. A cancelled Future retains the producer's
`CancellationException`, including a stackless operation acknowledgement, instead of capturing a replacement stack.

## The pool

`ReactorPool` is the gears scheduler that futures see; `ReactorPool.run` starts it, and the thread that calls it is
worker 0. Each `ReactorWorker` is a thread (`ReactorWorkerThread`) with:

- its reactor, which only its own thread may call;
- `local`, a `RunQueue` that other workers may take from;
- `home`, an owner-only `ArrayDeque` for resumes from its own reactor, under the affine and hybrid policies;
- an inbox, for tasks that must run on this worker's thread (`ReactorWorker.sync`).

Worker threads hold their worker, so finding the worker running the caller costs `Thread.currentThread()` and a type
test: a `ThreadLocal`, a map lookup on Scala Native, had cost 5–10% in `fairness` and `timers`. Tasks from outside the
pool go to a shared queue. A worker out of work takes from the shared queue, then half of another's `local`, never its
last task; an idle worker is woken only when a queue holds more than one task and no worker is already searching. A
worker that runs completions in the turn it blocked in skips itself when offering the work they queued: it had been
waking itself, a wasted `kevent` per resume that also used up the wake-up meant for another worker. One worker costs
nothing against the single-threaded scheduler the pool replaced (within 2%, [`bench/results/pool-one-worker.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/pool-one-worker.json)).

A worker's round, `drain`, runs the inbox first, at most `InboxPerDrain` (256) tasks, as though from the reactor's turn,
then `home`, then what `local` held when the drain began; tasks queued meanwhile wait for the next round. Running only
what was queued at the start is what keeps a computation that yields between slices from keeping the loop from ever
polling: before that bound, the `mixed` scenario never finished.

### `RunQueue`

`gears.async.io.internal.RunQueue` follows Tokio's local queue: a fixed ring of 256 tasks, first in first out for its owner, from which another
thread claims about half with one compare-and-swap on the head, which packs two indices so that the owner can go on
taking tasks while the other thread copies. A task that finds it full goes to the shared queue. `RunQueueCheck`
(`RunQueueSuite`, JVM and Native) has an owner pushing and taking while three other threads take from it, every task run
exactly once and seeing what the owner wrote before queueing it: 5 million tasks, ten release-fast runs, no failures.
With one worker it costs `pipelined`, the scenario that queues the most tasks per message, 0.64 → 0.68 (+6.3% over 7
runs), since each pop is a compare-and-swap and each push a volatile write, where an `ArrayDeque` had neither; the rest
within noise ([`bench/results/run-queue.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/run-queue.json)). Tokio pays the same. Claiming a drain's tasks with one compare-and-swap
would save most of it, but keep other workers from them for the whole drain.

### The inbox

Some tasks must run on one worker's thread because they call its reactor: cancelling an op pending there
(`AsyncOperation.cancel`), or arming or cancelling a timer there. `ReactorWorker.sync` queues them in that worker's inbox,
from another thread. They used to join `local`, behind the tasks already there, from whose head other workers take, so
one could be taken and run on the wrong thread: `InboxCheck` reproduces it every time (two workers; another thread
queues a task for the busy worker, which then yields and spawns futures behind it while the other is idle), and before
the fix all 500 rounds ran the task on the wrong thread. Run from the inbox, as though from the reactor's turn, a task
one of them resumes also stays under the affine and hybrid policies. Within noise of before
([`bench/results/inbox-on-owner.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/inbox-on-owner.json)).

## Tasks moving between threads

A task's next op goes to the reactor of whichever worker runs it, rather than a reactor it is pinned to, so a moved
task's descriptors follow it. `MigrateCheck` (`ReactorPoolSuite`, in `io-backends/kqueue`) hands every resume to the
other of two threads and checks, after each: the locals of the frames the resume restored, an exception thrown after
the move and caught in one of those frames, one that ends the future, a `@blocking` call and an allocation, and the new
thread's own alternate signal stack and mask. No failures in 4 million moved resumes, release-fast and debug. The one
thing it found was Scala Native's GC blocking signals process-wide, which happens whether tasks move or not (see
[`scala-native.md`](scala-native.md)).

### Descriptors shared between kqueue reactors

`DescriptorTable` (core, `native/`), process-wide like the descriptors, keeps one `Long` per descriptor: a generation
that every close bumps, on any thread, and the id of the reactor that last registered a read filter for it. Registering
a read filter claims the descriptor. A reactor brings a descriptor's slot up to date when it submits: it starts over if
the generation changed or another reactor has claimed it since. A read event with no read pending, for a descriptor
another reactor now owns, makes the reactor delete its filter, one `kevent` per moved descriptor, and forget the slot.
Write filters need none of this: they are one-shot, and an op cannot move while pending. A descriptor taken over starts
out not ready, so a read registers its filter, claiming it, before trying anything, since kqueue reports data already
waiting when a filter is added; a reused number starts out unknown, as a new descriptor does. `SharedDescriptorCheck`
checks it on two reactor threads sharing a pipe: the old reactor must not wake for data the new one owns, and a read of
a reused number must complete.

**What a move costs.** `move-bench` (`GearsMoveBench`) ping-pongs against an echo server on its own reactor thread from
two client reactors: `stay` on one; `hand-off`, taking turns every N round trips with a connection each, so work
changes thread but no descriptor moves; and `move`, taking turns on one shared connection. Median of 5 runs of 100,000
round trips, µs per round trip ([`bench/results/move-descriptors.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/move-descriptors.json)):

| every | stay | hand-off | move |
| --- | --- | --- | --- |
| 1 | 6.32 | 9.18 | 10.17 |
| 4 | | 7.65 | 7.87 |
| 16 | | 6.83 | 7.06 |
| 64 | | 6.37 | 6.48 |

Handing work to another thread costs about 2.9 µs, mostly the cross-thread wake-up and the spinning and blocking around
it (system calls per round trip at every 1: `stay` 8.6, `hand-off` 19.1, `move` 19.9). Moving the descriptor with it
costs about 0.8 µs more once a taken-over descriptor starts out not ready ([`bench/results/taken-over-not-ready.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/taken-over-not-ready.json)).
The wake-up, not the descriptor, is the expensive part of moving work. Not tried: deleting the old filter eagerly, by
message to the old reactor, and `EV_DISPATCH` filters, so that a moved descriptor leaves nothing that can wake anyone.

## Take policies

`TakePolicy` (`gears.async.io.internal`, chosen with `ReactorRuns.run`) says which of a worker's tasks others may take:

- `Free`: any queued task. Resumes join `local` in order with everything else.
- `Affine`, the default: only tasks that are not resumes from the worker's own reactor, such as new futures and
  yields, so a task doing I/O stays on the worker whose reactor has its descriptors. Resumes go to `home`.
- `Hybrid`: as `Affine`, except that a worker with a backlog of resumes shares half of it.

A worker whose one task in `local` waits behind resumes in `home` offers it (`hasSpare`), through a volatile flag set
when that happens and cleared once a drain has run the resumes; the owner keeps its own copy, so the usual case neither
reads nor writes it. Publishing a resume count after every turn instead cost `connections` 7–9% and `pingpong` 4%. With
it, `mixed`'s computation moves to an idle worker under affine and hybrid. µs/op, workers-policy, `ce` cats-effect with
as many threads ([`bench/results/pool-policies-spare.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/pool-policies-spare.json)):

| scenario | 1-affine | ce-1 | 2-free | 2-affine | 2-hybrid | ce-2 | 4-free | 4-affine | 4-hybrid | ce-4 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| pingpong | 7.58 | 22.8 | 7.64 | 7.66 | 7.60 | 23.1 | 7.73 | 7.89 | 7.63 | 24.0 |
| concurrent | 2.16 | 10.0 | 2.76 | 2.47 | 2.52 | 7.49 | 5.48 | 4.04 | 3.85 | 9.80 |
| connections | 24.0 | 157 | 42.9 | 24.5 | 22.9 | 121 | 42.9 | 22.5 | 24.6 | 118 |
| pipelined | 0.71 | 2.27 | 0.73 | 0.66 | 0.72 | 2.96 | 0.87 | 0.71 | 0.68 | 2.30 |
| blocking-io | 15.1 | 24.5 | 14.8 | 14.4 | 14.4 | 28.1 | 14.5 | 14.6 | 14.3 | 24.6 |
| timers | 0.16 | 1.03 | 0.16 | 0.16 | 0.16 | 0.86 | 0.16 | 0.16 | 0.16 | 0.98 |
| fairness (tick) | 1.81 (3.62) | 2.27 (4.22) | 0.74 (11.3) | 1.74 (3.62) | 1.82 (3.64) | 2.52 (0.50) | 0.89 (14.9) | 1.87 (3.75) | 1.83 (3.67) | 3.04 (0.47) |
| mixed (compute) | 23.8 (23.8) | 1187 (19.5) | 8.49 (35.1) | 8.26 (36.0) | 8.52 (35.1) | 21.1 (21.1) | 8.98 (34.5) | 8.49 (35.6) | 8.40 (34.4) | 109 (21.2) |

- **Free spreads I/O as well as computation**, so it moves tasks and descriptors constantly (on 2 workers about half of
  `concurrent`'s round trips involve a task taken), and its `connections` is 42.9 on any number of workers. The reason is
  how it queues, not that it moves: a resume joins `local`, counted when the drain starts, so the echo future the
  server's accept loop spawns on resuming waits for the next drain, after another turn of the reactor; under affine the
  resume is in `home`, which the drain runs before it counts `local`, so the spawned future runs in the same drain. On
  one worker, affine took `connections` 40.5 → 21.3 and `mixed` 26.9 → 22.8, every other scenario within 2%
  ([`bench/results/affine-default.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/affine-default.json)). Affine is as fast or faster everywhere measured but `fairness`'s traffic, which
  free spreads, with a slow ticker; so it is the default.
- **`fairness` does not spread under affine.** A version that also offered the task queued by a drain's last resume
  spread it (traffic 1.85 → 0.72), but the ticker, alone on a worker, then ticked in 14 µs instead of 3.7: the slow polls
  of a lone worker (see below).
- **`concurrent` is slower on more workers under every policy**, though its 64 futures do spread under affine (the
  client's workers take 43 tasks on 2 workers, 152 on 4). Placing new futures round-robin on the workers as they are
  spawned gained nothing (2.31 → 2.45 on 2 workers, 3.73 → 3.84 on 4) and was dropped. The cause is the kernel; see
  macOS's per-process locks, below.
- **Against cats-effect**, gears is 3–6 times faster on `pingpong`, `connections`, `pipelined` and `timers`, and faster
  on `blocking-io`. cats-effect keeps `fairness` ticks at 0.5 µs on 2 and 4 threads, its ticker on a thread of its own;
  with one thread its ping-pong is starved by the computation (1187 µs).

## Fairness and the fast path

An op that finishes at once lets `perform` return without suspending: no await, no continuation switch, no round trip
through the scheduler (`Reactor.trySubmit`, which finishes such an op inside the call without calling its completion).
`CooperativeBudget`, owned by `ReactorWorker`, enforces a 128-completion limit across each drain and a
one-completion limit per task when peers are queued. Lone tasks share 32 extra credits per drain. All immediate
attempts consume drain credits, including failures and the per-task allowance, so a sequence of tasks cannot reset
its way around the next poll. The shared limit bounds the time until the next poll, while the per-task allowance
keeps queued peers moving. Home-queue resumes have a separate 256-task cap.

The measurements below describe the earlier policy that motivated these two limits; its 32-credit counter applied
only while idle, unlike the current shared drain counter:

- **While the worker has nothing else queued** (`isIdle`), at most `FastPathBudget` (32) times per round, so the reactor
  still polls for futures waiting on events. `pipelined` 2.23 → 0.67 (−70%), ping-pong −2 to −3%
  ([`bench/results/fast-path.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/fast-path.json)). A budget alone, without the idle condition, gave the fast op's future a budget's
  worth of ops per turn of everyone else: budgets of 1, 4, 8 and 32 gave `pipelined` −29%, −46%, −66%, −72% and
  `fairness-tick` +37%, +87%, +122%, +375% ([`bench/results/fast-path-budgets.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/fast-path-budgets.json), 3 runs). Requiring an idle worker
  removed the cost to fairness and kept the gain, which comes from futures that run alone.
- **Once per turn of the caller** (`TurnFastPathBudget`, 1, reset before each task), even with other tasks queued. A
  future whose op has just completed usually issues the next at once, so most round trips take one turn instead of two:
  `concurrent` 3.49 → 2.16 (−38%), `fairness` traffic −40%, `pingpong` −11%. The futures beside a busy one get the same
  share of turns, but each turn now does more: `fairness-tick` +20% and `mixed-compute` +39%
  ([`bench/results/turn-fast-path.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/turn-fast-path.json)). It is what Tokio's cooperative budget does per task poll.

Letting writes always take the fast path, with other tasks queued, was tried: `concurrent` 3.74 → 2.95, but
`fairness-tick` 3.41 → 17.59 (+416%) and `mixed-compute` 26.7 → 37.4 ([`bench/results/fast-path-writes.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/fast-path-writes.json)). An op that
finishes without suspending gives the turn to no one, so the futures beside it fall behind; that is why the per-turn
budget is 1.

**Yield.** `OnPool`'s `yield` suspends the caller and queues its resume behind the tasks already queued. Before it,
`mixed` yielded by awaiting each slice as a future of its own, two turns per slice; with a yield, one. That changed the
share between ping-pong and computation rather than the total (`mixed-compute` 37.0 → 28.1, ping-pong 18.5 → 28.1;
[`bench/results/yield-now.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/yield-now.json)).

**Responsiveness**: see `starvation` in [`kqueue.md`](kqueue.md): a 1 ms sleep is 17 µs late at the median on 4 workers,
262 at worst, beside a compute future per core.

### Spinning before blocking

Before blocking, a kqueue reactor polls without blocking up to `spinPolls` times (`KqueueReactor.DefaultSpinPolls`, 4;
0 blocks at once). In request/response traffic the reply often arrives microseconds after the loop runs out of work,
and sleeping then costs a thread wake-up per round trip. It cannot delay queued work: the loop only blocks, and so only
spins, when the worker has no task and the turn delivered nothing; in `mixed` the compute slices got faster with it
(34.4 → 28.8, against 0 polls). The trial that keeps 4, on the affine pool, load average 4–6, spin count then workers
([`bench/results/spin-polls-quiet.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/spin-polls-quiet.json)):

| scenario | 4-1 | 0-1 | 1-1 | 2-1 | 8-1 | 4-4 | 0-4 | 1-4 | 2-4 | 8-4 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| pingpong | 6.98 | 7.13 | 6.95 | 6.99 | 6.93 | 6.99 | 7.48 | 7.04 | 7.25 | 7.01 |
| concurrent | 2.00 | 1.88 | 1.88 | 1.86 | 2.00 | 3.58 | 3.65 | 3.77 | 3.72 | 3.69 |
| connections | 21.1 | 27.8 | 21.4 | 21.5 | 20.9 | 21.2 | 30.3 | 21.3 | 21.9 | 21.4 |
| pipelined | 0.67 | 0.63 | 0.67 | 0.67 | 0.68 | 0.64 | 0.67 | 0.67 | 0.68 | 0.68 |
| mixed (compute) | 22.8 (22.8) | 24.0 (24.0) | 24.3 (24.3) | 22.8 (22.8) | 22.9 (22.9) | 7.90 (35.6) | 8.10 (45.3) | 7.99 (34.2) | 7.94 (34.7) | 8.10 (35.7) |

Blocking at once is worse (`connections` 31–43% slower); 1 to 8 polls are within a few percent of one another, and idle
workers spinning do not measurably slow busy ones on 4 workers.

**A lone worker's polls are slow.** The `fairness` ticker, alone on a worker, took 60–100 µs a tick: that worker's spin
polls each took 10–20 µs, against about 1 µs on busy workers. GC, thread priority, oversubscription and stale filters
were each ruled out; why the kernel call is slower there is not established. A turn with a timer already due skips the
spin polls, which took ticks on 2 and 4 workers from 72 to 20 and 103 to 31 µs (debug).

## What gears does per suspension

With every op suspending (`concurrent`, 64 futures on a thread), three changes to gears core cut its own cost.

`perform` awaits in the `Unlinked` group, where nothing can cancel the wait, through `UnlinkedAwaitListener`, which has
no lock and no group link and is itself the task that resumes the suspension (`concurrent` −10.5%); the waiter's state
is a plain field, every access being on the reactor's thread, where Scala Native's `AtomicReference` computes a
`ClassTag` per operation; and a completion group keeps its lone member in a field (`CompletionGroup.single`). Together
`concurrent` 3.69 → 3.20 (−13%), `fairness` −15% ([`bench/results/gears-climb.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/gears-climb.json)). What is left per suspension is
mostly Scala Native's: about 8 allocations in `Continuations.suspend` against about 3 in gears.

## Failures

**A completion that throws is reported, and the turn goes on.** A throw from a completion in the middle of a turn used
to skip the rest of the turn, losing the poll's other events, and an edge-triggered or one-shot filter's event is not
reported again, so the ops waiting on them hung. Every call of a completion, in all three backends, goes through
`Reactor.deliverComplete`, `deliverFailure` or `deliverCancel`, which catch what it throws and report it to the
reactor's `reportFailure` (settable; by default the stack trace to stderr) as a `CompletionFailed(op, cause)`. It is
never delivered again as the op's failure: the op had its outcome, and the throw is the completion's bug. This is what
Netty, Vert.x and Scala's `ExecutionContext.reportFailure` do; Asio and the JDK's `AsynchronousChannelGroup` keep the
other completions in a queue instead. A completion that handles none of a failure, so that the default `onFailure`
threw it, is reported the same way. Programs are unaffected: the runtime's completion only resumes a task, and a
program's code throws inside its future. `ReactorContract` checks it on every backend.

**A worker survives a turn that throws.** `ReactorWorker.turn` reports the error and goes on, as `runTask` does for a
task; before, the throw ended the worker's thread and stranded its tasks. A reactor that throws `BrokenTurns` (16)
turns in a row is taken as broken: the worker fails the pool (`ReactorPool.fail`), which stops it, and `runFirstWorker`
throws the error, so the run fails rather than spinning or hanging. `WorkerFailureSuite` (JVM and Native) checks both.

**A cancelled op's failure has no stack trace.** `timers` had gone from 0.15–0.23 µs to 14.7 on kqueue: since
submissions became members of their group, a cancelled one completed with a new `CancellationException`, and the
scenario cancels half its timers; 95% of the time was in `libunwind` capturing a trace of the reactor's internals.
`Pools` fails a cancelled op with a `CancellationException` that captures none.

## macOS's per-process locks

`concurrent` on 1, 2 and 4 workers spends 0.70, 1.47 and 7.56 s in the kernel for the same 400,000 round trips,
whatever the spin count; sampled, its threads are mostly in `read` and `write`, then `kevent`. Four one-worker benches as
threads of one process run at 15.5 µs a round trip, as four processes at 3.4–3.9: one process tops out at about 260,000
round trips a second however its threads share the work, below one worker alone (470,000), and separate processes reach
about 1.1 million. Plain C shows the same, so it is neither gears nor Scala Native. `bench/descriptor-contention.c`,
each thread or process on descriptors of its own, µs per call (macOS 27.0.1, `xnu-13432.1.9`, 10 cores):

| call | 1 | 2 threads | 4 threads | 8 threads | 2 processes | 4 processes | 8 processes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `getppid`, no descriptor | 0.16 | 0.13 | 0.18 | 0.25 | 0.13 | 0.13 | 0.14 |
| `fstat` of its own descriptor | 0.23 | 0.51 | 2.69 | 6.51 | 0.24 | 0.24 | 0.41 |
| 1-byte `write` and `read` on its own socket pair | 0.61 | 0.94 | 5.33 | 14.8 | 0.62 | 0.64 | 0.81 |
| `sigprocmask`, reading the mask | 0.16 | 0.17 | 0.17 | 0.29 | | | 0.16 |
| `sigprocmask(SIG_SETMASK)`, to the same mask | 0.16 | 0.27 | 1.63 | 7.53 | | | 0.22 |
| `setjmp` then `longjmp` | 0.59 | 0.78 | 1.54 | 7.17 | | | 0.65 |
| `_setjmp` then `_longjmp` | 0.022 | 0.021 | 0.024 | 0.024 | | | 0.029 |

Non-blocking ping-pong over socket pairs, busy-polling, makes 510k, 297k and 185k round trips a second in all on 1, 2
and 4 pairs of threads, against 510k, 835k and 1.36M as processes. The calls do not just queue: 8 threads make 1.2
million `fstat`s a second between them, where one thread alone makes 4.3 million.

**The descriptor lock.** In XNU's published source (`xnu-12377`, macOS 26; the running `xnu-13432` behaves the same),
`fp_lookup` and `fp_get_ftype` (`bsd/kern/kern_descrip.c`), which turn a descriptor into its file and take a reference,
hold `proc_fdlock_spin(p)`, a mutex per process over the whole descriptor table (`p->p_fd.fd_lock`), and `fp_drop` takes
it again to give the reference back. `read` and `write` go through `preparefileread` and its write counterpart
(`bsd/kern/sys_generic.c`); `kevent` finds its kqueue with `kevent_get_kqfile`, through `fp_get_ftype`, and a
registration looks its descriptor up with `fp_lookup`. So a round trip's `write`, `read` and `kevent` take the same lock
six times. There is no lock-free path, sysctl or flag. FreeBSD made lookups lock-free in 2009 (`fget_unlocked`, commit
`bf422e5f27`, FreeBSD 8.0: an atomic reference on the file's own count, checked against the entry's sequence counter),
and Linux with RCU in 2005, so there threads contend only on the same file.

**The proc lock.** `sigprocmask` that sets a mask (`kern_sig.c`: `block_procsigmask`, `unblock_procsigmask`,
`set_procsigmask`) takes `proc_lock(p)` and rewrites the mask of every thread in the process; reading takes no lock.
libc's `longjmp` sets the mask its `setjmp` saved, and Scala Native makes such a pair on every continuation resume,
about 1.9 per round trip in `concurrent`, as many as its descriptor calls. This is also why `MigrateCheck` saw masks
change on threads that had not touched them. The `setjmp` override removes it; see [`scala-native.md`](scala-native.md).
FreeBSD's `kern_sigprocmask` (`sys/kern/kern_sig.c`) takes `PROC_LOCK(p)` even to read the mask, and its libc saves and
restores the mask, so there both halves of every resume would take the process lock: from the source, not measured.

**Linux has neither lock.** The same program on Linux 7.0.14, in Docker Desktop's VM on the same machine (10 virtual
CPUs, host load about 7): threads and processes cost the same in every mode. 1 → 8 threads, µs per call: `fstat` 0.21
→ 0.25, `write` and `read` 0.73 → 0.90, setting the signal mask 0.16 → 0.17; `setjmp` and `longjmp` 0.043, the same as
`_setjmp`, since glibc's leave the mask alone. Ping-pong over socket pairs, median of 3 runs, µs a round trip (round
trips a second in all):

| mode | 1 pair | 2 pairs, threads | 4 pairs, threads | 2 pairs, processes | 4 pairs, processes |
| --- | --- | --- | --- | --- | --- |
| busy `read` | 1.77 (563k) | 2.35 (851k) | 2.51 (1.59M) | 2.28 (876k) | 3.28 (1.22M) |
| epoll, edge-triggered | 2.48 (404k) | 3.73 (536k) | 4.39 (911k) | 3.53 (566k) | 4.89 (818k) |
| io_uring | 2.41 (415k) | 3.14 (637k) | 4.90 (816k) | 3.00 (668k) | 4.34 (921k) |
| io_uring, registered descriptors | 2.32 (432k) | 2.92 (685k) | 4.29 (933k) | 2.74 (730k) | 3.96 (1.01M) |

Time per round trip grows with pairs for processes as much as for threads: on Linux it is the shared machine, not a
lock. io_uring, submitting each side's write with its next read in one `io_uring_enter` and reaping with deferred task
work, is level with epoll, and 5–10% ahead with its descriptors and ring registered; a first version, one
`io_uring_enter` per read and per write, was 2–4 µs slower a round trip than epoll. How io_uring is driven matters more
than using it.

**Spinning on `kevent` is costly to the rest of the process.** The same ping-pong with a kqueue per thread, polled
without blocking without limit, then `read`: 5.93 µs a round trip on one pair (busy `read`, 1.87), 7.86 and 24.6 on 2
and 4 pairs of threads, and as processes 7.67 and 18.6, each process's two threads spinning on its one descriptor lock.
The pool spins 4 polls and then blocks.

**Nothing else in XNU carries socket I/O without the lock.** `sendmsg_x` and `recvmsg_x` (private) batch within one
socket only; workloops (`kevent_id`) avoid it only for threads the kernel's workqueue binds, libdispatch's model;
Mach ports and `os_sync_wait_on_address` avoid it but carry no socket data; Skywalk channels take it once per batch of
packets, but are private. Socket data always pays it, at least once a call.

**Waking workers another way gains nothing.** `bench/wakeup-contention.c` ping-pongs a wake-up between the threads of
N pairs (median of 3 runs, load rising from 2.8 to 12). Blocking wake-ups cost the same whatever carries them, and as
much between processes as between threads, so the limit is system-wide: on one pair `EVFILT_USER` 3.00 µs a round trip,
`mach_msg` 3.01, `os_sync_wait_on_address` 2.78, and a Mach port watched by the kqueue 4.01; on 8 pairs of threads
30.5, 27.9, 27.4 and 27.2, against 28.1 for `EVFILT_USER` as 8 processes. Only polling tells them apart (polled
`mach_msg` 1.32 on one pair, polled `kevent` 3.87), and a reactor must poll its kqueue for sockets anyway. The pool's
wake-ups stay `EVFILT_USER`.

**Network.framework** is Apple's way round the lock, a user-space stack over Skywalk; SwiftNIO's POSIX transport, an
event loop and kqueue per thread as here, meets the same lock. Over loopback Network.framework uses the kernel's
sockets, and is slower than blocking BSD sockets and scales worse within one process ([`bench/network-framework.c`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/network-framework.c)); see
[`nwframework.md`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/docs/io/nwframework.md).

**What this means.** On macOS a pool in one process cannot scale I/O beyond about one busy thread, and every extra
thread making descriptor calls slows the others; cats-effect is under the same limit. The pool helps computation, as in
`mixed`, not I/O. What would help, in order: several processes, each with its own pool, sharing the listening socket
(the only way measured to scale: 510k → 1.36M round trips a second on 1 → 4 processes), which is sharding, an
architectural choice for later; fewer descriptor calls per op, which the reactor is already down to (see
[`kqueue.md`](kqueue.md)); and fewer threads making them. On FreeBSD and Linux a pool should scale I/O, and the take
policies matter again, so the pool's size and use depend on the OS, not the backend.

## Three I/O-worker designs, measured and reverted

Each was an experiment, not kept; the pool stays a kqueue per worker. Two scenarios were added for them and kept:
`concurrent-compute`, `concurrent` with about 4 µs of computation after each round trip in the connection's own future,
and `concurrent-offload`, the same computation in a future of its own, awaited.

**I/O on one worker, computation on the rest** (`io1`): ops were kept to the first worker; a task elsewhere that
performed an op moved there first, through its inbox. The others made no non-blocking `kevent` turns, only the blocking
one when idle ([`bench/results/pool-io-workers.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/pool-io-workers.json)):

| scenario | 1 | 2-affine | 2-io1 | 4-affine | 4-io1 |
| --- | --- | --- | --- | --- | --- |
| pingpong | 6.97 | 6.86 | 6.85 | 6.86 | 6.80 |
| concurrent | 1.84 | 2.05 | 1.84 | 3.78 | 1.86 |
| concurrent-compute | 6.28 | 3.89 | 6.27 | 3.91 | 6.32 |
| concurrent-offload | 7.32 | 4.65 | 4.56 | 4.05 | 5.42 |
| mixed (compute) | 26.6 (26.6) | 7.60 (35.6) | 7.23 (20.6) | 7.76 (35.6) | 7.23 (20.6) |

Pure I/O costs what one worker does however many there are, and computation on a worker that makes no `kevent` turns
is faster (`mixed`'s slices 20.6 µs against 35.6). But computation inside a connection's own task stays on the I/O
worker (`concurrent-compute` is the one-worker number), and in a future of its own each round trip costs a move back to
the I/O worker, with a wake-up.

**A dedicated I/O worker that runs no tasks** (`1+n`): every `perform` handed its op to the I/O worker's inbox and
suspended; the completion queued the resume on the shared queue ([`bench/results/pool-dedicated-io.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/pool-dedicated-io.json)):

| scenario | 1 | 4-affine | 1+1 | 1+2 | 1+4 |
| --- | --- | --- | --- | --- | --- |
| pingpong | 6.96 | 6.99 | 21.9 | 22.0 | 21.8 |
| concurrent | 1.96 | 3.69 | 3.42 | 4.92 | 7.48 |
| pipelined | 0.70 | 0.64 | 8.39 | 9.63 | 10.0 |
| connections | 39.7 | 19.9 | 54.0 | 56.5 | 57.2 |

Every op crosses threads twice and the fast path is gone. Of the variants tried on a loaded machine (indicative only),
none recovered ping-pong; the I/O worker polling before blocking made it worse (22 → 35), contending with the other
pool's I/O worker for the descriptor lock, and computing workers parking their threads rather than blocking in their
kqueues helped only `concurrent` on 1+4 (7.33 → 4.76).

**One kqueue per pool, polled by whichever worker is idle**, Tokio's design: ops performed on the task's own worker,
readiness registered in one shared kqueue (`kevent` is safe from any thread), an edge with no task waiting counted per
descriptor and direction so that it is not lost ([`bench/results/pool-shared-kqueue.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/pool-shared-kqueue.json)):

| scenario | 1 | 2-affine | 4-affine | shared-1 | shared-2 | shared-4 |
| --- | --- | --- | --- | --- | --- | --- |
| pingpong | 6.98 | 6.88 | 7.01 | 6.76 | 6.98 | 6.94 |
| concurrent | 1.87 | 2.13 | 3.67 | 1.71 | 1.90 | 4.40 |
| concurrent-compute | 6.26 | 3.70 | 3.75 | 6.16 | 3.30 | 4.51 |
| connections | 40.5 | 21.3 | 21.2 | 41.7 | 35.6 | 35.2 |
| pipelined | 0.67 | 0.65 | 0.67 | 2.00 | 1.16 | 1.09 |
| fairness (tick) | 1.39 (2.78) | 1.43 (2.85) | 1.48 (2.97) | 10.5 (21.1) | 0.88 (17.7) | 1.02 (18.0) |

Ping-pong costs the same as a kqueue per worker, and `concurrent` is a little faster on 1 and 2 workers, but it does
not scale to 4 either: every worker still makes its own `read` and `write`. The scheduling around the shared driver
(`connections`, `pipelined`, the ticker) was not tuned further.

**Lessons, which stay:**

- An op that can finish at once must finish on the thread that performs it. Handing ops to another thread pays a
  hand-off and a wake-up each way, which on macOS costs more than the descriptor lock it saves.
- On macOS I/O does not scale past about one busy worker per process, however readiness is arranged. What helps is
  fewer descriptor calls from fewer threads.
- A thread should poll one kqueue, not two: polling a second each round cost a round trip about 13 µs and tripled the
  involuntary context switches.
- Write readiness should be one-shot; a persistent edge-triggered write filter fires whenever the peer reads, which
  made the shared kqueue's ping-pong 40 µs until changed.
- `wakeup` skips the system call while a wake-up is pending, so anything that leaves the wake event untaken loses later
  wake-ups.
- The shared kqueue is the design to try on Linux and FreeBSD, where `read` and `write` from several threads scale.

## What is open

- **Measure the pool on Linux**, where neither lock exists, which needs an epoll or io_uring backend; driven as above,
  io_uring with registered descriptors is the one to build. Then the take policies again, and the shared-kqueue design.
- **FreeBSD**: confirm with `descriptor-contention.c` (its `kqueue` mode builds there) and the pool; it needs a
  FreeBSD version of the `setjmp` override.
- **Sharding across processes** on macOS, sharing the listening socket.
- **Cancelling a gears `Future` is expensive on Scala Native.** `timers` arms each timer by a future performing it and
  cancels half of them: 20.7 µs a timer on kqueue (release, 7 runs, all within 20.6–20.8), against 0.41 when timers
  were submitted with completions of their own; on the JVM 1.83 on NIO's reactors ([`bench/results/2026-10-25-timers-native.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/2026-10-25-timers-native.json),
  `-timers-jvm.json`). A profile is almost all `libunwind`, through `scalanative_personality`: a cancelled future's
  `perform` throws its `CancellationException` through the future's body, unwound frame by frame through the DWARF
  tables, and gears' `RunnableFuture` replaces it with a new `CancellationException`, capturing a stack for each. A
  stack-less one there took a debug build from 61.8 to 40.8 µs a timer; the rest is the throw itself. Ways in, none
  tried: no stack in gears core's cancellation exceptions, and an op as a passive `Future` of its own
  (`performAsync`), completed with its outcome rather than thrown out of a body. The rest of the cost against the old
  0.15–0.23 µs is each op's membership of its group (a hash set under a lock), which is what lets a scope's end cancel
  it. A timer re-armed again and again, QUIC's case, no longer pays it: a deadline timer's deadline moves while one
  future performs it (`Deadline.moveTo`, 0.003 µs a move against 42–64 µs a cancel and a new future, in
  [`bench/results/native-rearm-timers.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/native-rearm-timers.json)); other ops still do.
- **`fairness` and `mixed` after the turn budget and yield**: each pair of a compute slice and a round trip takes about
  2 µs longer than before, for a reason not found ([`bench/results/turns-combined.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/turns-combined.json)).
- **A lone worker's slow polls**, not explained.
- Resuming a task whose awaited future finished on another worker back on the worker it last ran on.
