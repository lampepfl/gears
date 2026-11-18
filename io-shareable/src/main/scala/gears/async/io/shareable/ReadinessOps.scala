package gears.async.io.shareable

import gears.async.Async
import gears.async.io.Received
import gears.async.io.Slot
import gears.async.io.shareable.TimerHeapOp.Outcome

import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import scala.util.Failure
import scala.util.control.NonFatal

/** An op that does its work when its handle is ready for it: tried at once by its performer and, if the handle was not
  * ready, again by the worker whose event source reports that it is. A trait, so that both the ops performed again and
  * again, a read or an accept, and those performed once, a connect, are such ops.
  *
  * Its `Source` is its handle's: the event sources that listen for the handle are where the op keeps its time limit.
  * Nothing else ties the two types, so an op names its handle's type alone, and the handle says what listens for it.
  */
trait ReadyWaiting extends AbstractTimerHeapOp, TimeoutOnWake, ReadinessOp {

  /** Where its handle is listened for, which is one of its workers, and where its time limit is kept. */
  type Source <: EventSource

  /** One attempt at the work, which must not block, with the handle's work claimed: how many bytes it moved, which may
    * be none, as at the end of a stream, or `Blocked` if the handle was not ready, and nothing was done.
    */
  protected def attempt(): Moved

  /** Whether the op waits for the handle to be readable, rather than writable. */
  protected def isRead: Boolean

  final def handleClosing(): Unit =
    if claimAtReady() then completeFromReady(Failure(new ClosedChannelException()))

  final def failReady(failure: Throwable): Unit = completeFromReady(Failure(failure))

  final def readyWakeup(): Readiness =
    var moved = Readiness.SettledOtherwise
    val result: Outcome | Null =
      try
        if cancelledDuringWork then TimerHeapOp.CancelledSentinel
        else
          // Without claiming the handle's work: whoever claimed the op for its handle keeps a close away.
          val did = attempt()
          if did.isBlocked then null
          else
            moved = Readiness.of(did)
            WorkpoolScheduler.SuccessUnit
      catch
        case NonFatal(ex) =>
          moved = Readiness.SettledOtherwise
          Failure(ex)
        case fatal: Throwable =>
          // As in `timerWakeup`: an op left claimed would keep its performer waiting for ever.
          completeFromReady(Failure(fatal))
          throw fatal
    if result == null then Readiness.Blocked
    else
      completeFromReady(result)
      moved

  /** Does the work at once, which most often does it, or else waits for the handle's listener to find the handle ready
    * and do it, for the handle to close, or for the op's time limit or cancel. The listener is `self`, the performer's
    * worker, if the handle had none, and from then on whichever worker that was: it does the work and resumes the
    * performer, which goes on from there on that worker. On a handle whose ops take turns, an op performed while others
    * wait goes behind them without trying.
    */
  protected final def performWhenReadyOn(handle: ReadinessHandle { type Source = ReadyWaiting.this.Source })(
      self: Source
  )(using Async): Unit =
    self.handleOwner.check(handle)
    val readiness = handle.readiness(isRead)
    var waiting = false
    var done = false
    while !done && !waiting do
      val tick = readiness.tick
      handle.claimWork()
      try
        // A handle known to be drained is not tried: its listener reports it when there is something again.
        val tried = !readiness.waitsWithoutTrying(tick)
        if tried && !attempt().isBlocked then done = true
        else
          expose()
          val listening = readiness.registerWaiter(this, tick, tried, self)
          if listening != null then // else it may be ready for all the handle knows: again
            val listener = listening.nn
            waiting = true
            try
              // On the listener's heap, not the performer's: a claim the listener gives back can't then have kept
              // the time limit from firing meanwhile, as one thread does both.
              if hasDeadline then holdTimerOn(listener)
            catch
              case failure: Throwable =>
                readiness.clearWaiter(this): Unit
                throw failure
      finally handle.releaseWork()
    if waiting then
      try parkUntilSettled()
      finally readiness.clearWaiter(this): Unit
}

/** A `ReadyWaiting` op that may be performed again and again, on the handle it was built with. */
abstract class AbstractReadinessOp[H <: ReadinessHandle](protected val waitsOn: H)
    extends RepeatableAbstractTimerHeapOp,
      ReadyWaiting {
  type Source = waitsOn.Source

  final def performWhenReady(self: Source)(using Async): Unit = performWhenReadyOn(waitsOn)(self)
}

/** A read of a stream, whatever it reads into. */
abstract class AbstractStreamReadOp[H <: BaseReadableHandle & ReadinessHandle](handle: H)
    extends AbstractReadinessOp[H](handle) {

  /** Reads into `buf`, without blocking: what it read, the end of the stream, or that it would block. */
  def _readHandle(handle: H, buf: ByteBuffer): Moved

  /** As `_readHandle`, into `bufs` in order. */
  def _readvHandle(handle: H, bufs: Array[ByteBuffer]): Moved

  protected final def isRead: Boolean = true
}

abstract class AbstractReadOp[H <: BaseReadableHandle & ReadinessHandle](handle: H, buf: ByteBuffer)
    extends AbstractStreamReadOp[H](handle) {
  if buf.isReadOnly() then throw new IllegalArgumentException("A read needs a buffer it can write to")

  override def onEntry(): Unit =
    super.onEntry()
    if !buf.hasRemaining() then throw new IllegalArgumentException("A read needs a buffer with room")

  protected final def attempt(): Moved = _readHandle(handle, buf)
}

/** A scattering read: into `bufs` in order, each filled before the next is begun. */
abstract class AbstractScatterReadOp[H <: BaseReadableHandle & ReadinessHandle](
    handle: H,
    bufs: Array[ByteBuffer]
) extends AbstractStreamReadOp[H](handle) {
  if bufs.isEmpty then throw new IllegalArgumentException("A scattering read needs a buffer")
  if bufs.exists(_.isReadOnly()) then
    throw new IllegalArgumentException("A scattering read needs buffers it can write to")

  override def onEntry(): Unit =
    super.onEntry()
    if !bufs.exists(_.hasRemaining()) then
      throw new IllegalArgumentException("A scattering read needs a buffer with room")

  protected final def attempt(): Moved = _readvHandle(handle, bufs)
}

/** A read into a buffer of `pool`'s, handed over through `into` as a `Received`. The buffer is taken for each attempt
  * and given back by one that would block, so that a read which waits holds none: a pool with no buffer free refuses
  * the read as it is attempted.
  */
abstract class AbstractPooledReadOp[H <: BaseReadableHandle & ReadinessHandle](
    handle: H,
    pool: LendingPool,
    into: Slot[Received]
) extends AbstractStreamReadOp[H](handle) {

  override def onEntry(): Unit =
    super.onEntry()
    if !into.isEmpty then throw new IllegalArgumentException("A pooled read needs an empty slot")

  protected final def attempt(): Moved =
    val buf = pool.take()
    val read =
      try _readHandle(handle, buf)
      catch
        case failure: Throwable =>
          pool.give(buf)
          throw failure
    if read.isBlocked then
      pool.give(buf)
      Moved.Blocked
    else
      buf.flip() // at the end of the stream nothing was read, and the limit is 0
      into.set(pool.received(buf))
      read
}

/** A write to a stream, whatever it writes from. */
abstract class AbstractStreamWriteOp[H <: BaseWritableHandle & ReadinessHandle](handle: H)
    extends AbstractReadinessOp[H](handle) {

  /** Writes from `buf`, without blocking: what it wrote, or that it would block. */
  def _writeHandle(handle: H, buf: ByteBuffer): Moved

  /** As `_writeHandle`, from `bufs` in order. */
  def _writevHandle(handle: H, bufs: Array[ByteBuffer]): Moved

  protected final def isRead: Boolean = false
}

abstract class AbstractWriteOp[H <: BaseWritableHandle & ReadinessHandle](
    handle: H,
    buf: ByteBuffer
) extends AbstractStreamWriteOp[H](handle) {

  /** With nothing to write, the op is done, moving nothing. */
  protected final def attempt(): Moved = if buf.hasRemaining() then _writeHandle(handle, buf) else Moved(0)
}

/** A gathering write: from `bufs` in order, each drained before the next is begun. */
abstract class AbstractGatherWriteOp[H <: BaseWritableHandle & ReadinessHandle](
    handle: H,
    bufs: Array[ByteBuffer]
) extends AbstractStreamWriteOp[H](handle) {

  protected final def attempt(): Moved = if bufs.exists(_.hasRemaining()) then _writevHandle(handle, bufs) else Moved(0)
}
