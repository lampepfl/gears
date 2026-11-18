# io-bench: kqueue on this branch against `origin/async-io-backends`, after the stealing changes

- this branch: `08812c55`
- `origin/async-io-backends`: `9bbd2340`
- machine: Apple M3 Max, Darwin 27.2.0
- date: 2026-11-18

Microseconds per op, lower is better. Scala Native only: the changes since `2026-11-18-after-tuning.md` are to how a pool's workers share tasks, which the JVM backends were not measured for again.

Release builds on the Scala Native nightly, with the number of workers. The two binaries were run in turn, not through `Compare`, each configuration 5 times at scale 1.0: the median.

| scenario | this: kqueue, 1 | other: kqueue, 1 | this: kqueue, 4 | other: kqueue, 4 |
|---|---:|---:|---:|---:|
| pipe-pingpong | 2.92 | 3.06 | 8.10 | 8.22 |
| pipe-concurrent | 2.70 | 2.76 | 1.41 | 1.86 |
| pipe-stream | 5.30 | 5.93 | 5.24 | 9.06 |
| tcp-pingpong | 19.43 | 19.76 | 19.27 | 19.88 |
| tcp-concurrent | 7.46 | 7.41 | 3.77 | 3.80 |
| tcp-stream | 6.80 | 5.95 | 6.54 | 5.83 |
| tcp-connect | 57.98 | 58.58 | 58.09 | 56.20 |
| unix-pingpong | 2.98 | 3.17 | 3.01 | 8.53 |
| unix-concurrent | 2.83 | 2.88 | 1.30 | 1.27 |
| unix-stream | 22.27 | 27.58 | 22.45 | 29.24 |
| unix-connect | 8.76 | 11.15 | 8.87 | 21.80 |
| udp-pingpong | 27.46 | 28.68 | 26.95 | 28.56 |
| udp-concurrent | 15.83 | 14.88 | 6.31 | 9.84 |
| udp-connected | 21.84 | 22.31 | 21.94 | 22.73 |
| udg-pingpong | 9.14 | 8.74 | 9.30 | 14.79 |
| udg-concurrent | 9.08 | 8.25 | 3.72 | 3.39 |
| fifo-pingpong | 5.08 | 10.74 | 9.77 | 9.86 |
| file-read | 4.37 | 8.40 | 4.34 | 8.58 |
| file-write | 12.93 | 13.69 | 12.68 | 13.82 |
| file-concurrent | 1.65 | 1.63 | 1.19 | 1.15 |
| file-open | 20.23 | 24.60 | 21.12 | 30.03 |
| blocking | 4.27 | 8.31 | 4.37 | 8.60 |
| blocking-concurrent | 1.55 | 1.40 | 0.94 | 0.91 |
| resolve | 0.98 | 118.83 | 1.01 | 116.84 |
| timer-zero | 1.11 | 12.91 | 1.15 | 13.14 |
| timers | 1.15 | 1.14 | 1.17 | 1.23 |

## Notes

- `pipe-pingpong` on four workers has two outcomes on this branch, about 3 and about 8, by whether a worker with nothing to do takes one future of the pair as they are spawned: most runs here are the second, where `after-tuning` recorded the first. It was so before these changes too.
- `tcp-stream` follows how full the socket's buffer is kept, on either branch: about 9.5 with it near empty, 6.4 to 7 around half full, 5.5 near full. The other branch's writer runs until the buffer is full more often.
- The datagram scenarios on one worker are behind by a receive that would block before each wait. The other branch skips it by counting the bytes received against those kqueue reported, which would leave an empty packet behind others unreceived until the next arrives.
