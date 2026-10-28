# Scala Native: changes proposed, and the workarounds meanwhile

Profiling the kqueue backend found costs, and one deadlock, that only Scala Native can remove, all on macOS arm64. This
note records each with its problem, the fix proposed and what it gains, then the workarounds this repository uses until
they land: the `setjmp` override and the `Unblocked` bindings, and why each is sound. It ends with the build settings
that follow: release builds against the Scala Native nightly, and conditional yieldpoints. The `scala-native-setjmp-issue`
branch has a draft issue (`scala-native-issue-setjmp.md`) with the analysis and repros. Unless said otherwise, numbers are
the median of 5 runs of release-fast builds of the bench (see [`bench.md`](bench.md)) on an M1 Pro running macOS 27.

## Continuation resume: `_setjmp` instead of `setjmp`

**Problem.** `scalanative_continuation_resume` (`delimcc.c`) calls libc `setjmp`, which on macOS and the BSDs also saves
the signal mask and the alternate signal stack, a `sigprocmask` and a `sigaltstack` call; `longjmp` then sets the mask
again. glibc's do neither. So every continuation resume, which is every `perform` that suspends, costs two system calls
more on macOS than on Linux: before the workaround below, 1.3 pairs per round trip in `pingpong` and 1.9 per message in
`concurrent`, outnumbering the I/O calls themselves. With threads it is worse than two calls: `sigprocmask` that sets a
mask takes the process lock and rewrites every thread's mask, so threads making these calls slow one another
(`bench/descriptor-contention.c`, µs per `setjmp` and `longjmp` pair: 0.59 on one thread, 7.17 on 8, against 0.022 to
0.024 for `_setjmp` and `_longjmp`; see [`runtime.md`](runtime.md), "macOS's per-process locks").

**Fix.** `_setjmp` in `delimcc.c`, with the matching `_longjmp` in `eh.c` and `eh.cpp`. The mask needs no saving: a
continuation's frames do not change it.

**Gain.** Patching the nightly's `delimcc.c` and `eh.c` in the link output, single-threaded: `pipelined` −13%,
`concurrent` −10%, `pingpong-deadline` −11%, `pingpong` −8%, `blocking` −2% ([`bench/results/patched-setjmp.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/patched-setjmp.json)). On
FreeBSD it matters as much, for another reason: there `sigprocmask` takes the process lock even to read the mask, so
both halves of every resume would take it (from the source, not measured).

## GC register capture on arm64: `_setjmp` or inline assembly

**Problem.** Around every `@blocking` extern call the thread's GC state switches, and `RegistersCapture`
(`gc/immix_commix/RegistersCapture.h`) captures the registers with inline moves on x86 but falls back to libc `setjmp`
everywhere else. So on macOS arm64 posixlib's `read`, `write` and `kevent` cost three system calls each.

**Fix.** `_setjmp`, or capturing x19–x28, the frame pointer and the stack pointer inline, as on x86-64. With it the
`Unblocked` bindings below could go, and every library's `@blocking` calls would get cheaper.

**Gain.** What the workaround measured: 8–17% (`pingpong` −17%, `concurrent` −12%, `pipelined` −12%, `blocking` −8%;
[`bench/results/unblocked-syscalls.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/unblocked-syscalls.json)).

## Safepoint polls without a thread-local on macOS

**Problem.** Thread-local lookups (`_tlv_get_addr`) take 18–20% of CPU in the throughput scenarios, two thirds of it
from safepoint polls (by caller: safepoint polls 66%, `currentThread` 11%, the allocator 10%, monitors 8%). With
trap-based yieldpoints, the default in release builds, every poll is a volatile load of the thread-local
`scalanative_GC_yieldpoint_trap`, and on macOS every thread-local access is a function call, where Linux has a
register-relative load.

**Fix**, for Scala Native to choose: a process-wide trap page where stopping threads one at a time is not needed, or
keeping the cell where Darwin can read it without `_tlv_get_addr`.

**Gain.** Conditional yieldpoints, which poll a global, show what is at stake: 7–14% on the throughput scenarios
(`pipelined` −10%, `concurrent` −7%, `blocking` −7%, `timers` −14%, ping-pong −1%;
[`bench/results/conditional-yieldpoints.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/conditional-yieldpoints.json)). The bench links with them; see below.

## The GC's signal blocking: `pthread_sigmask` instead of `sigprocmask`

**Problem.** On macOS, multithreaded release builds can deadlock in a collection, and debug builds block SIGINT and
SIGTERM in every thread for longer than a collection. `Synchronizer_acquire` and `Synchronizer_release` (`gc/immix` and
`gc/commix`, `Synchronizer.c`) call `sigprocmask`, which on macOS changes every thread's mask, without synchronising
with another thread's return from a signal handler: even with an empty set, it can write back a thread's mask as it was
inside the handler. With trap-based yieldpoints the set is empty (`Synchronizer_init` fills it only for conditional
yieldpoints), so the calls do nothing but race. A thread returning from the safepoint trap's handler at that moment keeps
SIGBUS blocked; at the next collection its trap cannot be delivered, it faults forever on the poll, and the GC waits
until `SCALANATIVE_GC_SYNC_TIMEOUT_MS` aborts.

**Evidence.** Three threads calling `sched_yield` in a loop beside an allocating one hang 4 runs in 5, on 0.5.12 and the
nightly; never with conditional yieldpoints, nor with the calls redirected to `pthread_sigmask`. A C program racing
`sigprocmask(SIG_BLOCK/SIG_UNBLOCK, empty)` against handler returns leaves the handler's signal blocked in 552 of 948k
returns, and in none with `pthread_sigmask`. `MigrateCheck` found it, seeing SIGINT and SIGTERM blocked after about 1
in 5,000 resumes in a debug build, whether or not tasks moved.

**Fix.** `pthread_sigmask` in both synchronizers. Until then a pool of reactor threads, all making `@blocking` calls,
risks the deadlock with trap-based yieldpoints; conditional yieldpoints avoid it.

## The GC's interior-pointer roots: fixed upstream

On 0.5.12, multithreaded release builds of the gears demos crash intermittently in `perform` just after a continuation
resumes: the GC ignored conservative roots that point partway into an object, so an object referenced only by such a
pointer, as optimised code can hold across a suspension, was collected while live. Debug builds are unaffected. The
`gears-release-repro` branch reproduces it with gears alone, two threads awaiting through `Async.withGroup`. Fixed by
[scala-native#5080](https://github.com/scala-native/scala-native/pull/5080), for
[#5046](https://github.com/scala-native/scala-native/issues/5046), not yet released.

## Workaround: the `setjmp` override

`io-backends/kqueue/src/main/resources/scala-native/setjmp_override.S` defines `setjmp` and `longjmp` as branches to
`_setjmp` and `_longjmp`, for macOS on arm64. It is a native resource of the kqueue backend, so it applies to every
program that links `gears-io-kqueue`, and only to those: gears' core links libc's `setjmp` as upstream does. The
Network.framework backend runs on kqueue's reactors and depends on `gears-io-kqueue`, so its programs link the override
too (since 2026-10-27; its test binary defines `_setjmp` and `_longjmp` as branches to `__setjmp` and `__longjmp`, as
kqueue's does). References within the binary bind to these
definitions before libc's, so Scala Native's continuation resume, its exceptions and the GC's register capture all skip
the mask and the alternate stack. Affine pool, without → with it ([`bench/results/pool-setjmp-override.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/pool-setjmp-override.json)):

| scenario | 1 worker | 2 workers | 4 workers |
| --- | --- | --- | --- |
| pingpong | 7.39 → 6.72 (−9%) | 7.44 → 6.79 (−9%) | 7.37 → 6.77 (−8%) |
| concurrent | 2.15 → 1.84 (−14%) | 2.32 → 2.10 (−9%) | 3.73 → 3.75 |
| connections | 23.5 → 22.3 (−5%) | 24.2 → 22.7 (−6%) | 24.1 → 22.9 (−5%) |
| pipelined | 0.67 → 0.69 | 0.65 → 0.63 | 0.67 → 0.62 (−7%) |
| blocking | 5.58 → 5.09 (−9%) | 6.20 → 5.40 (−13%) | 6.24 → 5.47 (−12%) |
| blocking-io | 14.1 → 13.0 (−8%) | 14.1 → 13.0 (−8%) | 14.0 → 13.1 (−7%) |
| fairness (tick) | 1.72 → 1.38 (3.43 → 2.75) | 1.73 → 1.41 (3.46 → 2.81) | 1.75 → 1.47 (3.51 → 2.95) |
| mixed | 23.2 → 22.7 | 8.21 → 7.65 (−7%) | 8.23 → 7.68 (−7%) |

`timers` is unchanged, and `concurrent` on 4 workers is held by the descriptor lock instead.

Why it is sound:

- **It is how Scala Native already runs on Linux.** glibc's `setjmp` leaves the mask alone (on Linux `setjmp` and
  `longjmp` cost 0.043 µs, the same as `_setjmp`), so the runtime's continuations, exceptions and GC already work without
  the mask being saved and restored.
- **Nothing relies on `longjmp` restoring the mask.** Nothing in Scala Native's runtime or in gears expects a
  `longjmp` to put back a mask changed since its `setjmp`. Code in the same binary that did would break: that is the
  override's one cost, stated in its header and in `build.sbt`.
- **For a moved continuation, restoring the mask would be wrong.** A continuation resumed on another worker's thread
  would, with libc's `setjmp` and `longjmp`, carry signal state from one thread to another; each thread should keep its
  own alternate stack and mask. `MigrateCheck` checks that the new thread's are its own after every moved resume, and
  passes with the override, with exceptions thrown and caught across moves (see [`runtime.md`](runtime.md)).
- **It binds only references inside the binary.** libc's own uses of `setjmp`, and those of system libraries loaded
  dynamically, still bind to libc's.

The pool's checks (`MigrateCheck`, which throws after moves and checks signal masks, `RunQueueCheck` and
`SharedDescriptorCheck`) and the demos' tests pass with it. A FreeBSD version would be needed there.

## Workaround: the `Unblocked` bindings

`gears.async.io.internal.Unblocked` (core, `native/`) binds `read`, `write`, `accept`, `recvmsg`, `sendto`, `ioctl` and
`kevent` without `@blocking`, and the kqueue backend uses them where the call cannot block: on non-blocking descriptors,
and for `kevent` calls that only register or poll without waiting. The `kevent` that waits keeps posixlib's `@blocking`
binding, so that a collection does not wait for it. `ioctl` is bound for `FIONBIO`, which posixlib lacks. This is sound
because `@blocking` only tells the GC that the thread may be away for long, which a call that returns at once never is.
With the register-capture fix in Scala Native, these bindings could go. With the `setjmp` override, a `@blocking` call
skips the signal calls too, so what `Unblocked` still saves there is the GC state switch itself, which has not been
measured apart. See [`kqueue.md`](kqueue.md).

## Release builds against the nightly

Because of the interior-pointer crash, release builds of anything multithreaded need a Scala Native with
scala-native#5080. `SCALA_NATIVE_NIGHTLY=1` builds against a nightly that includes it (`project/plugins.sbt`, which pins
`0.5.13-20261015-c3918a9-SNAPSHOT`, and `build.sbt`, which adds the snapshot resolver), and `bench/build.sc` always uses
it. Debug builds and the test suites run on 0.5.12. When 0.5.13 is released: set the plugin version in
`project/plugins.sbt`, and remove the nightly switch there and in `build.sbt`, and the nightly from `bench/build.sc`.

## Conditional and trap-based yieldpoints

Scala Native's release builds use trap-based yieldpoints by default: each safepoint poll loads a thread-local cell,
which the GC protects to stop threads. Conditional yieldpoints poll a global flag instead
(`SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=0` at link time). On macOS the conditional ones are both safer, avoiding the
signal-blocking deadlock above, and faster, avoiding `_tlv_get_addr` per poll. `bench/build.sc` links with them, and
`YIELDPOINTS=trap` links trap-based ones; each build's provenance record says which. At the switch, conditional against
trap-based (5 runs, load average 10; [`bench/results/conditional-yieldpoints-default.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/conditional-yieldpoints-default.json)): `timers` −9.5%, `fairness`
−7%, `mixed` −4.5%, the rest −1 to −3%, `pingpong-deadline` +0.4%. Applications on the pool are recommended to link
with them too, until the GC's synchronizers use `pthread_sigmask`.
