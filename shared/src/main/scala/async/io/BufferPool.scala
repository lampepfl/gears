package gears.async.io

import java.nio.ByteBuffer

/** Buffers a backend fills for the reads into its own buffers, opened by `Backend.pool`. A pool may be used from any
  * thread. `bufferSize` is the most a read into it receives at once: a backend that allocates the pool's buffers fills
  * them, where one whose facility hands it chunks of its own, as Node's streams do, may hand such a chunk over as the
  * `Received` instead, so long as it is no larger. `buffers` bounds how many may be held at once, by pending reads and
  * unreleased `Received`s together, which makes the pool a window on the source: a backend that controls the source's
  * flow may stop taking bytes while none is free, so that a read waits rather than fails. `close` frees its buffers,
  * and must come once no read is pending on it and every `Received` from it has been released.
  */
trait BufferPool extends AutoCloseable {
  def bufferSize: Int
  def buffers: Int

  /** Buffers neither held by a pending read nor by an unreleased `Received`. */
  def available: Int

  def close(): Unit
}

/** Bytes a pooled read received: `buffer` is one of the pool's, positioned at 0 with its limit at the bytes received, 0
  * at the end of the stream. It is the caller's until `release` gives it back to the pool, which must happen exactly
  * once, from any thread; the `Received` is not to be used after.
  */
trait Received {
  def buffer: ByteBuffer
  def release(): Unit
}
