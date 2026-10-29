package gears.async.io.internal

import gears.async.io.{Received, Slot}

import java.io.IOException
import java.nio.ByteBuffer

/** Buffer ownership for one synchronous read attempt, independent of readiness registration and operation completion. A
  * read returns 0 if it would block, -1 at EOF, or the number of bytes transferred. Success (including EOF) publishes a
  * flipped buffer to the caller; every other exit returns it to the pool. The caller must provide an empty slot.
  */
private[io] object PooledBufferRead {
  inline def attempt(pool: FreeListPool, into: Slot[Received])(inline read: ByteBuffer => Int): Int = {
    val buffer = pool.take()
    if buffer == null then throw new IOException("The pool has no buffer free")
    var published = false
    try {
      val count = read(buffer.nn.buffer)
      if count != 0 then {
        buffer.nn.buffer.flip()
        into.set(buffer.nn)
        published = true
      }
      count
    } finally if !published then buffer.nn.release()
  }
}
