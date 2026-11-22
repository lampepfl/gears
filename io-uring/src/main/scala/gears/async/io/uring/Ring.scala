package gears.async.io
package uring

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import scala.scalanative.linux.eventfd
import scala.scalanative.posix.errno
import scala.scalanative.posix.unistd
import scala.scalanative.runtime.ByteArray
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import uring.*
import uringStructOps.*

private[uring] final class Ring(entries: Int, completions: Int) {
  private val storage = new Array[Byte](io_uring_size().toInt)
  private def ring: Ptr[io_uring] = storage.asInstanceOf[ByteArray].at(0).asInstanceOf[Ptr[io_uring]]

  locally {
    val res = io_uring_init(entries.toUInt, ring, completions.toUInt)
    if res < 0 then throw Ring.failure("Failed to set up io_uring", res)
    val refusal = Ring.unsupported(ring)
    if refusal != null then {
      io_uring_queue_exit(ring)
      throw refusal
    }
  }

  private val wakeFd: Int = eventfd.eventfd(0.toUInt, eventfd.EFD_CLOEXEC)
  if wakeFd < 0 then {
    io_uring_queue_exit(ring)
    throw Results.failed(s"Failed to create an eventfd")
  }
  private val wakeBuffer = new Array[Byte](8)
  private val wakeWrite = {
    val one = new Array[Byte](8)
    one(0) = 1
    one
  }
  private var wakeArmed = false

  // - the worker sets `sleeping` before it looks for work; a waker sets `signalled` before it reads `sleeping`
  // - so either the worker sees the signal, or the waker sees it asleep and writes the eventfd
  private val sleeping = new AtomicBoolean(false)

  private val signalled = new AtomicBoolean(false)

  @volatile private var closed = false

  armWake()

  def wake(): Unit = {
    signalled.set(true)
    if sleeping.getAndSet(false) then
      synchronized {
        if !closed then {
          // - a failed write is the ring's to find out as it waits, not the waker's
          unistd.write(wakeFd, wakeWrite.asInstanceOf[ByteArray].at(0), 8.toUSize): Unit
        }
      }
  }

  // - `hasWork` is asked after `onSubmit`, which may resume futures: asked before, the worker could sleep with work
  def await(deadline: Long, hasWork: Ring.Check, spin: Boolean)(dispatch: (Long, Int) => Unit): Unit = {
    armWake()
    onSubmit()
    var res = 0
    if signalled.get() || hasWork.check() then res = submitPrepared()
    else {
      val arrived = spin && {
        checkSubmitted(submitPrepared())
        val spinEnd = System.nanoTime() + Ring.SpinNanos
        spinUntil(if deadline < 0 then spinEnd else math.min(deadline, spinEnd))
      }
      if !arrived then {
        sleeping.set(true)
        val wait =
          if signalled.get() then 0L
          else if deadline < 0 then -1L
          else math.max(deadline - System.nanoTime(), 0L)
        if wait == 0 then {
          sleeping.set(false)
          if io_uring_sq_ready(ring).toInt > 0 then res = io_uring_submit(ring)
        } else res = io_uring_submit_and_wait(ring, wait)
      }
    }
    sleeping.set(false)
    signalled.set(false)
    afterSubmit()
    checkSubmitted(res)
    reap(dispatch)
  }

  private def checkSubmitted(res: Int): Unit =
    if res < 0 && res != -Linux.ETIME && res != -errno.EINTR && res != -errno.EBUSY && res != -errno.EAGAIN &&
      res != -errno.ENOMEM
    then throw Ring.failure("Failed to submit to io_uring", res)

  private def submitPrepared(): Int = if io_uring_sq_ready(ring).toInt > 0 then io_uring_submit(ring) else 0

  private def spinUntil(until: Long): Boolean = {
    var arrived = false
    var spinning = true
    while spinning do {
      val state = io_uring_spin_check(ring)
      if state == 1 || signalled.get() then {
        arrived = true
        spinning = false
      } else if state == 2 then io_uring_get_events(ring): Unit
      else if System.nanoTime() >= until then spinning = false
    }
    arrived
  }

  var onSubmit: () => Unit = () => ()

  var afterSubmit: () => Unit = () => ()

  var onReceive: Ring.OnReceive = Ring.NoReceive

  // - never inside a reap: its batch is the reap's
  def collectNow(dispatch: (Long, Int) => Unit): Boolean =
    !closed && !reaping && {
      val state = io_uring_spin_check(ring)
      if state == 2 then io_uring_get_events(ring): Unit
      if state != 0 then reap(dispatch)
      true
    }

  def setupBufRing(entries: Int, bgid: Int, base: Ptr[Byte], size: Int): Ptr[Byte] = {
    val err = stackalloc[CInt]()
    val br = buf_ring_setup(ring, entries.toUInt, bgid, base, size.toUInt, err)
    if br == null then throw Ring.failure("Failed to set up io_uring's provided buffers", !err)
    br
  }

  def freeBufRing(br: Ptr[Byte], entries: Int, bgid: Int): Unit =
    if !closed then buf_ring_free(ring, br, entries.toUInt, bgid): Unit

  def armReceive(fd: Int, bgid: Int, id: Long): Ptr[io_uring_sqe] | Null = {
    val sqe = nextSqe()
    if sqe != null then prep_recv_multishot(sqe.nn, fd, bgid, id.toULong)
    sqe
  }

  def isClosed: Boolean = closed

  /** The next free entry, submitting what is prepared to make room if there is none: null if there is still none, the
    * kernel refusing for a moment, or the ring is closed.
    */
  def nextSqe(): Ptr[io_uring_sqe] | Null =
    if closed then null
    else {
      var sqe = io_uring_get_sqe(ring)
      if sqe == null then {
        onSubmit()
        val res = io_uring_submit(ring)
        afterSubmit()
        checkSubmitted(res)
        sqe = io_uring_get_sqe(ring)
      }
      sqe
    }

  def pending(sqe: Ptr[io_uring_sqe], id: Long): Boolean = !closed && io_uring_sqe_pending(ring, sqe, id.toULong) != 0

  def nop(sqe: Ptr[io_uring_sqe]): Unit =
    if !closed then {
      io_uring_prep_nop(sqe)
      sqe.user_data = Ring.InternalId.toULong
    }

  /** 0 if cancelled, the kernel having let go; `-ENOENT` if it had finished, its completion in the ring; and without
    * `wait`, `-EALREADY` or `-ETIME` for one a kernel worker runs, whose completion comes once it lets go.
    */
  def cancelNow(id: Long, wait: Boolean): Int =
    if closed then -errno.ENOENT else io_uring_cancel_now(ring, id.toULong, if wait then 1 else 0)

  def cancelFd(fd: Int): Unit = synchronized(if !closed then io_uring_cancel_fd(ring, fd): Unit)

  private inline val MaxBatch = 64
  private val batchStorage = new Array[Byte]((sizeof[Ptr[io_uring_cqe]] * MaxBatch.toUInt).toInt)
  private def batch: Ptr[Ptr[io_uring_cqe]] =
    batchStorage.asInstanceOf[ByteArray].at(0).asInstanceOf[Ptr[Ptr[io_uring_cqe]]]

  private var reaping = false

  private def reap(dispatch: (Long, Int) => Unit): Unit = {
    reaping = true
    try reapAll(dispatch)
    finally reaping = false
    armWake()
  }

  private def reapAll(dispatch: (Long, Int) => Unit): Unit = {
    var more = true
    while more do {
      val count = io_uring_peek_batch_cqe(ring, batch, MaxBatch.toUInt).toInt
      var i = 0
      while i < count do {
        val cqe = !(batch + i)
        val id = cqe.user_data.toLong
        val res = cqe.res
        io_uring_cq_advance(ring, 1.toUInt)
        if id == Ring.WakeId then wakeArmed = false
        else if id >= Ring.ReceiveBase then onReceive.receive(id, res, cqe.flags.toInt)
        else if id != Ring.InternalId then dispatch(id, res)
        i += 1
      }
      if count < MaxBatch then
        if io_uring_cq_overflowing(ring) == 0 then more = false
        else {
          val res = io_uring_get_events(ring)
          if res < 0 && res != -errno.EINTR && res != -errno.EAGAIN && res != -errno.EBUSY then
            throw Ring.failure("Failed to take completions from io_uring", res)
        }
    }
  }

  private def armWake(): Unit =
    if !wakeArmed && !closed then {
      val sqe = nextSqe()
      if sqe != null then {
        io_uring_prep_read(sqe, wakeFd, wakeBuffer.asInstanceOf[ByteArray].at(0), 8.toUInt, 0.toULong)
        sqe.user_data = Ring.WakeId.toULong
        wakeArmed = true
      }
    }

  def close(): Unit = synchronized {
    if !closed then {
      closed = true
      io_uring_queue_exit(ring)
      unistd.close(wakeFd): Unit
    }
  }
}

private[uring] object Ring {

  // - ping-pong over a Unix socket: 13.7 µs without the spin, 7.4 µs with 20 µs (results/2026-11-16-backends)
  inline val SpinNanos = 20_000L

  inline val WakeId = 0L
  inline val InternalId = 1L
  inline val ReceiveBase = 1L << 62

  abstract class Check { def check(): Boolean }

  abstract class OnReceive { def receive(id: Long, res: Int, flags: Int): Unit }

  private object NoReceive extends OnReceive { def receive(id: Long, res: Int, flags: Int): Unit = () }

  def failure(what: String, res: Int): IOException = Results.failure(what, res)

  private def unsupported(ring: Ptr[io_uring]): IOException | Null = {
    val features = io_uring_features(ring).toInt
    val probe = io_uring_cancel_now(ring, InternalId.toULong, 1)
    if probe != -errno.ENOENT || (features & IORING_FEAT_NODROP) == 0 || (features & IORING_FEAT_EXT_ARG) == 0 then
      new IOException(s"The io_uring backend needs Linux 6.0 or later (synchronous cancel: $probe)")
    else null
  }
}
