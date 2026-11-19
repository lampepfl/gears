# io-bench/stacks/loom

The TCP scenarios of `../README.md`, and the realistic ones, on the JDK's virtual threads and nothing else: the
JDK's channels in blocking mode, a virtual thread for each side of a connection, started and joined by hand. A thread
that would block is parked by the JDK, which polls for it. `--workers` is the number of carrier threads the virtual
threads run on (`jdk.virtualThreadScheduler.parallelism`).

`BlockingScenarios.scala` is the scenarios as blocking code, which `../ox` runs too; `Cache.scala` is the session
cache, its client and the protocol, the same source as `CapBench`'s (`io-bench/shared`), copied. Channel I/O is on
buffers over the arrays the cache works on, which the JDK copies through a direct buffer of its own; the echo and the
stream use direct buffers. There are no UDP scenarios.

From the root of the repository (needs `scala-cli`, and a JDK of 21 or later on the `PATH`):

```
scala-cli run io-bench/stacks/loom -- --workers 4
```
