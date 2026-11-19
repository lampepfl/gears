# io-bench/stacks/fs2

The scenarios of `../README.md` on [fs2-io](https://fs2.io) 3.16.0 and Cats Effect 3.7.1: `Network[IO]` sockets as
`Resource`s, a fiber for each side of a pair. `--workers` is the number of threads in the Cats Effect compute pool,
whose workers also poll the `Selector`s the sockets wait on.

From the root of the repository (needs `scala-cli`, and a JDK on the `PATH`):

```
scala-cli run io-bench/stacks/fs2 -- --workers 4
```

All seven scenarios are run; `udp-connected` uses `DatagramSocket.connect`.

The realistic scenarios are those of `../fs2-js`, the same code: `Cache.scala` is the session cache, its client and
the protocol, the same source as `CapBench`'s, copied. fs2 has no gathering write, so `tcp-realistic-gather` puts an
answer's chunks together (`++`) and writes that.
