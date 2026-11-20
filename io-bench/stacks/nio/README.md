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

## The realistic scenarios

`tcp-realistic`, `tcp-realistic-bulk`, `tcp-realistic-gather` and `work-realistic` are here too. `Cache.scala` is a
copy, unchanged, of `../fs2-js/Cache.scala`, itself the source of `CapBench`'s cache, its client and the protocol
(`io-bench/shared`): the work is the same source every Scala stack runs, over arrays of bytes.

Where it differs from the Node.js reference:

- The channels read into and write from direct buffers, and the protocol is read and written in arrays, so the bytes
  of every read and every write are copied once between the two (in `tcp-realistic-gather`, on the server, only what
  is read: the requests are kept in a direct buffer and in an array beside it, as `CapBench` has it).
- One server serves `tcp-realistic` and `tcp-realistic-bulk`: it answers every whole command a read brought, in one
  write, which with one request sent at a time is an answer for each command. It reads again only once its answers
  are all written.
- `tcp-realistic-gather` keeps each value in a direct buffer of its own, used again when the key is set to a value
  that fits in it, and writes the answers with `SocketChannel.write(ByteBuffer[], int, int)`; a write the socket takes
  part of goes on from the first buffer with bytes left once the channel can be written to.
- The buffers are of a fixed size, 2048 bytes for each request that can be in flight, where Node.js joins what is left
  of one read to the next.
