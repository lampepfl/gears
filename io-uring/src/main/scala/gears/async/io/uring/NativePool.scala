package gears.async.io
package uring

import gears.async.io.BufferPool
import gears.async.io.Received

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import scala.scalanative.memory.PointerBuffer
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

private[uring] final class NativePool(val bufferSize: Int, val buffers: Int) extends BufferPool {
  require(bufferSize > 0 && buffers > 0, "A pool needs buffers of some size")

  private val zone = Zone.open()
  private val memory: Ptr[Byte] = alloc[Byte]((bufferSize.toLong * buffers).toCSize)(using zone)

  final class PoolBuffer(val index: Int, val address: Ptr[Byte]) extends Received {
    val buffer: ByteBuffer = PointerBuffer.wrap(address, bufferSize)

    @volatile private[NativePool] var next = -1

    private[NativePool] val held = new AtomicBoolean(false)

    def release(): Unit = NativePool.this.release(this)
  }

  private val all = Array.tabulate(buffers)(i => new PoolBuffer(i, memory + i.toLong * bufferSize))

  // - free-stack top index in the low 32 bits, a change count above, against ABA
  private val head = new AtomicLong(NativePool.pack(-1, 0))
  private val free = new AtomicInteger(buffers)
  @volatile private var closed = false

  (buffers - 1 to 0 by -1).foreach(i => push(all(i)))

  def available: Int = free.get()

  private val parked = new java.util.ArrayDeque[Command]()
  private val parkedCount = new AtomicInteger()

  private[uring] def park(read: Command): Boolean = synchronized {
    parkedCount.incrementAndGet()
    if free.get() > 0 then {
      parkedCount.decrementAndGet()
      false
    } else {
      parked.addLast(read)
      true
    }
  }

  private[uring] def leave(read: Command): Boolean = synchronized {
    parked.remove(read) && {
      parkedCount.decrementAndGet()
      true
    }
  }

  private def unpark(): Unit =
    if parkedCount.get() > 0 then {
      val reads = synchronized {
        val all = parked.toArray(new Array[Command](0))
        parked.clear()
        parkedCount.addAndGet(-all.length)
        all
      }
      for read <- reads do read.owner.nn.startTurn(read)
    }

  def take(): PoolBuffer | Null = {
    if closed then throw new IllegalStateException("The pool is closed")
    var taken: PoolBuffer | Null = null
    var done = false
    while !done do {
      val seen = head.get()
      val index = NativePool.index(seen)
      if index < 0 then done = true
      else {
        val buffer = all(index)
        if head.compareAndSet(seen, NativePool.pack(buffer.next, NativePool.tag(seen) + 1)) then {
          taken = buffer
          done = true
        }
      }
    }
    if taken != null then {
      taken.held.set(true)
      free.decrementAndGet()
      taken.buffer.clear()
    }
    taken
  }

  private def release(buffer: PoolBuffer): Unit = {
    if !buffer.held.compareAndSet(true, false) then
      throw new IllegalStateException(s"Buffer ${buffer.index} of the pool was released already")
    push(buffer)
    free.incrementAndGet()
    unpark()
  }

  private def push(buffer: PoolBuffer): Unit = {
    var done = false
    while !done do {
      val seen = head.get()
      buffer.next = NativePool.index(seen)
      done = head.compareAndSet(seen, NativePool.pack(buffer.index, NativePool.tag(seen) + 1))
    }
  }

  def close(): Unit = synchronized {
    if !closed then {
      require(free.get() == buffers, s"${buffers - free.get()} of the pool's buffers are still held")
      closed = true
      zone.close()
    }
  }
}

private object NativePool {
  private inline def pack(index: Int, tag: Int): Long = (tag.toLong << 32) | (index.toLong & 0xffffffffL)
  private inline def index(word: Long): Int = word.toInt
  private inline def tag(word: Long): Int = (word >>> 32).toInt
}
