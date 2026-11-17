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
  * ready, again by the worker whose event source reports that it is. `W` is that event source. A trait, so that both
  * the ops performed again and again, a read or an accept, and those performed once, a connect, are such ops.
  */
trait ReadyWaiting[H <: BaseHandle { type Source = W }, W <: EventSource]
    extends AbstractTimerHeapOp,
      TimeoutOnWake,
      ReadinessOp {

  /** The handle the op waits for. */
  protected def waitsOn: H

  /** One attempt at the work, which must not block, with the handle's work claimed: how many bytes it moved, which may
    * be none, as at the end of a stream, or `Blocked` if the handle was not ready, and nothing was done.
    */
  protected def attempt(): Int

  /** Whether the op waits for the handle to be readable, rather than writable. */
  protected def isRead: Boolean

  /** Its time limit is kept where its handle is listened for, which is one of its workers. */
  type Source = W

  final def handleClosing(): Unit =
    if claimAtReady() then completeFromReady(Failure(new ClosedChannelException()))

  final def failReady(failure: Throwable): Unit = completeFromReady(Failure(failure))

  final def readyWakeup(): Int =
    var moved = ReadinessOp.SettledOtherwise
    val result: Outcome | Null =
      try
        if cancelledDuringWork then TimerHeapOp.CancelledSentinel
        else
          // Without claiming the handle's work: whoever claimed the op for its handle keeps a close away.
          moved = attempt()
          if moved == AbstractReadinessOp.Blocked then null else WorkpoolScheduler.SuccessUnit
      catch
        case NonFatal(ex) =>
          moved = ReadinessOp.SettledOtherwise
          Failure(ex)
        case fatal: Throwable =>
          // As in `timerWakeup`: an op left claimed would keep its performer waiting for ever.
          completeFromReady(Failure(fatal))
          throw fatal
    if result == null then ReadinessOp.Blocked
    else
      completeFromReady(result)
      moved

  /** Does the work at once, which most often does it, or else waits for the handle's listener to find the handle ready
    * and do it, for the handle to close, or for the op's time limit or cancel. The listener is `self`, the performer's
    * worker, if the handle had none, and from then on whichever worker that was: it does the work and resumes the
    * performer, which goes on from there on that worker. On a handle whose ops take turns, an op performed while others
    * wait goes behind them without trying.
    */
  final def performWhenReady(self: W)(using Async): Unit =
    val handle = waitsOn
    val readiness = handle.readiness(isRead)
    var waiting = false
    var done = false
    while !done && !waiting do
      val tick = readiness.tick
      handle.claimWork()
      try
        // A handle known to be drained is not tried: its listener reports it when there is something again.
        val tried = !readiness.waitsWithoutTrying(tick)
        if tried && attempt() != AbstractReadinessOp.Blocked then done = true
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
abstract class AbstractReadinessOp[H <: BaseHandle { type Source = W }, W <: EventSource](handle: H)
    extends RepeatableAbstractTimerHeapOp,
      ReadyWaiting[H, W] {
  protected final def waitsOn: H = handle
}

object AbstractReadinessOp:

  /** What `attempt` returns when the handle was not ready. */
  inline val Blocked = -1

trait NonBlockingReadOps:
  type ReadableHandle <: BaseReadableHandle
  type WorkerSource <: EventSource

  /** The bytes read into `buf`, 0 if the read would block, or -1 at the end of the stream. */
  def _readHandle(handle: ReadableHandle, buf: ByteBuffer): Int

  /** As `_readHandle`, into `bufs` in order. */
  def _readvHandle(handle: ReadableHandle, bufs: Array[ByteBuffer]): Int

object AbstractStreamReadOp:

  /** What a read moved, from what `_readHandle` gives: 0 there is a read that would block, and -1 the end of the
    * stream, which completes the op, moving nothing.
    */
  inline def moved(read: Int): Int =
    if read == 0 then AbstractReadinessOp.Blocked else if read < 0 then 0 else read

/** A read of a stream, whatever it reads into. */
abstract class AbstractStreamReadOp[H <: BaseReadableHandle { type Source = W }, W <: EventSource](handle: H)
    extends AbstractReadinessOp[H, W](handle),
      NonBlockingReadOps {

  type ReadableHandle = H
  type WorkerSource = W

  protected final def isRead: Boolean = true
}

abstract class AbstractReadOp[H <: BaseReadableHandle { type Source = W }, W <: EventSource](handle: H, buf: ByteBuffer)
    extends AbstractStreamReadOp[H, W](handle) {
  if buf.isReadOnly() then throw new IllegalArgumentException("A read needs a buffer it can write to")

  override def onEntry(): Unit =
    super.onEntry()
    if !buf.hasRemaining() then throw new IllegalArgumentException("A read needs a buffer with room")

  protected final def attempt(): Int = AbstractStreamReadOp.moved(_readHandle(handle, buf))
}

/** A scattering read: into `bufs` in order, each filled before the next is begun. */
abstract class AbstractScatterReadOp[H <: BaseReadableHandle { type Source = W }, W <: EventSource](
    handle: H,
    bufs: Array[ByteBuffer]
) extends AbstractStreamReadOp[H, W](handle) {
  if bufs.isEmpty then throw new IllegalArgumentException("A scattering read needs a buffer")
  if bufs.exists(_.isReadOnly()) then
    throw new IllegalArgumentException("A scattering read needs buffers it can write to")

  override def onEntry(): Unit =
    super.onEntry()
    if !bufs.exists(_.hasRemaining()) then
      throw new IllegalArgumentException("A scattering read needs a buffer with room")

  protected final def attempt(): Int = AbstractStreamReadOp.moved(_readvHandle(handle, bufs))
}

/** A read into a buffer of `pool`'s, handed over through `into` as a `Received`. The buffer is taken for each attempt
  * and given back by one that would block, so that a read which waits holds none: a pool with no buffer free refuses
  * the read as it is attempted.
  */
abstract class AbstractPooledReadOp[H <: BaseReadableHandle { type Source = W }, W <: EventSource](
    handle: H,
    pool: LendingPool,
    into: Slot[Received]
) extends AbstractStreamReadOp[H, W](handle) {

  override def onEntry(): Unit =
    super.onEntry()
    if !into.isEmpty then throw new IllegalArgumentException("A pooled read needs an empty slot")

  protected final def attempt(): Int =
    val buf = pool.take()
    val read =
      try _readHandle(handle, buf)
      catch
        case failure: Throwable =>
          pool.give(buf)
          throw failure
    if read == 0 then
      pool.give(buf)
      AbstractReadinessOp.Blocked
    else
      buf.flip() // at the end of the stream nothing was read, and the limit is 0
      into.set(pool.received(buf))
      AbstractStreamReadOp.moved(read)
}

trait NonBlockingWriteOps:
  type WriteableHandle <: BaseWritableHandle
  type WorkerSource <: EventSource

  /** The bytes written from `buf`, or -1 if the write would block. */
  def _writeHandle(handle: WriteableHandle, buf: ByteBuffer): Int

  /** As `_writeHandle`, from `bufs` in order. */
  def _writevHandle(handle: WriteableHandle, bufs: Array[ByteBuffer]): Int

/** A write to a stream, whatever it writes from. */
abstract class AbstractStreamWriteOp[H <: BaseWritableHandle { type Source = W }, W <: EventSource](handle: H)
    extends AbstractReadinessOp[H, W](handle),
      NonBlockingWriteOps {

  type WriteableHandle = H
  type WorkerSource = W

  protected final def isRead: Boolean = false
}

abstract class AbstractWriteOp[H <: BaseWritableHandle { type Source = W }, W <: EventSource](
    handle: H,
    buf: ByteBuffer
) extends AbstractStreamWriteOp[H, W](handle) {

  /** A write that would block is -1, which is `Blocked`; with nothing to write, the op is done, moving nothing. */
  protected final def attempt(): Int = if buf.hasRemaining() then _writeHandle(handle, buf) else 0
}

/** A gathering write: from `bufs` in order, each drained before the next is begun. */
abstract class AbstractGatherWriteOp[H <: BaseWritableHandle { type Source = W }, W <: EventSource](
    handle: H,
    bufs: Array[ByteBuffer]
) extends AbstractStreamWriteOp[H, W](handle) {

  protected final def attempt(): Int = if bufs.exists(_.hasRemaining()) then _writevHandle(handle, bufs) else 0
}
