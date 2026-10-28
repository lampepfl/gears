package gears.async.io
package nio

import gears.async.io.internal.FreeListPool

import java.nio.ByteBuffer

/** A `BufferPool` of direct buffers, for NIO: one allocation sliced into `buffers` of `bufferSize` bytes, with the free
  * list of `FreeListPool`, since the pool is shared by the reactors' threads and the virtual threads that take the fast
  * path, and a `Received` may be released from any of them.
  */
final class DirectPool(bufferSize: Int, buffers: Int) extends FreeListPool(bufferSize, buffers) {
  private val memory = ByteBuffer.allocateDirect(bufferSize * buffers)

  type S = Slot

  fill(i => new Slot(i, memory.slice(i * bufferSize, bufferSize)))

  protected def freeMemory(): Unit = () // the JDK frees it once the pool is unreachable
}
