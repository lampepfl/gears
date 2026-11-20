# io-bench: the realistic scenarios across stacks

- gears: `d08f21e8` (run on its tree before it was committed)
- machine: Apple M3 Max, Darwin 27.2.0
- JVM: openjdk version "25.0.2" 2026-01-20 LTS
- date: 2026-11-19

Microseconds per op, lower is better. A dash is a scenario the stack was not run for, or has no way to do. Each column is one process that runs the scenarios 3 times at scale 1.0: the median of the last 2. What each scenario does is in `io-bench/stacks/README.md`.

## 1 thread, and the stacks that have only one

| scenario | gears kqueue | gears NIO pool | gears NIO threads | gears on Node.js | Netty | virtual threads | Ox | Kotlin coroutines | fs2-io, Scala.js | fs2-io, Scala.js on Wasm | JDK selector | fs2-io | Go | Node.js | Tokio |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| tcp-concurrent | 7.62 | 9.66 | 12.51 | 15.05 | 7.54 | 10.29 | 9.06 | 15.45 | 227.36 | 284.10 | 6.31 | 13.81 | 6.91 | 9.34 | 6.94 |
| tcp-realistic | 9.76 | 9.81 | 13.41 | 19.67 | 8.50 | 10.21 | 9.87 | 18.05 | 240.53 | 299.49 | 7.04 | 15.59 | 7.42 | 11.67 | 6.75 |
| tcp-realistic-bulk | 2.40 | 0.93 | 0.88 | 3.83 | 0.76 | 1.15 | 0.96 | 1.35 | 18.00 | 20.71 | 0.76 | 1.21 | 0.72 | 1.63 | 0.75 |
| tcp-realistic-gather | 2.80 | 0.95 | 0.96 | 3.49 | 0.93 | 1.17 | 0.97 | 1.45 | 19.16 | 20.26 | 0.74 | 1.29 | 0.75 | 1.67 | 0.78 |
| work-realistic | 1.41 | 0.13 | 0.18 | 1.39 | 0.18 | 0.16 | 0.14 | 0.15 | 2.21 | 1.17 | 0.20 | 0.13 | 0.18 | 0.70 | 0.22 |

## 4 threads

| scenario | gears kqueue | gears NIO pool | gears NIO threads | Netty | virtual threads | Ox | Kotlin coroutines | fs2-io | Go | Tokio |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| tcp-concurrent | 3.95 | 6.52 | 9.70 | 5.84 | 9.19 | 12.33 | 12.41 | 6.93 | 5.12 | 4.28 |
| tcp-realistic | 3.74 | 6.28 | 10.05 | 5.68 | 9.74 | 13.14 | 10.86 | 6.95 | 5.26 | 4.22 |
| tcp-realistic-bulk | 0.66 | 0.43 | 0.66 | 0.42 | 0.62 | 1.39 | 0.64 | 0.50 | 0.35 | 0.31 |
| tcp-realistic-gather | 0.79 | 0.46 | 0.67 | 0.45 | 0.63 | 0.95 | 0.63 | 0.48 | 0.37 | 0.35 |
| work-realistic | 1.42 | 0.15 | 0.24 | 0.19 | 0.16 | 0.29 | 0.15 | 0.13 | 0.17 | 0.22 |

## The stacks

- **gears kqueue**: `EventPollingBackend`, Scala Native nightly, release build
- **gears NIO pool**: `NioPoolBackend`, with `--add-opens java.base/java.lang=ALL-UNNAMED`
- **gears NIO threads**: `NioBackend`, its futures on the JDK's virtual threads
- **gears on Node.js**: `NodeBackend`, Scala.js on WebAssembly with JSPI, Node.js v26.5.0
- **Netty**: io.netty:netty-codec-base:4.2.21.Final
- **virtual threads**: `java.nio.channels`, with no library
- **Ox**: com.softwaremill.ox::core:1.0.2
- **Kotlin coroutines**: ktor-network on kotlinx.coroutines (see `io-bench/stacks/kotlin`)
- **fs2-io, Scala.js**: co.fs2::fs2-io::3.16.0, org.typelevel::cats-effect::3.7.1, built to JavaScript
- **fs2-io, Scala.js on Wasm**: co.fs2::fs2-io::3.16.0, org.typelevel::cats-effect::3.7.1, built to WebAssembly
- **JDK selector**: `java.nio.channels`, with no library
- **fs2-io**: co.fs2::fs2-io:3.16.0, org.typelevel::cats-effect:3.7.1
- **Go**: go version go1.26.5 darwin/arm64
- **Node.js**: Node.js v26.5.0
- **Tokio**: tokio 1.54.1, rustc 1.90.0 (1159e78c4 2025-09-14)

## Reading it

The kqueue backend's column was measured again, by itself, the median of three processes: the run this table is of
linked it with Scala Native's release, 0.5.12, not the nightly it asked for (see the end), and had it at 9.00, 11.13,
2.71, 3.10 and 1.50 on one thread and 5.90, 7.77, 0.95, 1.20 and 1.88 on four.

One process for each column, with other programs at work on the machine: differences of a tenth or so are not to be
relied on. `tcp-concurrent` is the echo of 64 bytes, for what a round trip costs with no work.

- **Direct style on the JVM.** The gears NIO backend on its pool is beside the JDK's virtual threads on blocking
  channels and Ox over the same: 9.7 to 9.8 us a round trip against 10.2 to 10.3 and 9.1 to 9.9 on one thread, and
  ahead on four, 6.3 to 6.5 against 9.2 to 9.7 and 12.3 to 13.1. With its futures on the JDK's own threads it is the
  slowest of them, 12.5 to 13.4 and 9.7 to 10.1. Kotlin's coroutines over ktor-network take 15.5 to 18.1 on one thread.
  The stacks written with callbacks or a hand-made loop, Netty and the JDK selector, are at 6.3 to 8.5.
- **Scala Native.** The work alone takes the kqueue backend 1.41 us, where the same Scala takes 0.13 to 0.18 us on the
  JVM and Go and Rust take 0.18 to 0.22: with requests sent 16 together on one thread it is the work that shows, 2.4 us
  a request where the JVM's backends take 0.9. A round trip is the quickest of the stacks that suspend, 7.6 us on one
  thread, and on four the quickest of any, 3.7 to 3.95.
- **Node.js.** The gears backend takes 15 to 20 us a round trip where plain Node.js takes 9 to 12, and 3.5 to 3.8 us
  a request sent together where it takes 1.6; fs2-io for Scala.js takes 230 to 300 us and 18 to 21.
- **Gathering** an answer's parts changes little anywhere: within a tenth of copying them into one buffer on every
  stack but the kqueue backend on four threads (1.20 against 0.95) and Ox on four (0.95 against 1.39), which were not
  run again.

## The nightly that was not

The gears kqueue backend is linked with a Scala Native nightly, asked for with `SCALA_NATIVE_NIGHTLY=1`, which sbt
reads as a JVM of its starts. `sbt` alone is a thin client of whatever server is running for the directory, and an
IDE had one running, started without the variable: the bench was linked with the release, 0.5.12, saying nothing, for
this run and for an unknown number before it. On the release the echo over 64 connections on four threads failed in 12
runs of 40, a fault in the runtime as a continuation is suspended, an op found entered twice, or a hang; on the
nightly (`0.5.13-20261114-143517b`) in none of 40. The runner now starts sbt as a JVM of its own (`sbt --server`),
and the build refuses to link the bench with the release.
