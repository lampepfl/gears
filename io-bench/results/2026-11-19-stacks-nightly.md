# io-bench: the IP socket scenarios across stacks

- gears: `127f5d0e`, with uncommitted changes
- machine: Apple M3 Max, Darwin 27.2.0
- JVM: openjdk version "25.0.2" 2026-01-20 LTS
- date: 2026-11-19

Microseconds per op, lower is better. A dash is a scenario the stack was not run for, or has no way to do. Each column is one process that runs the scenarios 5 times at scale 1.0: the median of the last 3. What each scenario does is in `io-bench/stacks/README.md`.

## 1 thread, and the stacks that have only one

| scenario | gears kqueue | gears NIO pool | gears NIO threads | gears on Node.js | Netty | virtual threads | Ox | Kotlin coroutines | JDK selector | fs2-io | Go | Node.js | Tokio |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| tcp-pingpong | 19.20 | 19.61 | 32.39 | 25.96 | 19.34 | 25.34 | 25.74 | 34.65 | 16.71 | 23.09 | 18.28 | 20.93 | 18.82 |
| tcp-concurrent | 7.46 | 7.69 | 10.33 | 13.07 | 6.30 | 8.57 | 8.52 | 10.62 | 6.58 | 13.75 | 6.42 | 9.12 | 6.76 |
| tcp-stream | 6.35 | 7.16 | 6.13 | 20.04 | 6.20 | 6.76 | 6.97 | 25.93 | 7.21 | 13.48 | 6.61 | 14.90 | 6.95 |
| tcp-connect | 57.17 | 76.20 | 102.52 | 82.26 | 75.01 | 74.05 | 73.60 | 79.26 | 63.29 | 94.34 | 57.92 | 62.79 | 53.79 |
| udp-pingpong | 26.42 | 27.73 | 39.69 | 35.66 | 25.95 | – | – | – | 25.88 | 30.65 | 26.42 | 30.23 | 26.01 |
| udp-concurrent | 15.16 | 14.09 | 12.21 | 24.81 | 13.49 | – | – | – | 12.61 | 21.84 | 12.98 | 19.52 | 13.24 |
| udp-connected | 21.18 | 18.88 | 26.06 | 27.95 | 17.25 | – | – | – | 17.66 | 24.88 | 16.17 | 27.06 | 16.56 |
| tcp-realistic | 9.73 | 7.99 | 10.72 | 17.34 | 7.48 | 9.39 | 9.08 | 11.29 | 7.84 | 15.03 | 6.91 | 11.45 | 6.64 |
| tcp-realistic-bulk | 2.54 | 0.74 | 0.73 | 3.16 | 0.68 | 0.81 | 0.99 | 0.94 | 0.81 | 1.23 | 0.71 | 1.56 | 0.78 |
| tcp-realistic-gather | 2.82 | 0.79 | 0.68 | 3.24 | 0.80 | 0.85 | 0.96 | 0.96 | 0.89 | 1.23 | 0.74 | 1.60 | 0.78 |
| work-realistic | 1.54 | 0.13 | 0.12 | 1.15 | 0.13 | 0.11 | 0.12 | 0.12 | 0.20 | 0.13 | 0.18 | 0.69 | 0.22 |

## 4 threads

| scenario | gears kqueue | gears NIO pool | gears NIO threads | Netty | virtual threads | Ox | Kotlin coroutines | fs2-io | Go | Tokio |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| tcp-pingpong | 18.77 | 19.17 | 31.90 | 19.75 | 25.63 | 26.16 | 56.80 | 26.24 | 18.49 | 18.51 |
| tcp-concurrent | 4.47 | 3.75 | 6.72 | 4.20 | 5.79 | 6.55 | 8.15 | 5.66 | 4.96 | 5.23 |
| tcp-stream | 6.70 | 7.25 | 6.61 | 6.10 | 6.65 | 6.43 | 31.92 | 9.19 | 5.84 | 8.60 |
| tcp-connect | 58.45 | 75.55 | 100.89 | 83.18 | 72.46 | 73.18 | 89.15 | 89.17 | 58.71 | 57.46 |
| udp-pingpong | 26.52 | 27.71 | 39.03 | 24.70 | – | – | – | 34.40 | 27.68 | 27.24 |
| udp-concurrent | 6.98 | 5.21 | 6.55 | 5.23 | – | – | – | 8.42 | 5.92 | 6.39 |
| udp-connected | 21.43 | 21.13 | 27.21 | 17.13 | – | – | – | 25.31 | 16.07 | 16.26 |
| tcp-realistic | 4.78 | 3.96 | 6.71 | 4.07 | 6.80 | 5.90 | 8.18 | 6.42 | 5.07 | 4.14 |
| tcp-realistic-bulk | 0.69 | 0.27 | 0.45 | 0.22 | 0.51 | 0.49 | 0.54 | 0.44 | 0.30 | 0.36 |
| tcp-realistic-gather | 0.79 | 0.28 | 0.44 | 0.32 | 0.51 | 0.43 | 0.53 | 0.43 | 0.27 | 0.32 |
| work-realistic | 1.56 | 0.12 | 0.12 | 0.12 | 0.11 | 0.12 | 0.12 | 0.12 | 0.17 | 0.22 |

## The stacks

- **gears kqueue**: `EventPollingBackend`, Scala Native 0.5.13-20261114-143517b-SNAPSHOT, release build
- **gears NIO pool**: `NioPoolBackend`, with `--add-opens java.base/java.lang=ALL-UNNAMED`
- **gears NIO threads**: `NioBackend`, its futures on the JDK's virtual threads
- **gears on Node.js**: `NodeBackend`, Scala.js on WebAssembly with JSPI, Node.js v26.5.0
- **Netty**: io.netty:netty-codec-base:4.2.21.Final
- **virtual threads**: `java.nio.channels`, with no library
- **Ox**: com.softwaremill.ox::core:1.0.2
- **Kotlin coroutines**: ktor-network on kotlinx.coroutines (see `io-bench/stacks/kotlin`)
- **JDK selector**: `java.nio.channels`, with no library
- **fs2-io**: co.fs2::fs2-io:3.16.0, org.typelevel::cats-effect:3.7.1
- **Go**: go version go1.26.5 darwin/arm64
- **Node.js**: Node.js v26.5.0
- **Tokio**: tokio 1.54.1, rustc 1.90.0 (1159e78c4 2025-09-14)
