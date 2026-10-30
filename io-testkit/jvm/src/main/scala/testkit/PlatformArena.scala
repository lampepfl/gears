package gears.async.io
package testkit

import java.lang.foreign.Arena
import java.nio.ByteBuffer

/** The JVM's arenas: off-heap memory from a shared foreign memory arena, which any thread may use, as a future moving
  * between threads needs, and which frees it all at once on close.
  */
private[testkit] object PlatformArena {
  def open(): BufferArena = new BufferArena {
    private val arena = Arena.ofShared()

    def allocate(capacity: Int): ByteBuffer = {
      require(capacity > 0, "Buffer capacity must be positive")
      arena.allocate(capacity.toLong).asByteBuffer()
    }

    def close(): Unit = arena.close()
  }
}
