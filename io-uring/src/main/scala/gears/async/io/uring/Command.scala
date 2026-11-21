package gears.async.io
package uring

import gears.async.Async
import gears.async.CompletionGroup
import gears.async.Listener
import gears.async.io.posix.NativeBuffer
import gears.async.io.posix.NonBlocking
import gears.async.io.posix.Unblocked
import gears.async.io.shareable.BackendOp
import gears.async.io.shareable.Checks
import gears.async.io.shareable.OpPerformer
import gears.async.io.shareable.RepeatableAbstractTimerHeapOp
import gears.async.io.shareable.SingleUseAbstractTimerHeapOp
import gears.async.io.shareable.TimeoutOnWake
import gears.async.io.shareable.WorkpoolScheduler

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import scala.scalanative.posix.errno
import scala.scalanative.posix.sys.socket
import scala.scalanative.posix.sys.uio
import scala.scalanative.runtime.ByteArray
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.util.Failure
import scala.util.Try
import scala.util.control.NonFatal

import uring.*
import uringStructOps.*

/** An op of this backend's, of one of the kinds a backend has (see `BackendOp`), or a command of the ring's. */
trait UringOp extends BackendOp[UringWorker]:
  final def _holdTimer(worker: UringWorker): Unit = worker.addTimer(this)

// - the kernel owns its buffers from submission until its completion is reaped, or a synchronous cancel returns
// - only its worker touches its entry; anyone else goes through that worker's ring tasks
// - unclaimed while in the kernel: a cancel or a time limit settles it at once, and the performer then takes the entry
//   back and waits for the kernel to let go; work the kernel did first is delivered all the same
private[uring] abstract class Command extends RepeatableAbstractTimerHeapOp, UringOp, TimeoutOnWake {

  def handle: UringHandle

  final def fd: Int = handle.fd

  def begin(worker: UringWorker): Unit = ()

  /** After the performance, on the performer's thread, whatever its outcome. */
  def ended(): Unit = ()

  protected def direction: Command.Direction = Command.Direction.Neither

  def check(): Unit = ()

  def turns: Turns | Null = null

  protected def prepare(sqe: Ptr[io_uring_sqe]): Unit

  protected def finish(res: Int): Boolean

  def nothingToDo: Boolean = false

  def tryAtOnce(): Boolean = false

  protected def discard(): Unit = ()

  protected def readinessEvents: Int = 0

  final def performOn(performer: OpPerformer[UringWorker] & UringWorker)(using Async): Unit =
    performer.performCommand(this)

  private var stage = Command.Stage.Idle
  private var entryId = 0L
  private var entry: Ptr[io_uring_sqe] = null
  @volatile private[uring] var owner: UringWorker | Null = null
  private var awaitingReadiness = false
  @volatile private[uring] var wantOut = false
  @volatile private[uring] var performance = 0L
  @volatile private var lateSuccess = false
  private val letGoState = new AtomicReference[AnyRef | Null](null)

  override def onEntry(): Unit = {
    super.onEntry()
    performance += 1
    stage = Command.Stage.Idle
    awaitingReadiness = false
    wantOut = false
    lateSuccess = false
    letGoState.set(null)
  }

  private[uring] final def state: Command.Stage = stage
  private[uring] final def id: Long = entryId
  private[uring] final def sqe: Ptr[io_uring_sqe] = entry

  private[uring] final def prepared(id: Long, sqe: Ptr[io_uring_sqe]): Unit = {
    entryId = id
    entry = sqe
    stage = Command.Stage.Prepared
  }

  private[uring] final def submitted(): Unit = stage = Command.Stage.Submitted

  private[uring] final def outOfRing(): Unit = stage = Command.Stage.Idle

  private[uring] final def backlogged(): Unit = stage = Command.Stage.Backlogged

  private[uring] def readyToSubmit(): Boolean = true

  private[uring] def leaveParking(): Boolean = false

  private[uring] def performerOf(current: UringWorker): UringWorker = current

  protected final def park(): Unit = stage = Command.Stage.Parked

  private[uring] final def resume(): Unit =
    if stage == Command.Stage.Parked then {
      stage = Command.Stage.Idle
      if handle.parked eq this then handle.parked = null
    }

  private[uring] final def doneAtOnce(): Boolean = nothingToDo || (handle.triesAtOnce && tryAtOnce())

  private[uring] final def claimDirection(): Boolean = direction match {
    case Command.Direction.Reading => handle.reading.compareAndSet(false, true)
    case Command.Direction.Writing => handle.writing.compareAndSet(false, true)
    case Command.Direction.Neither => true
  }

  private var ringOf: Ring | Null = null
  private var ringCount: java.util.concurrent.atomic.AtomicInteger | Null = null

  // - a pooled read started again after parking is counted on its ring already
  private[uring] final def onRing(ring: Ring): Unit =
    if ringOf ne ring then {
      ringCount = handle.onRing(ring)
      ringOf = ring
    }

  /** Lets go of its direction and its count on its ring. */
  private[uring] final def release(): Unit = {
    val count = ringCount
    if count != null then {
      ringOf = null
      ringCount = null
      count.decrementAndGet(): Unit
    }
    direction match {
      case Command.Direction.Reading => handle.reading.set(false)
      case Command.Direction.Writing => handle.writing.set(false)
      case Command.Direction.Neither => ()
    }
  }

  private[uring] final def prepareSubmission(sqe: Ptr[io_uring_sqe]): Unit =
    if awaitingReadiness then io_uring_prep_poll_add(sqe, fd, readinessEvents.toUInt)
    else prepare(sqe)

  private[uring] final def finishSubmission(res: Int): Boolean =
    if awaitingReadiness then {
      awaitingReadiness = false
      if res < 0 && !Results.transient(res) then {
        discard()
        throw Results.failure(s"Failed to wait for descriptor $fd", res)
      }
      false
    } else if res == -errno.EAGAIN && readinessEvents != 0 then {
      awaitingReadiness = true
      false
    } else finish(res)

  private[uring] final def dropCompletion(res: Int): Unit = if !awaitingReadiness then dropResult(res)

  protected def dropResult(res: Int): Unit = ()

  private[uring] final def discardSubmission(): Unit = {
    awaitingReadiness = false
    discard()
  }

  private[uring] final def exposeNow(): Unit = expose()

  private[uring] final def hasLetGo: Boolean = letGoState.get() eq Command.LetGo

  /** On its worker, once the kernel has let go and its use has ended: the performer gets `outcome`, unless the op was
    * settled otherwise first, when a success the performer then takes over its cancel or time limit.
    */
  private[uring] final def deliver(outcome: Try[Unit], done: Boolean): Unit =
    if claimAtWork() then {
      markLetGo()
      completeFromWork(outcome)
    } else {
      lateSuccess = done && outcome.isSuccess
      markLetGo()
    }

  private[uring] final def markLetGo(): Unit =
    letGoState.getAndSet(Command.LetGo) match {
      case waiting: Listener[?] => waiting.asInstanceOf[Listener[Unit]].completeNow((), letGo): Unit
      case _                    => ()
    }

  private lazy val letGo: Async.Source[Unit] = new Async.Source[Unit] {
    def poll(k: Listener[Unit]): Boolean = hasLetGo && k.completeNow((), this)
    def onComplete(k: Listener[Unit]): Unit = if !letGoState.compareAndSet(null, k) then k.completeNow((), this): Unit
    def dropListener(k: Listener[Unit]): Unit = letGoState.compareAndSet(k, null): Unit
  }

  /** On the performer's thread, the op given to its worker: its outcome, or, cancelled or out of time, what the kernel
    * did before it let go.
    */
  private[uring] final def awaitOutcome(worker: UringWorker)(using async: Async): Unit =
    try parkUntilSettled()
    catch {
      case stopped: Throwable =>
        if !hasLetGo then {
          wantOut = true
          worker.takeBack(this, performance)
          async.withGroup(CompletionGroup.Unlinked).await(letGo)
        }
        if !lateSuccess then throw stopped
    }

  override def toString: String = s"${getClass.getSimpleName} on $handle"
}

private[uring] object Command {
  enum Direction { case Neither, Reading, Writing }

  enum Stage { case Idle, Prepared, Submitted, Parked, Backlogged }

  case object LetGo

  def alreadyPending(op: AnyRef): IllegalArgumentException =
    new IllegalArgumentException(s"An op reading, or writing, as $op does, is already pending on its handle")
}

private[uring] trait OneShot extends Command {
  private val used = new java.util.concurrent.atomic.AtomicBoolean()
  override def check(): Unit = {
    if !used.compareAndSet(false, true) then throw new IllegalStateException(s"$this is one-shot, and was performed")
    super.check()
  }
}

private[uring] final class Staging(bufs: Array[ByteBuffer], copyIn: Boolean, copyOut: Boolean) {
  private val direct = NativeBuffer.allAddressable(bufs)
  private val stands = if direct then bufs else new Array[ByteBuffer](bufs.length)
  private var zone: Zone | Null = null

  def begin(): Array[ByteBuffer] = {
    if !direct then {
      end()
      var i = 0
      while i < bufs.length do {
        val b = bufs(i)
        stands(i) =
          if NativeBuffer.addressable(b) then b
          else {
            if zone == null then zone = Zone.open()
            NativeBuffer.standIn(b, NativeBuffer.stagingMemory(b.remaining())(using zone.nn), copyIn)
          }
        i += 1
      }
    }
    stands
  }

  def commit(n: Int): Unit = {
    NonBlocking.advance(stands, n.toLong)
    if !direct then {
      var i = 0
      while i < bufs.length do {
        val b = bufs(i)
        if !(stands(i) eq b) then NativeBuffer.settle(b, stands(i), copyOut)
        i += 1
      }
      end()
    }
  }

  def end(): Unit = {
    val z = zone
    if z != null then {
      zone = null
      z.close()
    }
  }
}

private[uring] final class MessageHeader(buffers: Int, named: Boolean) {
  private val header = new Array[Byte](sizeof[msghdr].toInt)
  private val vectorStorage = new Array[Byte](sizeof[uio.iovec].toInt * math.max(buffers, 1))
  val name = new Array[Byte](if named then Addresses.StorageSize else 1)

  def msg: Ptr[msghdr] = header.asInstanceOf[ByteArray].at(0).asInstanceOf[Ptr[msghdr]]
  def vectors: Ptr[uio.iovec] = vectorStorage.asInstanceOf[ByteArray].at(0).asInstanceOf[Ptr[uio.iovec]]
  def capacity: Int = name.length

  def fill(bufs: Array[ByteBuffer]): Int = NonBlocking.fill(vectors, bufs)

  def set(count: Int, nameLength: Int): Unit = {
    val m = msg
    m.msg_name = if nameLength > 0 then name.asInstanceOf[ByteArray].at(0) else null
    m.msg_namelen = nameLength.toUInt
    m.msg_iov = vectors.asInstanceOf[Ptr[iovec]]
    m.msg_iovlen = count.toCSize
    m.msg_control = null
    m.msg_controllen = 0.toCSize
    m.msg_flags = 0
  }
}

// ---- Streams

private[uring] abstract class Transfer(bufs: Array[ByteBuffer], copyIn: Boolean) extends Command {
  protected val staging = new Staging(bufs, copyIn = copyIn, copyOut = !copyIn)

  protected def failing: String

  protected def again(res: Int): Boolean = Results.transient(res)

  protected def moved(n: Int): Unit = ()

  protected def finish(res: Int): Boolean =
    if again(res) then false
    else if res < 0 then {
      staging.end()
      throw Results.failure(s"Failed to $failing", res)
    } else {
      staging.commit(res)
      moved(res)
      true
    }

  override protected def discard(): Unit = staging.end()
}

// - a connection's read takes what its receive got first, and goes to the kernel itself only while the receive is idle
private[uring] trait Receiving extends Command {
  protected def receives: Boolean = false
  protected def fromQueue(r: Receiver): Boolean
  protected def atEnd(): Boolean
  protected def directAtOnce(): Boolean
  protected def directReady(): Boolean = true
  protected def directLeave(): Boolean = false
  protected def waitForRoom(): Boolean = false
  protected def failing: String

  private var receiver: Receiver | Null = null
  private val onReceiver = new java.util.concurrent.atomic.AtomicBoolean()

  override private[uring] def performerOf(current: UringWorker): UringWorker = {
    var r = handle.receiver
    if r == null && receives && handle.isInstanceOf[ConnectionHandle] then r = current.receiverFor(handle)
    receiver = r
    if r == null then current else r.worker
  }

  override def tryAtOnce(): Boolean = {
    val r = receiver
    if r == null then directAtOnce()
    else if r.hasQueued then fromQueue(r)
    else if r.idle then directAtOnce()
    else !r.armed && r.outcome(failing) && atEnd()
  }

  override private[uring] def readyToSubmit(): Boolean = {
    val r = receiver
    if r == null then directReady()
    else if r.hasQueued || !(r.idle || r.armed) then {
      if !waitForRoom() then restart()
      false
    } else if receives && r.worker.arm(r) then waitOnReceiver(r)
    else if r.armed then {
      r.worker.stopReceive(r)
      r.worker.collectNow()
      if r.armed then waitOnReceiver(r)
      else {
        restart()
        false
      }
    } else directReady()
  }

  private def restart(): Unit = owner.nn.startTurn(this)

  private def waitOnReceiver(r: Receiver): Boolean = {
    r.worker.readsOnReceives.incrementAndGet()
    onReceiver.set(true)
    handle.parked = this
    park()
    if handle.isClosing && leaveParking() then restart()
    false
  }

  override private[uring] def leaveParking(): Boolean =
    if onReceiver.compareAndSet(true, false) then {
      if handle.parked eq this then handle.parked = null
      owner.nn.readsOnReceives.decrementAndGet()
      true
    } else directLeave()
}

private[uring] final class ReadIntoBuffer(val handle: UringHandle, buf: ByteBuffer)
    extends Transfer(Array(buf), copyIn = false),
      Receiving {
  Checks.checkFillable(buf)
  override protected def direction = Command.Direction.Reading
  override def check(): Unit = if !buf.hasRemaining() then throw Checks.bufferFull(buf)
  override protected def readinessEvents: Int = Linux.POLLIN
  protected def failing: String = s"read from $handle"

  protected def prepare(sqe: Ptr[io_uring_sqe]): Unit = {
    val b = staging.begin()(0)
    io_uring_prep_read(sqe, fd, NativeBuffer.atPosition(b), b.remaining().toUInt, Results.currentPosition)
  }

  override protected def receives: Boolean = Receive.enabled && buf.remaining() <= Receive.LargestRead
  protected def fromQueue(r: Receiver): Boolean = {
    r.take(buf)
    true
  }
  protected def atEnd(): Boolean = true
  protected def directAtOnce(): Boolean = !NonBlocking.read(fd, buf).isBlocked
}

private[uring] final class ReadIntoBuffers(val handle: UringHandle, bufs: Array[ByteBuffer])
    extends Transfer(bufs, copyIn = false),
      Receiving {
  Checks.checkBuffers(bufs)
  Checks.checkFillable(bufs)
  override protected def direction = Command.Direction.Reading
  override def check(): Unit = if !Checks.anyRemaining(bufs) then throw Checks.buffersFull(bufs)
  override protected def readinessEvents: Int = Linux.POLLIN
  protected def failing: String = s"read from $handle"
  private val header = new MessageHeader(math.min(bufs.length, NonBlocking.MaxBuffers), named = false)

  protected def prepare(sqe: Ptr[io_uring_sqe]): Unit = {
    val count = header.fill(staging.begin())
    io_uring_prep_readv(sqe, fd, header.vectors.asInstanceOf[Ptr[iovec]], count.toUInt, Results.currentPosition)
  }

  protected def fromQueue(r: Receiver): Boolean = {
    var i = 0
    while i < bufs.length && r.hasQueued do {
      r.take(bufs(i))
      i += 1
    }
    true
  }
  protected def atEnd(): Boolean = true
  protected def directAtOnce(): Boolean = !NonBlocking.readv(fd, bufs).isBlocked
}

private[uring] final class WriteFromBuffer(val handle: UringHandle, buf: ByteBuffer)
    extends Transfer(Array(buf), copyIn = true) {
  override protected def direction = Command.Direction.Writing
  override protected def readinessEvents: Int = Linux.POLLOUT
  override def nothingToDo: Boolean = !buf.hasRemaining()
  protected def failing: String = s"write to $handle"

  protected def prepare(sqe: Ptr[io_uring_sqe]): Unit = {
    val b = staging.begin()(0)
    io_uring_prep_write(sqe, fd, NativeBuffer.atPosition(b), b.remaining().toUInt, Results.currentPosition)
  }

  override def tryAtOnce(): Boolean = !NonBlocking.write(fd, buf).isBlocked
}

private[uring] final class WriteFromBuffers(val handle: UringHandle, bufs: Array[ByteBuffer])
    extends Transfer(bufs, copyIn = true) {
  Checks.checkBuffers(bufs)
  override protected def direction = Command.Direction.Writing
  override protected def readinessEvents: Int = Linux.POLLOUT
  override def nothingToDo: Boolean = !Checks.anyRemaining(bufs)
  protected def failing: String = s"write to $handle"
  private val header = new MessageHeader(math.min(bufs.length, NonBlocking.MaxBuffers), named = false)

  protected def prepare(sqe: Ptr[io_uring_sqe]): Unit = {
    val count = header.fill(staging.begin())
    io_uring_prep_writev(sqe, fd, header.vectors.asInstanceOf[Ptr[iovec]], count.toUInt, Results.currentPosition)
  }

  override def tryAtOnce(): Boolean = !NonBlocking.writev(fd, bufs).isBlocked
}

// - a read waiting on a socket holds no buffer: it waits for readiness, and takes one as it reads, while the pool has
//   more than half free; a file's read takes one before it is submitted
private[uring] final class PooledRead(val handle: UringHandle, pool: NativePool, into: Slot[Received])
    extends Command,
      Receiving {
  override protected def direction = Command.Direction.Reading
  override def check(): Unit = Checks.checkEmpty(into)
  private var held: pool.PoolBuffer | Null = null
  private var pollNext = false

  private val file = handle.isInstanceOf[FileHandle]

  private def parkOnPool(): Boolean = {
    handle.parked = this
    if pool.park(this) && !(handle.isClosing && leaveParking()) then {
      park()
      true
    } else {
      handle.parked = null
      false
    }
  }

  protected def failing: String = s"read from $handle"

  override protected def directLeave(): Boolean =
    pool.leave(this) && {
      handle.parked = null
      true
    }

  override protected def waitForRoom(): Boolean = parkOnPool()

  protected def fromQueue(r: Receiver): Boolean = {
    val slot = pool.take()
    slot != null && {
      r.take(slot.buffer)
      slot.buffer.flip()
      into.set(slot)
      true
    }
  }

  protected def atEnd(): Boolean = {
    val slot = pool.take()
    slot != null && {
      slot.buffer.flip()
      into.set(slot)
      true
    }
  }

  override protected def directReady(): Boolean =
    if file then {
      var ready = true
      while held == null && ready && !handle.isClosing do {
        held = pool.take()
        if held == null && parkOnPool() then ready = false
      }
      ready
    } else pool.available > 0 || handle.isClosing || !parkOnPool()

  protected def prepare(sqe: Ptr[io_uring_sqe]): Unit = {
    if held == null && !file && !pollNext && pool.available * 2 > pool.buffers then held = pool.take()
    val slot = held
    if slot == null then io_uring_prep_poll_add(sqe, fd, Linux.POLLIN.toUInt)
    else io_uring_prep_read(sqe, fd, slot.address, slot.buffer.capacity().toUInt, Results.currentPosition)
  }

  protected def finish(res: Int): Boolean = {
    val slot = held
    if slot == null then {
      pollNext = false
      if Results.transient(res) then false
      else if res < 0 then throw Results.failure(s"Failed to wait for $handle", res)
      else directAtOnce()
    } else if Results.transient(res) then {
      held = null
      slot.release()
      pollNext = !file
      false
    } else {
      held = null
      if res < 0 then {
        slot.release()
        throw Results.failure(s"Failed to read from $handle", res)
      }
      val buffer = slot.buffer
      buffer.clear()
      buffer.position(res)
      buffer.flip()
      into.set(slot)
      true
    }
  }

  protected def directAtOnce(): Boolean = {
    val slot = pool.take()
    if slot == null then return false
    val read =
      try NonBlocking.read(fd, slot.buffer)
      catch {
        case failure: Throwable =>
          slot.release()
          throw failure
      }
    if read.isBlocked then {
      slot.release()
      false
    } else {
      slot.buffer.flip()
      into.set(slot)
      true
    }
  }

  override protected def discard(): Unit = {
    val slot = held
    held = null
    if slot != null then slot.release()
  }
}

// ---- Stream sockets

private[uring] final class Connect(
    address: IpSocketAddress | UnixAddress,
    options: SocketOptions,
    into: Slot[ConnectionHandle]
) extends Command
    with OneShot {
  private var connection: ConnectionHandle | Null = null
  private var published = false
  def handle: UringHandle = connection.nn

  override def check(): Unit = {
    super.check()
    Checks.checkEmpty(into)
  }

  override def begin(worker: UringWorker): Unit = {
    val opened = UringSockets.startConnect(address, options, worker.handleOwner)
    opened.open()
    connection = opened
  }

  private def connected(): Boolean =
    if !UringSockets.finishConnect(connection.nn) then false
    else {
      into.set(connection.nn)
      published = true
      true
    }

  override def tryAtOnce(): Boolean = connected()

  protected def prepare(sqe: Ptr[io_uring_sqe]): Unit = io_uring_prep_poll_add(sqe, fd, Linux.POLLOUT.toUInt)

  protected def finish(res: Int): Boolean =
    if Results.transient(res) then false
    else if res < 0 then throw Results.failure(s"Failed to connect to $address", res)
    else connected()

  override def ended(): Unit = {
    val opened = connection
    if !published && opened != null then
      try opened.requestClose(): Unit
      catch case _: IOException => ()
  }

  override def toString: String = s"connect to $address"
}

private[uring] final class Accept(val handle: ListenerHandle, into: Slot[ConnectionHandle]) extends Command {
  override def check(): Unit = Checks.checkEmpty(into)
  override def turns: Turns | Null = handle.accepts
  override protected def readinessEvents: Int = Linux.POLLIN

  protected def prepare(sqe: Ptr[io_uring_sqe]): Unit =
    io_uring_prep_accept(sqe, fd, null, null, Linux.SOCK_NONBLOCK | Linux.SOCK_CLOEXEC)

  protected def finish(res: Int): Boolean =
    if Results.transient(res) || res == -errno.ECONNABORTED then false
    else if res < 0 then throw Results.failure("Failed to accept a connection", res)
    else {
      into.set(UringSockets.accepted(handle, res))
      true
    }

  override def tryAtOnce(): Boolean = {
    val client = Accepts.acceptNow(fd)
    if client >= 0 then into.set(UringSockets.accepted(handle, client))
    client >= 0
  }

  override protected def dropResult(res: Int): Unit = if res >= 0 then scala.scalanative.posix.unistd.close(res): Unit
}

private[uring] final class AcceptAll(val handle: ListenerHandle, into: Slot[Array[ConnectionHandle]], max: Int)
    extends Command {
  override def check(): Unit = Checks.checkEmpty(into)
  override def turns: Turns | Null = handle.accepts
  protected def prepare(sqe: Ptr[io_uring_sqe]): Unit = io_uring_prep_poll_add(sqe, fd, Linux.POLLIN.toUInt)

  protected def finish(res: Int): Boolean =
    if Results.transient(res) then false
    else if res < 0 then throw Results.failure(s"Failed to wait for $handle", res)
    else tryAtOnce()

  override def tryAtOnce(): Boolean = {
    val accepted = scala.collection.mutable.ArrayBuffer.empty[ConnectionHandle]
    var more = true
    while more && accepted.size < max do {
      try {
        val client = Accepts.acceptNow(fd)
        if client < 0 then more = false else accepted += UringSockets.accepted(handle, client)
      } catch {
        case NonFatal(_) if accepted.nonEmpty => more = false
      }
    }
    if accepted.nonEmpty then into.set(accepted.toArray)
    accepted.nonEmpty
  }
}

private[uring] object Accepts {
  def acceptNow(fd: Int): Int = {
    var client = LinuxCalls.accept4(fd, null, null, Linux.SOCK_NONBLOCK | Linux.SOCK_CLOEXEC)
    while client < 0 && errno.errno == errno.ECONNABORTED do
      client = LinuxCalls.accept4(fd, null, null, Linux.SOCK_NONBLOCK | Linux.SOCK_CLOEXEC)
    if client < 0 then {
      val e = errno.errno
      if e != errno.EAGAIN && e != errno.EWOULDBLOCK && e != errno.EINTR then
        throw Results.failed(s"Failed to accept a connection")
    }
    client
  }
}

// ---- Datagram sockets

private[uring] final class ReceiveFrom(
    val handle: DatagramHandle,
    buf: ByteBuffer,
    from: Slot[IpSocketAddress] | Slot[UnixAddress] | Null
) extends Command {
  Checks.checkFillable(buf)
  override def check(): Unit = {
    if from != null then Checks.checkEmpty(from.nn)
    if buf.capacity() == 0 then throw Checks.noCapacity(buf)
  }
  override def turns: Turns | Null = handle.receives
  override protected def readinessEvents: Int = Linux.POLLIN
  private val staging = new Staging(Array(buf), copyIn = false, copyOut = true)
  private val header = new MessageHeader(1, named = true)

  private def setUp(): Unit = {
    buf.clear()
    header.set(header.fill(staging.begin()), header.capacity)
  }

  protected def prepare(sqe: Ptr[io_uring_sqe]): Unit = {
    setUp()
    io_uring_prep_recvmsg(sqe, fd, header.msg, 0)
  }

  override def tryAtOnce(): Boolean = {
    setUp()
    val n = Unblocked.recvmsg(fd, header.msg.asInstanceOf[Ptr[socket.msghdr]], Linux.MSG_DONTWAIT).toInt
    Datagrams.atOnce(n, finish, staging)
  }

  protected def finish(res: Int): Boolean =
    if Results.transient(res) then false
    else if res < 0 then {
      staging.end()
      throw Results.failure("Failed to receive a datagram", res)
    } else {
      staging.commit(res)
      buf.flip()
      if from != null then
        Addresses.read(header.name, header.msg.msg_namelen.toInt) match {
          case ip: IpSocketAddress if !handle.unix => from.asInstanceOf[Slot[IpSocketAddress]].set(ip)
          case path: UnixAddress if handle.unix    => from.asInstanceOf[Slot[UnixAddress]].set(path)
          case other                               => throw new IOException(s"Received from $other on $handle")
        }
      true
    }

  override protected def discard(): Unit = staging.end()
}

private[uring] final class SendTo(
    val handle: DatagramHandle,
    bufs: Array[ByteBuffer],
    to: IpSocketAddress | UnixAddress | Null
) extends Command {
  Checks.checkBuffers(bufs)
  override def check(): Unit = {
    if to == null && !Checks.anyRemaining(bufs) then
      throw new IllegalArgumentException("A connected datagram socket sends no empty packet")
    if bufs.count(_.hasRemaining) > NonBlocking.MaxBuffers then
      throw new IllegalArgumentException(
        s"A datagram is sent from at most ${NonBlocking.MaxBuffers} buffers with bytes remaining"
      )
  }
  override def turns: Turns | Null = handle.sends
  override protected def readinessEvents: Int = Linux.POLLOUT
  private val staging = new Staging(bufs, copyIn = true, copyOut = false)
  private val header = new MessageHeader(math.min(bufs.length, NonBlocking.MaxBuffers), named = to != null)
  private var size = 0L

  private def setUp(): Unit = {
    size = Checks.remaining(bufs)
    val nameLength = if to == null then 0 else Addresses.write(to.nn, handle.ipv6, header.name)
    header.set(header.fill(staging.begin()), nameLength)
  }

  protected def prepare(sqe: Ptr[io_uring_sqe]): Unit = {
    setUp()
    io_uring_prep_sendmsg(sqe, fd, header.msg, 0)
  }

  override def tryAtOnce(): Boolean = {
    setUp()
    val n =
      Unblocked.sendmsg(fd, header.msg.asInstanceOf[Ptr[socket.msghdr]], Linux.MSG_DONTWAIT | Linux.MSG_NOSIGNAL).toInt
    Datagrams.atOnce(n, finish, staging)
  }

  protected def finish(res: Int): Boolean =
    if Results.transient(res) then false
    else {
      staging.end()
      if res < 0 then throw Results.failure("Failed to send a datagram", res)
      if res.toLong != size then throw new IOException(s"Incomplete datagram: sent $res of $size bytes")
      for b <- bufs do b.position(b.limit())
      true
    }

  override protected def discard(): Unit = staging.end()
}

private object Datagrams {
  def atOnce(n: Int, finish: Int => Boolean, staging: Staging): Boolean = {
    val res = if n >= 0 then n else -errno.errno
    if res == -errno.EAGAIN || res == -errno.EWOULDBLOCK || res == -errno.EINTR then {
      staging.end()
      false
    } else finish(res)
  }
}

// ---- Regular files: a kernel worker runs each

private[uring] final class PositionalRead(val handle: FileHandle, buf: ByteBuffer, private var offset: Long)
    extends Transfer(Array(buf), copyIn = false) {
  Checks.checkFillable(buf)
  override def check(): Unit = if !buf.hasRemaining() then throw Checks.bufferFull(buf)
  protected def failing: String = s"read $handle at $offset"
  override protected def again(res: Int): Boolean = res == -errno.EINTR
  override protected def moved(n: Int): Unit = offset += n

  protected def prepare(sqe: Ptr[io_uring_sqe]): Unit = {
    val b = staging.begin()(0)
    io_uring_prep_read(sqe, fd, NativeBuffer.atPosition(b), b.remaining().toUInt, offset.toULong)
  }
}

private[uring] final class PositionalWrite(val handle: FileHandle, buf: ByteBuffer, private var offset: Long)
    extends Transfer(Array(buf), copyIn = true) {
  override def nothingToDo: Boolean = !buf.hasRemaining()
  protected def failing: String = s"write $handle at $offset"
  override protected def again(res: Int): Boolean = res == -errno.EINTR
  override protected def moved(n: Int): Unit = offset += n

  protected def prepare(sqe: Ptr[io_uring_sqe]): Unit = {
    val b = staging.begin()(0)
    io_uring_prep_write(sqe, fd, NativeBuffer.atPosition(b), b.remaining().toUInt, offset.toULong)
  }
}

private[uring] final class FileSync(val handle: FileHandle) extends Command {
  protected def prepare(sqe: Ptr[io_uring_sqe]): Unit = io_uring_prep_fsync(sqe, fd)

  protected def finish(res: Int): Boolean =
    if res == -errno.EINTR then false
    else if res < 0 then throw Results.failure(s"Failed to sync $handle", res)
    else true
}

// ---- Closing

// - the handle is closing before its entries are cancelled: an op claimed after finds it so, and one submitted as the
//   close begins is cancelled by its worker after the submission
private[uring] final class CloseHandle(handle: UringHandle)
    extends SingleUseAbstractTimerHeapOp,
      TimeoutOnWake,
      gears.async.io.shareable.CloseListener,
      UringOp {

  final def performOn(performer: OpPerformer[UringWorker] & UringWorker)(using Async): Unit = {
    performer.handleOwner.check(handle)
    if !handle.requestClose() then {
      if hasDeadline then holdTimerOn(performer)
      handle.onClosed(this)
      handle.closing()
      parkUntilSettled()
    }
  }

  final def handleClosed(): Unit =
    if claimAtCloseFire() then completeFromCloseFire(WorkpoolScheduler.SuccessUnit)

  override def toString: String = s"close of $handle"
}
