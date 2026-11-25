# io-bench/stacks/ox

The scenarios of `../loom`, the same blocking code (`../loom/BlockingScenarios.scala`), on
[Ox](https://ox.softwaremill.com) 1.0.2: each side of a connection is a fork of a supervised scope, which ends when
every fork has and fails with the first that fails. Ox's forks are the JDK's virtual threads, and it has no sockets
of its own, so the I/O is the JDK's blocking channels as in `../loom`: what differs is how the threads are started
and waited for. `--workers` is the number of carrier threads.

From the root of the repository (needs `scala-cli`, and a JDK of 21 or later on the `PATH`):

```
scala-cli run io-bench/stacks/ox -- --workers 4
```
