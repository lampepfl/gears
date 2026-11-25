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

## The realistic scenarios

`tcp-realistic`, `tcp-realistic-bulk`, `tcp-realistic-gather` and `work-realistic`, as `../README.md` has them.
`Cache.scala` is a copy, unchanged, of the source the other Scala stacks run (`../fs2-js/Cache.scala`, from
`io-bench/shared`): the cache, its client and RESP, over arrays of bytes.

- One inbound handler (`Values`) on each end copies every `ByteBuf` read into an array of its own and releases it;
  at `channelReadComplete` it finds the whole RESP values there (`Cache.Resp.end`) and keeps the beginning of one that
  has not all come. So "what came together" is one turn of Netty's read loop, which may be several reads of the socket
  (its receive buffer begins at 2048 bytes and adapts), where the reference has one `data` event.
- The cache's work is on arrays, so bytes are copied from a `ByteBuf` into an array on the way in, and from an array
  into a buffer of the channel's allocator for each write.
- `tcp-realistic`: the server writes each answer as a buffer of its own, and flushes when the values that came are
  answered. Its client is the client of the bulk scenarios with a batch of one.
- `tcp-realistic-bulk`: the answers go into one array and out as one buffer, with `writeAndFlush`.
- `tcp-realistic-gather`: the server keeps each value as a direct buffer of the channel's allocator (released when it
  is replaced, and all of them when the connection closes). An answer is written as its parts: a small buffer for the
  line of its length, a `retainedDuplicate` of the value's buffer, and a duplicate of a constant line's end; the parts
  of every answer that came together are flushed once, and the NIO channel writes them with one gathering write.
  Counts are kept as numbers, as the reference's gathering cache keeps them.
- `work-realistic` runs on the main thread, with no Netty in it.
