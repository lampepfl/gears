package gears.async.io
package internal

import gears.async.io.BufferPool
import gears.async.io.Received

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A `BufferPool` of `buffers` buffers of `bufferSize` bytes, in memory a backend provides: a `Received` made once per
  * buffer and reused, and a free list that takes no lock, since a `Received` may be released from any thread and a
  * readiness backend takes and releases a buffer on every read: a stack of slot indexes whose head is a compare-and-set
  * word, the index tagged with a count of changes so that a slot released and taken again meanwhile cannot be mistaken
  * for the one seen. A backend's pool makes its slots, of its own kind `S`, with `fill` at the end of its constructor,
  * once its memory is there, and frees that memory in `freeMemory` when the pool closes.
  */
abstract class FreeListPool(val bufferSize: Int, val buffers: Int) extends BufferPool {
  require(bufferSize > 0 && buffers > 0, "A pool needs buffers of some size")

  /** One of the pool's buffers, as the `Received` of a read that filled it. */
  class Slot(val index: Int, val buffer: ByteBuffer) extends Received {

    /** The index of the slot below this one on the free stack, while it is on it. */
    @volatile private[FreeListPool] var next = -1

    /** Whether a read or a `Received` holds this buffer: set by `take`, cleared by `release`, which refuses a second.
      */
    private[FreeListPool] val held = new AtomicBoolean(false)

    def release(): Unit = FreeListPool.this.release(this)
  }

  /** The kind of slot the backend's pool makes. */
  type S <: Slot

  private val slots = new Array[Slot](buffers)

  // The free stack: `head` holds the top slot's index, -1 for none, in its low 32 bits and a change count above.
  private val head = new AtomicLong(FreeListPool.pack(-1, 0))
  private val free = new AtomicInteger(buffers)
  @volatile private var closed = false

  /** Makes the pool's slots with `make` and puts them on the free stack, the first on top: called once, at the end of
    * the backend's pool's constructor.
    */
  protected final def fill(make: Int => S): Unit = {
    for i <- 0 until buffers do slots(i) = make(i)
    (buffers - 1 to 0 by -1).foreach(i => push(slots(i)))
  }

  /** Frees the pool's memory, once the pool is closed with none of its buffers held. */
  protected def freeMemory(): Unit

  def available: Int = free.get()

  /** Takes a free buffer, cleared, or null if none is. */
  def take(): S | Null = {
    if closed then throw new IllegalStateException("The pool is closed")
    var taken: Slot | Null = null
    var done = false
    while !done do {
      val seen = head.get()
      val index = FreeListPool.index(seen)
      if index < 0 then done = true
      else {
        val slot = slots(index)
        if head.compareAndSet(seen, FreeListPool.pack(slot.next, FreeListPool.tag(seen) + 1)) then {
          taken = slot
          done = true
        }
      }
    }
    if taken != null then {
      taken.held.set(true)
      free.decrementAndGet()
      taken.buffer.clear()
    }
    taken.asInstanceOf[S | Null]
  }

  private def release(slot: Slot): Unit = {
    // A second release would push the slot again, making a cycle of the free stack, so it fails instead.
    if !slot.held.compareAndSet(true, false) then
      throw new IllegalStateException(s"Buffer ${slot.index} of the pool was released already")
    push(slot)
    free.incrementAndGet()
  }

  private def push(slot: Slot): Unit = {
    var done = false
    while !done do {
      val seen = head.get()
      slot.next = FreeListPool.index(seen)
      done = head.compareAndSet(seen, FreeListPool.pack(slot.index, FreeListPool.tag(seen) + 1))
    }
  }

  def close(): Unit = synchronized {
    if !closed then {
      // Checked first, so that a pool closed too early stays open, for its buffers to be released and the close done.
      require(free.get() == buffers, s"${buffers - free.get()} of the pool's buffers are still held")
      closed = true
      freeMemory()
    }
  }
}

private object FreeListPool {
  private inline def pack(index: Int, tag: Int): Long = (tag.toLong << 32) | (index.toLong & 0xffffffffL)
  private inline def index(word: Long): Int = word.toInt
  private inline def tag(word: Long): Int = (word >>> 32).toInt
}
