package gears.async.io
package uring

import java.io.IOException
import java.nio.ByteBuffer
import scala.scalanative.libc.stdlib
import scala.scalanative.memory.PointerBuffer
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

private[uring] object Receive {

  // - `false` in the property, or in the variable when the property is not set, turns multishot receives off
  inline val EnabledProperty = "gears.io.uring.multishot"
  inline val EnabledVariable = "GEARS_IO_URING_MULTISHOT"

  val enabled: Boolean = {
    var text = System.getProperty(EnabledProperty)
    if text == null then text = System.getenv(EnabledVariable)
    text == null || !text.trim.equalsIgnoreCase("false")
  }

  inline val Group = 0
  inline val Entries = 512
  inline val Size = 4096

  // - a read of more goes to the kernel itself: copying it through the provided buffers costs more than it saves
  inline val LargestRead = 16 * 1024

  // - a connection that holds this many with no read waiting stops receiving, and the kernel's flow control holds the
  //   sender back; the stop lands after the burst the kernel is receiving, which may take every free buffer, and the
  //   worker's other reads then go to the kernel themselves until those are read
  inline val MostQueued = 16

  inline val FBuffer = 1
  inline val FMore = 2
  inline val BufferShift = 16
}

// - its worker's alone
private[uring] final class BufferRing(ring: Ring, entries: Int) {
  import Receive.Size

  private val base: Ptr[Byte] = {
    val memory = stdlib.malloc((entries.toLong * Size).toCSize)
    if memory == null then throw new OutOfMemoryError("io_uring's provided buffers")
    memory
  }
  private var ringOfBuffers: Ptr[Byte] =
    try ring.setupBufRing(entries, Receive.Group, base, Size)
    catch {
      case failure: Throwable =>
        stdlib.free(base)
        throw failure
    }
  private val views = Array.tabulate(entries)(i => PointerBuffer.wrap(base + i.toLong * Size, Size))
  private var lent = 0

  def free: Int = entries - lent

  def view(bid: Int): ByteBuffer = views(bid)

  def taken(): Unit = lent += 1

  def recycle(bid: Int): Unit = {
    uring.buf_ring_recycle(ringOfBuffers, entries.toUInt, base, Size.toUInt, bid)
    lent -= 1
  }

  // - once nothing is armed on the ring: the kernel writes into these until its receives end
  def close(): Unit =
    if ringOfBuffers != null then {
      ring.freeBufRing(ringOfBuffers, entries, Receive.Group)
      ringOfBuffers = null
      stdlib.free(base)
    }
}

// - its worker's alone, on which every read of the connection is performed
private[uring] final class Receiver(val worker: UringWorker, val handle: UringHandle, buffers: BufferRing) {
  private var bids = new Array[Int](8)
  private var lengths = new Array[Int](8)
  private var head = 0
  private var count = 0
  private var taken = 0

  var armed = false
  var stopping = false
  var id = 0L
  var sqe: Ptr[uring.io_uring_sqe] | Null = null
  var ended = false
  var failure = 0
  var lost: IOException | Null = null

  def queued: Int = count
  def hasQueued: Boolean = count > 0

  // - a read may go to the kernel itself only while it is idle: nothing received waits, and nothing more can come
  def idle: Boolean = count == 0 && !armed && !ended && failure == 0 && lost == null

  def add(bid: Int, length: Int): Unit = {
    if count == bids.length then grow()
    val at = (head + count) % bids.length
    bids(at) = bid
    lengths(at) = length
    count += 1
  }

  private def grow(): Unit = {
    val n = bids.length * 2
    val b = new Array[Int](n)
    val l = new Array[Int](n)
    var i = 0
    while i < count do {
      b(i) = bids((head + i) % bids.length)
      l(i) = lengths((head + i) % bids.length)
      i += 1
    }
    bids = b
    lengths = l
    head = 0
  }

  def take(into: ByteBuffer): Unit =
    while into.hasRemaining && count > 0 do {
      val bid = bids(head)
      val left = lengths(head) - taken
      val n = math.min(into.remaining(), left)
      val view = buffers.view(bid)
      view.limit(taken + n).position(taken)
      into.put(view)
      if n == left then {
        head = (head + 1) % bids.length
        count -= 1
        taken = 0
        buffers.recycle(bid)
      } else taken += n
    }

  def drop(): Unit =
    while count > 0 do {
      if lost == null then buffers.recycle(bids(head))
      head = (head + 1) % bids.length
      count -= 1
      taken = 0
    }

  def outcome(what: => String): Boolean =
    if lost != null then throw lost.nn
    else if failure != 0 then throw Results.failure(s"Failed to $what", failure)
    else ended
}
