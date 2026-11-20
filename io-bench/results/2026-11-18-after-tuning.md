# io-bench: this branch against `origin/async-io-backends`

- this branch: `dbdb2b80`
- `origin/async-io-backends`: `9bbd2340`
- machine: Apple M3 Max, Darwin 27.2.0
- date: 2026-11-18

> **Scala Native's version was not checked for these figures.** They were meant to be of a nightly, but the build may
> have been linked with the release, 0.5.12, which sbt falls back to when it is not given the environment that asks for
> one (see `2026-11-19-realistic-stacks.md`, at its end). The kqueue figures are superseded by
> `2026-11-19-kqueue-nightly.md` and `2026-11-19-stacks-nightly.md`, which name the version they were linked with.

Microseconds per op, lower is better. A dash is a scenario the backend has no capability for.

## JVM

openjdk version "25.0.2" 2026-01-20 LTS. `threads` is `NioBackend` on four selector workers and `pool` is `NioPoolBackend`, with the number of workers; `caller` is the other branch's `NioOnCaller` and `reactors` its `NioBackend`. Each configuration is one JVM that runs the scenarios 5 times at scale 0.5: the median of the last 3.

| scenario | this: threads | other: caller | this: pool, 1 | other: reactors, 1 | this: pool, 4 | other: reactors, 4 |
|---|---:|---:|---:|---:|---:|---:|
| pipe-pingpong | 5.13 | 5.37 | 2.85 | 2.88 | 2.92 | 9.24 |
| pipe-concurrent | 7.25 | 6.48 | 2.98 | 2.90 | 1.88 | 1.96 |
| pipe-stream | 16.83 | 20.33 | 5.58 | 5.63 | 5.67 | 9.95 |
| tcp-pingpong | 34.06 | 26.60 | 20.69 | 20.26 | 20.42 | 20.93 |
| tcp-concurrent | 6.76 | 10.44 | 7.80 | 7.52 | 4.36 | 4.51 |
| tcp-stream | 7.14 | 6.87 | 7.04 | 7.74 | 7.02 | 9.78 |
| tcp-connect | 102.44 | 84.77 | 74.31 | 72.05 | 75.15 | 72.17 |
| unix-pingpong | 5.33 | 5.45 | 2.97 | 2.93 | 3.08 | 3.03 |
| unix-concurrent | 7.05 | 6.48 | 3.07 | 3.02 | 2.12 | 1.90 |
| unix-stream | 13.78 | 17.10 | 22.32 | 22.11 | 22.76 | 24.07 |
| unix-connect | 29.21 | 15.19 | 14.91 | 16.36 | 16.28 | 17.63 |
| udp-pingpong | 40.50 | 39.44 | 25.11 | 26.82 | 26.51 | 36.87 |
| udp-concurrent | 8.16 | 12.28 | 14.47 | 14.45 | 6.69 | 10.58 |
| udp-connected | 30.14 | 28.61 | 21.96 | 22.08 | 22.44 | 25.49 |
| file-read | 0.42 | 0.37 | 9.74 | 9.94 | 9.42 | 8.69 |
| file-write | 4.42 | 4.35 | 15.38 | 15.81 | 15.12 | 15.44 |
| file-concurrent | 1.14 | 1.14 | 0.92 | 1.10 | 1.37 | 0.96 |
| file-open | 11.10 | 11.96 | 23.29 | 27.33 | 24.70 | 26.24 |
| blocking | 0.16 | 0.09 | 9.29 | 10.07 | 9.61 | 9.71 |
| blocking-concurrent | 0.02 | 0.01 | 0.90 | 1.67 | 0.58 | 0.92 |
| resolve | 0.20 | 0.97 | 0.20 | 12.40 | 0.22 | 9.56 |
| timer-zero | 0.37 | 0.36 | 1.08 | 12.52 | 1.15 | 12.39 |
| timers | 1.57 | 1.12 | 1.91 | 1.77 | 1.45 | 1.32 |

## Scala Native, kqueue

Release builds on the Scala Native nightly, with the number of workers. Each configuration is run 3 times at scale 1.0: the median.

| scenario | this: kqueue, 1 | other: kqueue, 1 | this: kqueue, 4 | other: kqueue, 4 |
|---|---:|---:|---:|---:|
| pipe-pingpong | 2.91 | 3.19 | 3.17 | 8.72 |
| pipe-concurrent | 2.74 | 2.83 | 2.26 | 2.47 |
| pipe-stream | 5.36 | 6.05 | 5.92 | 9.70 |
| tcp-pingpong | 19.37 | 18.43 | 20.30 | 21.23 |
| tcp-concurrent | 7.76 | 8.65 | 6.64 | 5.62 |
| tcp-stream | 7.44 | 6.92 | 8.04 | 7.22 |
| tcp-connect | 58.80 | 59.54 | 63.14 | 61.43 |
| unix-pingpong | 2.99 | 3.40 | 3.27 | 9.11 |
| unix-concurrent | 2.83 | 3.23 | 2.04 | 1.84 |
| unix-stream | 22.55 | 28.97 | 24.52 | 35.29 |
| unix-connect | 8.72 | 12.02 | 10.06 | 26.33 |
| udp-pingpong | 27.04 | 29.54 | 29.13 | 30.70 |
| udp-concurrent | 15.94 | 17.36 | 11.40 | 12.27 |
| udp-connected | 21.94 | 22.55 | 22.30 | 23.13 |
| udg-pingpong | 9.34 | 9.59 | 10.52 | 16.04 |
| udg-concurrent | 9.29 | 10.05 | 6.96 | 4.00 |
| fifo-pingpong | 5.05 | 14.42 | 11.42 | 11.62 |
| file-read | 4.36 | 8.69 | 4.74 | 9.15 |
| file-write | 12.46 | 14.97 | 13.63 | 15.40 |
| file-concurrent | 1.80 | 1.75 | 1.27 | 1.19 |
| file-open | 20.81 | 25.35 | 22.21 | 33.74 |
| blocking | 4.41 | 9.15 | 4.80 | 9.02 |
| blocking-concurrent | 1.67 | 1.87 | 1.08 | 1.06 |
| resolve | 1.08 | 123.25 | 1.20 | 130.86 |
| timer-zero | 1.11 | 13.66 | 1.24 | 13.65 |
| timers | 1.15 | 1.22 | 1.18 | 1.21 |
