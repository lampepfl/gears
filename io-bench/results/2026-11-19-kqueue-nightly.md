# io-bench: this branch against `origin/async-io-backends`

- this branch: `bd79c7f5`, with uncommitted changes
- `origin/async-io-backends`: `9bbd2340`
- machine: Apple M3 Max, Darwin 27.2.0
- date: 2026-11-19

Microseconds per op, lower is better. A dash is a scenario the backend has no capability for.

## Scala Native, kqueue

Release builds on Scala Native 0.5.13-20261114-143517b-SNAPSHOT, as each build resolved it, with the number of workers. Each configuration is run 3 times at scale 1.0: the median.

| scenario | this: kqueue, 1 | other: kqueue, 1 | this: kqueue, 4 | other: kqueue, 4 |
|---|---:|---:|---:|---:|
| pipe-pingpong | 2.66 | 2.78 | 7.90 | 8.04 |
| pipe-concurrent | 2.41 | 2.52 | 1.23 | 1.80 |
| pipe-stream | 5.15 | 5.85 | 5.23 | 6.11 |
| tcp-pingpong | 19.18 | 19.18 | 19.14 | 19.68 |
| tcp-concurrent | 7.39 | 7.25 | 4.41 | 3.99 |
| tcp-stream | 6.63 | 5.87 | 6.90 | 5.92 |
| tcp-connect | 54.83 | 58.85 | 59.01 | 57.01 |
| tcp-realistic | 9.85 | 9.57 | 4.78 | 4.51 |
| tcp-realistic-bulk | 2.55 | 2.48 | 0.69 | 0.75 |
| tcp-realistic-gather | 2.78 | 2.79 | 0.81 | 0.87 |
| work-realistic | 1.53 | 1.53 | 1.55 | 1.55 |
| unix-pingpong | 2.76 | 2.88 | 2.74 | 8.43 |
| unix-concurrent | 2.63 | 2.72 | 1.17 | 1.16 |
| unix-stream | 20.84 | 25.33 | 20.93 | 36.72 |
| unix-connect | 8.35 | 10.07 | 8.42 | 20.31 |
| udp-pingpong | 26.60 | 28.89 | 26.72 | 28.36 |
| udp-concurrent | 15.33 | 14.18 | 6.31 | 9.43 |
| udp-connected | 21.68 | 21.07 | 21.21 | 22.15 |
| udg-pingpong | 8.44 | 7.64 | 8.47 | 13.79 |
| udg-concurrent | 8.42 | 7.50 | 3.32 | 3.08 |
| fifo-pingpong | 4.92 | 10.54 | 9.98 | 10.25 |
| file-read | 4.26 | 8.43 | 4.17 | 8.52 |
| file-write | 12.96 | 13.84 | 12.46 | 13.74 |
| file-concurrent | 1.83 | 1.22 | 1.16 | 1.23 |
| file-open | 20.91 | 24.54 | 20.93 | 29.64 |
| blocking | 4.36 | 7.76 | 4.50 | 7.82 |
| blocking-concurrent | 2.06 | 2.18 | 0.80 | 1.28 |
| resolve | 1.13 | 122.80 | 1.07 | 122.31 |
| timer-zero | 1.07 | 12.72 | 1.06 | 13.02 |
| timers | 1.16 | 1.13 | 1.25 | 1.16 |
