# io-bench/stacks/netty

The scenarios of `../README.md` over Netty 4.2 on its NIO transport: one event loop group of `--workers` threads for
servers and clients alike, pipelines of handlers, and the default allocator (pooled, direct buffers).

From the root of the repository:

```
scala-cli run io-bench/stacks/netty -- --workers 4
```

with the arguments of `../README.md`. It runs on whatever `java` is on the path.

- TCP ping-pong: a `FixedLengthFrameDecoder` on each end makes whole messages; the server writes each back, the client
  writes its next when one has come.
- `tcp-stream`: each chunk is written when the write before it has completed; the server allows half closure, and
  reads into a buffer of one chunk (`FixedRecvByteBufAllocator`).
- `tcp-connect`: Netty closes a connection whose end it has read, so the next connect begins when the last
  connection's close future completes.
- UDP: the sockets are bound within the time measured, and closed outside it.
