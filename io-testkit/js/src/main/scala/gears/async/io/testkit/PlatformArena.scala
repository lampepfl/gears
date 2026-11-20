package gears.async.io
package testkit

import java.nio.ByteBuffer

/** Scala.js's arenas: direct buffers, which the garbage collector frees once unused, so closing frees nothing. */
private[testkit] object PlatformArena {
  def open(): BufferArena = new BufferArena {
    def allocate(capacity: Int): ByteBuffer = {
      require(capacity > 0, "Buffer capacity must be positive")
      ByteBuffer.allocateDirect(capacity)
    }

    def close(): Unit = ()
  }
}
