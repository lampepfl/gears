# io-bench: this branch against `origin/async-io-backends`

- this branch: `21ff3b47`
- `origin/async-io-backends`: `9bbd2340`
- machine: Apple M3 Max, Darwin 27.2.0
- date: 2026-11-18

Microseconds per op, lower is better. A dash is a scenario the backend has no capability for.

## JVM

openjdk version "25.0.2" 2026-01-20 LTS. `threads` is `NioBackend` on four selector workers and `pool` is `NioPoolBackend`, with the number of workers; `caller` is the other branch's `NioOnCaller` and `reactors` its `NioBackend`. Each configuration is one JVM that runs the scenarios 5 times at scale 0.5: the median of the last 3.

| scenario | this: threads | other: caller | this: pool, 1 | other: reactors, 1 | this: pool, 4 | other: reactors, 4 |
|---|---:|---:|---:|---:|---:|---:|
| pipe-pingpong | 16.71 | 5.30 | 2.86 | 2.91 | 2.94 | 9.30 |
| pipe-concurrent | 5.54 | 6.90 | 2.98 | 3.03 | 2.08 | 3.28 |
| pipe-stream | 10.22 | 20.22 | 5.41 | 5.54 | 5.63 | 10.01 |
| tcp-pingpong | 33.01 | 26.58 | 20.59 | 20.32 | 20.47 | 20.54 |
| tcp-concurrent | 6.68 | 10.44 | 8.05 | 7.48 | 4.09 | 4.01 |
| tcp-stream | 5.97 | 7.46 | 6.85 | 7.83 | 7.67 | 9.69 |
| tcp-connect | 96.31 | 83.22 | 73.18 | 71.70 | 73.91 | 70.08 |
| unix-pingpong | 17.30 | 5.37 | 2.81 | 2.98 | 2.91 | 2.98 |
| unix-concurrent | 5.45 | 6.46 | 2.98 | 3.04 | 1.79 | 1.68 |
| unix-stream | 20.61 | 18.97 | 21.49 | 21.81 | 22.13 | 22.26 |
| unix-connect | 55.55 | 15.22 | 15.09 | 16.51 | 15.56 | 16.56 |
| udp-pingpong | 38.67 | 41.04 | 27.46 | 26.50 | 27.16 | 45.60 |
| udp-concurrent | 6.48 | 12.17 | 14.46 | 14.43 | 4.93 | 6.49 |
| udp-connected | 25.60 | 29.30 | 19.84 | 21.56 | 21.20 | 43.14 |
| file-read | 0.39 | 0.37 | 9.74 | 9.79 | 9.64 | 8.77 |
| file-write | 4.23 | 4.22 | 14.90 | 15.10 | 14.51 | 15.70 |
| file-concurrent | 1.13 | 1.09 | 0.83 | 1.00 | 1.34 | 0.93 |
| file-open | 11.26 | 11.90 | 22.45 | 27.14 | 22.71 | 26.78 |
| blocking | 0.16 | 0.09 | 9.25 | 9.67 | 9.41 | 9.46 |
| blocking-concurrent | 0.02 | 0.01 | 0.82 | 1.48 | 0.55 | 0.80 |
| resolve | 0.18 | 0.91 | 0.21 | 11.40 | 0.25 | 11.78 |
| timer-zero | 0.45 | 0.40 | 1.05 | 12.28 | 1.05 | 12.18 |
| timers | 1.57 | 1.16 | 1.97 | 1.73 | 1.45 | 1.45 |
