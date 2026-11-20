# io-bench/redis

The bench's session cache as a server of its own, driven by `redis-benchmark`, beside Redis itself: one client, not
this repository's, for every server, and the figures Redis's own tool reports, percentiles of latency among them.

The cache speaks enough of Redis's protocol for it: `SET`, `GET` and `INCR`, with one table for every connection, as
Redis has. Each program serves it with `--serve <port>`, answering every request a read brought in one write:

- the gears bench (`CapBench`, `io-bench/shared`), over every gears backend: Node.js, kqueue, and NIO on a pool and on
  the JDK's threads;
- the plain Node.js program (`io-bench/stacks/node/bench.mjs`).

`run.sh` starts each in turn, and Redis, and drives it twice with 64 connections and values of 512 bytes: a request
at a time, and 16 together (`-P 16`). It prints a line of CSV for each of `SET`, `GET` and `INCR`: requests a second,
and the latency at the median, the 95th and the 99th percentile, in milliseconds.

```
SCALA_NATIVE_NIGHTLY=1 sbt --server 'ioCapBenchJS/fullLinkJS; ioCapBenchJVM/compile; ioCapBenchNative/nativeLink'
io-bench/redis/run.sh > io-bench/results/<name>.csv
```

`redis-benchmark` asks a server for its `CONFIG` as it starts, which these answer with an error: it says so and goes
on. It has one thread, as run here, and is itself the limit against the fastest servers: Redis's own figures show
what the client can drive.
