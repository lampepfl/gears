# io-bench: the session cache driven by redis-benchmark

The figures are in `2026-11-19-redis-benchmark.csv`, as `io-bench/redis/run.sh` printed them (see
`io-bench/redis/README.md`): requests a second and the latency's percentiles, for `SET`, `GET` and `INCR` with 64
connections and values of 512 bytes, a request at a time and 16 together. One run of each server.

- gears: `127f5d0e`
- machine: Apple M3 Max, macOS 27.2.1
- date: 2026-11-19
- Redis and `redis-benchmark`: 8.10.3
- Node.js: v26.5.0, for plain Node.js and for the gears backend on it (Scala.js 1.22.0, on WebAssembly)
- Scala Native, for the gears kqueue backend: `0.5.13-20261114-143517b-SNAPSHOT`, a release build, checked as it was
  linked
- JVM, for the gears NIO backends: openjdk 25.0.2
