# io-bench: this branch against `origin/async-io-backends`

- this branch: `08812c55`
- `origin/async-io-backends`: `9bbd2340`
- machine: Apple M3 Max, Darwin 27.2.0
- date: 2026-11-18

Microseconds per op, lower is better. A dash is a scenario the backend has no capability for.

## JVM

openjdk version "25.0.2" 2026-01-20 LTS. `threads` is `NioBackend` on four selector workers and `pool` is `NioPoolBackend`, with the number of workers; `caller` is the other branch's `NioOnCaller` and `reactors` its `NioBackend`. Each configuration is one JVM that runs the scenarios 5 times at scale 0.5: the median of the last 3.

| scenario | this: threads | other: caller | this: pool, 1 | other: reactors, 1 | this: pool, 4 | other: reactors, 4 |
|---|---:|---:|---:|---:|---:|---:|
| pipe-pingpong | 5.15 | 5.31 | 3.05 | 2.98 | 2.94 | 10.88 |
| pipe-concurrent | 7.18 | 6.47 | 3.16 | 2.96 | 2.46 | 2.72 |
| pipe-stream | 19.17 | 20.90 | 5.82 | 5.60 | 5.63 | 10.97 |
| tcp-pingpong | 34.50 | 26.13 | 21.47 | 21.19 | 20.82 | 21.92 |
| tcp-concurrent | 6.64 | 10.46 | 8.80 | 7.82 | 4.43 | 5.31 |
| tcp-stream | 6.15 | 6.83 | 8.07 | 7.74 | 7.24 | 10.60 |
| tcp-connect | 100.04 | 83.19 | 75.73 | 74.91 | 75.14 | 73.98 |
| unix-pingpong | 5.26 | 5.79 | 3.06 | 3.09 | 2.90 | 4.25 |
| unix-concurrent | 7.10 | 6.47 | 3.22 | 3.08 | 1.91 | 2.47 |
| unix-stream | 12.37 | 18.89 | 22.80 | 21.55 | 22.47 | 24.84 |
| unix-connect | 27.21 | 14.86 | 16.69 | 17.07 | 15.84 | 20.05 |
| udp-pingpong | 40.54 | 43.05 | 26.87 | 26.96 | 26.62 | 28.16 |
| udp-concurrent | 7.66 | 12.20 | 15.13 | 14.93 | 6.03 | 9.51 |
| udp-connected | 33.24 | 29.04 | 22.30 | 21.71 | 22.43 | 22.04 |
| file-read | 0.39 | 0.37 | 9.98 | 10.13 | 9.78 | 10.44 |
| file-write | 4.23 | 4.56 | 15.53 | 15.29 | 14.62 | 14.76 |
| file-concurrent | 1.10 | 1.10 | 0.92 | 1.03 | 1.41 | 0.90 |
| file-open | 11.29 | 12.60 | 24.89 | 27.68 | 23.53 | 30.24 |
| blocking | 0.09 | 0.09 | 9.82 | 9.90 | 9.78 | 11.24 |
| blocking-concurrent | 0.01 | 0.01 | 0.94 | 1.68 | 0.70 | 0.77 |
| resolve | 0.20 | 0.88 | 0.20 | 12.46 | 0.22 | 11.66 |
| timer-zero | 0.53 | 0.35 | 1.12 | 12.74 | 1.09 | 13.71 |
| timers | 1.53 | 1.16 | 2.01 | 1.77 | 1.50 | 1.32 |
