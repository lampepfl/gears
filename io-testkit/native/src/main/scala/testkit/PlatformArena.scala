package gears.async.io
package testkit

import java.nio.ByteBuffer
import scala.scalanative.memory.PointerBuffer
import scala.scalanative.unsafe.*

/** Scala Native's arenas: a `Zone`, whose memory the kernel can address through the buffers' pointers, as the kqueue
  * backend needs (`ByteBuffer.allocateDirect` is heap-backed on Scala Native). A zone is not safe to allocate from on
  * two threads at once, and a program on several workers allocates from several, so allocation takes the arena's lock:
  * without it two buffers could share memory, and a test on a pool of four workers read another's bytes.
  */
private[testkit] object PlatformArena {
  def open(): BufferArena = new BufferArena {
    private val zone = Zone.open()

    def allocate(capacity: Int): ByteBuffer = {
      require(capacity > 0, "Buffer capacity must be positive")
      synchronized(PointerBuffer.wrap(alloc[Byte](capacity)(using zone), capacity))
    }

    def close(): Unit = synchronized(zone.close())
  }
}
