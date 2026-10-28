# Measuring: the bench and its tools

Every performance claim in [`kqueue.md`](kqueue.md), [`runtime.md`](runtime.md) and
[`scala-native.md`](scala-native.md) comes from the tools described here: the bench's scenarios, the Scala CLI scripts
in `bench/` that build, run and count them, and small C programs that answered questions about the kernel. This note
says what each measures, how to use it, and how to measure so that a few percent mean something. The JVM's builds are
measured the same way; their findings are in [`jvm.md`](jvm.md).

## The scenarios

`GearsBench` (`bench/io-bench/shared/src/main/scala/gearsbench/GearsBench.scala`, the `ioBench` project) runs an echo
server on one pool and the clients on another, so both sides go through the reactor, and times each scenario on its own over Unix stream sockets
(`--tcp` for TCP). Each scenario stresses one cost:

| scenario | what it does | what it stresses |
| --- | --- | --- |
| `pingpong` | one 64-byte message in flight on one connection | a resume and a readiness wait per message on each side |
| `pingpong-fresh-ops` | as `pingpong`, building its ops anew for every message | the cost of not keeping ops |
| `pingpong-pooled` | as `pingpong`, reading each echo into a buffer of the backend's pool | the pooled read |
| `pingpong-deadline` | as `pingpong`, with a time limit on every op | arming and cancelling a timer per op |
| `large`, `large-pooled` | as `pipelined`, with 128 KB messages (`GEARS_BENCH_LARGE`) | a read's copies, at a size where they count |
| `concurrent` | many connections ping-ponging at once | many events and resumes per turn |
| `concurrent-compute` | as `concurrent`, with about 4 µs of computation after each round trip in the connection's future | computation beside I/O on one task |
| `concurrent-offload` | as `concurrent-compute`, the computation in a future of its own, awaited | computation handed to another future |
| `connections` | a new connection for each round trip: connect, one message, close; the server accepts each | opening and closing |
| `pipelined` | one connection, each message its own write, replies read at the same time | ops that finish at once |
| `blocking` | trivial blocking tasks one after another | a wake-up from a blocker thread for each |
| `blocking-io` | a blocking task and a round trip in turn | moving between blocking work and I/O |
| `timers` | timers armed 1,000 at a time, each by a future, half firing and half cancelled | many pending timers, and cancellation |
| `rearm-cancel` | one timer re-armed again and again by cancelling the future performing it and performing a new one | re-arming as it was done before deadline timers |
| `rearm-later` | one deadline timer performed once, its deadline moved later for each re-arm | `Deadline.moveTo`, QUIC's usual case |
| `rearm-earlier` | the same, moved earlier each time | filing it again, and waking its reactor from another worker |
| `fairness` | pipelined traffic while a ticker on the same thread performs zero-length timers | reports `fairness-tick`, the time between ticks |
| `mixed` | `pingpong` while a compute future on the same thread works in slices, yielding between them | reports `mixed-compute`, the time per slice |
| `starvation` | `concurrent`'s round trips while a compute future per core works in slices, and a checker sleeps 1 ms over and over | reports the checker's lateness at p50, p99 and worst, as cats-effect's starvation check does |

A scenario prints a `bench:` line with its operations, seconds and µs per operation; `--scenarios a,b` picks some,
`--scale` multiplies their counts, `--workers` sizes both pools, `--policy` picks the take policy, and
`--connections` sets `concurrent`'s count (at most 128 with more than one worker, since a macOS listen queue holds 128).
`GEARS_POOL_STATS=1` prints each worker's counters at the end. A scenario a backend cannot run, for want of a
capability, is skipped. The scenarios, like the demos, are written against the interface alone, taking the `Io` they run
with: each platform's bench (`gearsbench.Main`) runs them over the backends its demo application offers
(`--backend`), whose runner starts the pools with the workers and policy asked for.

Two references sit beside it. `bench`, without `gears`, runs the same scenarios written as a plain hand-written loop
over `KqueueLoop`, with completions and kernel timers and no gears, as the floor a reactor could reach (all but
`pingpong-fresh-ops`, `fairness` and `mixed`). `bench/ce-bench` runs them with cats-effect and fs2, and `move-bench`
(`GearsMoveBench`) measures what moving work and descriptors between reactor threads costs.

## Building: `bench/build.sc`

```
bench/build.sc <commit> <output>
```

builds the bench's executable, `iobench` (`ioBench` on Scala Native), at a commit, release-fast, against the Scala Native nightly, in a
temporary `git worktree` with its own sbt, so the working tree and its sbt server are left alone. `<commit>` may be
`WORKTREE`, for HEAD with the working tree's changes, untracked files included (ignored ones are not; a nested
repository, such as another worktree, is not brought over). The worktree is removed by a shutdown hook however the
script ends. Commits from before the nightly switch existed get it from the working tree's `project/plugins.sbt`.

It links with conditional yieldpoints (`YIELDPOINTS=trap` for trap-based ones; see
[`scala-native.md`](scala-native.md)), and checks what it built: that the nightly's `nativelib` was used, that the
executable reports a release build, and, from its symbols, which yieldpoints it has. `PROJECT=ceBench` builds the
cats-effect version instead. A commit from before the bench had a project of its own builds the kqueue demos'
executable, `iokqueuedemos`, which ran the same commands (`gears bench`, and `bench` for the reference), so builds from
either side of the move compare alike. Beside the executable it writes `<output>.json`, its provenance: the commit and subject,
whether working-tree changes were built and a hash identifying them, the mode, the Scala Native version and the
yieldpoints.

Release builds only: debug builds overstate the Scala side's cost.

## Building for the JVM: `bench/build_jvm.sc`

```
bench/build_jvm.sc <output>
```

packages the JVM bench (`ioBenchJVM`) from the working tree, copying their jars into `<output>/lib` so that later builds do not change
what is measured, and writes launchers beside them: `nio-caller`, `nio-reactors`, `jdk-vthreads`, `jdk-selector`,
`jdk-selector-here` and `cats-effect` (compared as `@raw`). Each is a two-line shell script that `exec`s `java`, so that
`compare.sc` runs every build alike, as a program, and each first runs every scenario at a fifth of its count, unreported
(`--warmup`), so that the JIT has compiled what is measured. Each launcher has a provenance record. What the builds are
is in [`jvm.md`](jvm.md).

## Comparing: `bench/compare.sc`

```
bench/compare.sc before=/tmp/b.before after=/tmp/b.after raw=/tmp/b.after@raw --runs 5 --save -- --scenarios pingpong,pipelined
```

runs each build `--runs` times (3 by default; the numbers in these notes use 5, or 7 where a difference needed
confirming), **alternating between builds**, so that drift in the machine's load affects them alike, and prints each
scenario's median µs/op with each later build's change against the first. A path suffixed `@raw` runs the hand-written
loop (`bench`), and `@nwframework` the Network.framework backend (with `-- --tcp`). Arguments after `--` go to the
bench.

- `--timeout SECONDS` (600 by default): a run past it has its threads dumped to `bench/results/hangs`, with `jcmd` for
  a JVM, virtual threads included, or macOS's `sample` otherwise, before it is killed; it counts as a failed run, which
  `--retries` reruns. This is how intermittent hangs are caught with evidence. A run that still fails keeps the
  scenarios it finished and the comparison goes on; the table marks medians taken over fewer runs, the saved file
  lists the failures (`incomplete`), and the script exits with 1.
- `--save` writes `bench/results/<date>-<labels>.json`, `--save-to FILE` to a file of one's choosing: every run's
  numbers and the medians, each build's provenance record, the arguments, the date and the load average before and
  after. Results files are ignored by git and added with `git add -f` when a finding cites them. The results the docs
  cite are kept off the branch under review, at the tag `async-io-full-2026-10-28` on the fork
  ([`bench/results`](https://github.com/bishabosha/gears/tree/async-io-full-2026-10-28/bench/results)), which each
  citation links to.

## Counting system calls: `bench/syscalls.sc`

```
bench/syscalls.sc [--save FILE] [--scenarios a,b] <executable>[@raw]
```

counts the system calls each scenario makes per operation, using lldb breakpoints in batch mode, so it needs neither
root nor SIP disabled, as `dtruss` does. Each breakpoint on a wrapper in `libsystem_kernel.dylib` prints the thread and,
for `kevent`, its arguments, and continues. Each scenario runs twice, at about 100 and 200 operations, and the
difference over the extra operations is the count per operation, without setup and teardown. Calls are counted on the
client's thread (the main one, which runs the reactor under test) apart from the others. `kevent` is split by what the
call does: registration changes only, a poll that returns at once, or a wait, with or without changes. Under the
debugger the server answers slowly, so the split between polls and waits leans to waits and is only indicative; the
other counts do not depend on timing.

## Profiling

macOS's `sample` on a running release build gives self time by function; lldb breakpoints on `scalanative_GC_alloc*`
count allocations per operation as `syscalls.sc` counts calls. Both found what [`kqueue.md`](kqueue.md) records under
profiling.

## The C probes

Plain C programs, built with `cc -O2` and each documented in its header, that took gears and Scala Native out of a
question about the kernel:

| program | question | answer |
| --- | --- | --- |
| `bench/descriptor-contention.c` | Do descriptor calls from threads of one process slow each other down where separate processes do not? Does setting the signal mask? And on Linux, with epoll and io_uring? | Yes on macOS, for both: the per-process descriptor lock and the proc lock. Not on Linux. See [`runtime.md`](runtime.md). |
| `bench/wakeup-contention.c` | Is waking a worker by Mach port, `mach_msg` or `os_sync_wait_on_address` cheaper than by `EVFILT_USER`? | No: blocking wake-ups cost the same whatever carries them, between threads or processes. |
| `bench/write-events.c` | How often does a persistent edge-triggered write filter fire when nothing waits to write? | Once per turn of a ping-pong, so write filters stay one-shot. See [`kqueue.md`](kqueue.md). |
| [`bench/network-framework.c`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/network-framework.c) | Does Network.framework get round the descriptor lock? | Not over loopback, where it uses the kernel's sockets. See [`nwframework.md`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/docs/io/nwframework.md). |

## Measuring well

- **Check the load average** before and during a comparison (`sysctl -n vm.loadavg`; `compare.sc --save` records it
  before and after). A comparison under a load that rose during it, as one spin-count trial did from 3.7 to 13.8, is too
  noisy to use, and is repeated on a quiet machine.
- **Check that nothing else is busy**: Spotlight indexing, builds, sbt servers, browsers. A busy core changes ping-pong
  timings, which depend on wake-ups.
- **Alternate builds** rather than running one after the other: `compare.sc` does. A build that always runs second
  under rising load looks slower.
- **Take the median of 5 runs**, 7 to confirm a small difference. Medians from the same build differ by about 1%
  between runs, `blocking` by up to about 6%, and `concurrent` alternated between about 2.2 and 2.6 µs from run to run in
  every build for a while: a change within that is noise.
- **Compare like with like**: the same policy and the same number of workers on both sides. `connections` on one
  worker read 40 µs instead of 21 for a while because some builds ran the default policy, then free, and others
  `--policy affine`.
- **Release builds, against the nightly**, with the yieldpoints the provenance record states.

## Linux, in Docker

Linux has none of macOS's per-process locks, so it is where the pool's scaling and other drivers (epoll, io_uring) can
be judged. Without a Linux machine, measurements run in Docker Desktop's VM on the same Mac (Linux 7.0.14, 10 virtual
CPUs): `descriptor-contention.c` builds there with `-luring`. The VM shares the host's cores and load, so its absolute
numbers are virtualised and indicative, and what it shows reliably is how costs grow with threads against processes.
Loopback in the VM has no delay of its own; to see behaviour over a real network, `tc netem` adds a delay to every
packet (for example 50 µs or 500 µs), as the JVM measurements did (see [`jvm.md`](jvm.md)).
