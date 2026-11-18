# io-bench/stacks/nio

The scenarios of `../README.md` over the JDK's `java.nio.channels` and one `Selector`, by hand and on one thread:
non-blocking channels, direct buffers, and a small state machine for each channel, which tries its op first and has
the selector watch the channel only when the op would block.

From the root of the repository:

```
scala-cli run io-bench/stacks/nio
```

with the arguments of `../README.md`; `--workers` is taken and ignored. It runs on whatever `java` is on the path.

The UDP sockets are bound within the time measured, and closed outside it.
