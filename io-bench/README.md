# io-bench

Scenarios over every capability of the I/O interface, run over this branch's backends and, for comparison, over
another branch's.

## What is here

- `shared/` has `CapBench`: the scenarios, written against the interface alone, so the same source runs over any
  backend. Each prints one line, `bench: <name> <ops> ops <seconds> s <microseconds> us/op`.
- `jvm/` and `native/` have each platform's buffers (`BenchPlatform`) and its `BenchMain`, which runs `CapBench` over
  this branch's backends: `NioBackend` and `NioPoolBackend` on the JVM, `EventPollingBackend` on Scala Native.
- `other/` has the `BenchMain`s and the build definition for the branch compared against, `async-io-backends`, whose
  backends are started differently. They are not built here.
- `compare/` has `Compare`, which builds and runs both sides and writes the comparison.
- `results/` has the comparisons that were run, as Markdown.

## Running one side

```
sbt "ioCapBenchJVM/run --backend threads --workers 4 --scale 0.5 --repeat 5"
sbt "ioCapBenchJVM/run --backend pool --workers 4 --scenarios pipe-pingpong,tcp-pingpong"

SCALA_NATIVE_NIGHTLY=1 sbt ioCapBenchNative/nativeLink
SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=0 target/out/native0.5/scala-3.9.0/iocapbench/iocapbench --workers 4
```

`--scale` multiplies every scenario's count, `--repeat` runs the scenarios that many times in one process, and
`--connections` is how many pairs the concurrent scenarios run (64 unless given). A scenario that needs a capability
the backend lacks is skipped: FIFOs and Unix datagram sockets on the JVM.

A release build for Scala Native needs the nightly (`SCALA_NATIVE_NIGHTLY=1`): on 0.5.12 multithreaded release builds
crash. sbt reads that as its server starts, so a server already running must be shut down first (`sbt shutdown`).

## Comparing with another branch

From the root of the repository:

```
scala-cli run io-bench/compare -- --out io-bench/results/<name>.md
scala-cli run io-bench/compare -- --help
```

`Compare` checks the other branch out in a worktree under `target/io-bench-compare`, copies this bench into it with
that branch's `BenchMain`s, builds both sides, runs each configuration and writes a table for each platform. It
restarts sbt's server with the nightly switch, and shuts it down when it is done. A full run takes about an hour.

On the JVM a configuration is one process that repeats the scenarios, with the first repeats dropped as warm-up. On
Scala Native a configuration is run several times. Each figure is a median, from one machine with other programs
running: differences under about a tenth are within its noise.
