package gears.async.io
package reference

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** A pool of direct buffers for pooled reads, lent one at a time from a free list. */
final class RefPool(val bufferSize: Int, val buffers: Int) extends BufferPool {
  require(bufferSize > 0 && buffers > 0, s"A pool has buffers of some size: $buffers of $bufferSize bytes")
  private val free = scala.collection.mutable.Stack.fill(buffers)(ByteBuffer.allocateDirect(bufferSize))
  private var closed = false

  def available: Int = synchronized(free.size)

  /** A free buffer; fails if there is none, or the pool is closed. */
  private[reference] def take(): ByteBuffer = synchronized {
    if closed then throw new IOException("The pool is closed")
    if free.isEmpty then throw new IOException(s"The pool has no buffer free: all $buffers are held")
    free.pop()
  }

  private[reference] def give(buffer: ByteBuffer): Unit = synchronized(free.push(buffer): Unit)

  private[reference] def received(filled: ByteBuffer): Received = new Received {
    private val released = new AtomicBoolean()
    def buffer: ByteBuffer = filled
    def release(): Unit = {
      if !released.compareAndSet(false, true) then throw new IllegalStateException("Released already")
      give(filled)
    }
  }

  def close(): Unit = synchronized {
    if !closed then {
      if free.size < buffers then
        throw new IllegalArgumentException(s"${buffers - free.size} of the pool's buffers are still held")
      closed = true
    }
  }
}
