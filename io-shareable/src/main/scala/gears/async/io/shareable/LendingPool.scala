package gears.async.io.shareable

import gears.async.io.BufferPool
import gears.async.io.Received

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** A pool that lends its buffers one at a time from a free list, to pooled reads and, through a `Received`, to their
  * callers. The memory behind the buffers is the subclass's: `allocate` makes each buffer, once, and `free` gives the
  * memory back as the pool closes.
  */
abstract class LendingPool(val bufferSize: Int, val buffers: Int) extends BufferPool {
  require(bufferSize > 0 && buffers > 0, s"A pool has buffers of some size: $buffers of $bufferSize bytes")

  /** A buffer of `bufferSize` bytes the kernel can address. */
  protected def allocate(): ByteBuffer

  /** Frees what `allocate` took, all of it. */
  protected def free(): Unit

  /** Guarded by this pool's monitor, as `closed` is. Filled on first use: `allocate` is the subclass's, which is not
    * yet made as this is.
    */
  private var freeList: Array[ByteBuffer] | Null = null
  private var freeCount = buffers
  private var closed = false

  final def available: Int = synchronized(if closed then 0 else freeCount)

  /** A free buffer, cleared; fails if there is none, or the pool is closed. */
  final def take(): ByteBuffer = synchronized {
    if closed then throw new IOException("The pool is closed")
    if freeCount == 0 then throw new IOException(s"The pool has no buffer free: all $buffers are held")
    var list = freeList
    if list == null then
      list = Array.fill(buffers)(allocate())
      freeList = list
    freeCount -= 1
    val buffer = list(freeCount)
    buffer.clear()
    buffer
  }

  /** Gives back a buffer `take` gave, which no `Received` holds. */
  final def give(buffer: ByteBuffer): Unit = synchronized {
    freeList.nn(freeCount) = buffer
    freeCount += 1
  }

  /** `filled`, a buffer `take` gave, as the caller's until released. */
  final def received(filled: ByteBuffer): Received = new Received {
    private val released = new AtomicBoolean()
    def buffer: ByteBuffer = filled
    def release(): Unit = {
      if !released.compareAndSet(false, true) then throw new IllegalStateException("Released already")
      give(filled)
    }
  }

  final def close(): Unit = synchronized {
    if !closed then
      if freeCount < buffers then
        throw new IllegalArgumentException(s"${buffers - freeCount} of the pool's buffers are still held")
      closed = true
      freeList = null
      free()
  }
}
