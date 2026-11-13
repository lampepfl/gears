package gears.async.io.shareable

import gears.async.Async
import gears.async.Cancellable
import gears.async.CompletionGroup
import gears.async.Listener
import gears.async.io.Deadline
import gears.async.io.Received
import gears.async.io.Repeatable
import gears.async.io.Slot
import gears.async.io.shareable.TimerHeapOp.CancelledSentinel
import gears.async.io.shareable.TimerHeapOp.Outcome

import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.sql.Time
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Try
import scala.util.control.NonFatal

object TimerHeapOp {
  sealed trait Hooks {
    def onEntry(): Unit = ()
  }

  case object CancelledSentinel
  case object TimerFired
  case object CloseFired
  case object ReadyFired
  type State = Try[Unit] | CloseFired.type | TimerFired.type | ReadyFired.type | CancelledSentinel.type | Null
  type Outcome = Try[Unit] | CancelledSentinel.type
}

trait TimerHeapOp extends WorkpoolScheduler.AsyncOp, WorkpoolScheduler.ReentrantOp

sealed abstract class AbstractTimerHeapOp
    extends Async.OriginalSource[Outcome]
    with TimerHeapOp
    with TimerHeapOp.Hooks {

  /** Perform work after a timer has woken and there was no timeout. Result will be used to complete the operation.
    */
  def wakeup(resumedAt: Long): Outcome

  /** Waits for the op's outcome, and returns or throws it. The wait is under no group, so that only the op can end it:
    * a cancel reaches the performer through the op, which `performWith` links to the performer's group, and which
    * settles as cancelled if no worker has claimed it, and otherwise leaves the performer waiting for that worker's
    * outcome, as the work was done. That cancel is not lost: the group stays cancelled.
    *
    * Nothing here recovers from a wait that throws, as there is nothing to recover to: what a fibre's `await` throws
    * while registering its listener leaves through the fibre's boundary, not through this call.
    */
  def parkUntilSettled()(using Async): Unit = {
    val out = state.get() match
      case out: Outcome => out
      case _            => summon[Async].withGroup(CompletionGroup.Unlinked).await(this)
    out match
      case result: Try[Unit]             => result.get
      case TimerHeapOp.CancelledSentinel => throw new CancellationException()
  }

  // async state management

  @volatile private var _cancelLater: Boolean = false
  private val state = AtomicReference[TimerHeapOp.State](null)

  def settled: Boolean = state.get() match
    case null                          => false
    case _: Try[Unit]                  => true
    case TimerHeapOp.CancelledSentinel => true
    case TimerHeapOp.TimerFired        => false
    case TimerHeapOp.CloseFired        => false
    case TimerHeapOp.ReadyFired        => false

  /** Forgets the last performance's outcome, for the next performance of a repeatable op. */
  protected def reset(): Unit = synchronized {
    _cancelLater = false
    state.set(null)
  }

  /** The group this performance is linked to, or `Unlinked` between performances. Guarded by this op's monitor. */
  private var linkedTo: CompletionGroup = CompletionGroup.Unlinked

  override def link(group: CompletionGroup): this.type = synchronized {
    linkedTo = group
    super.link(group)
  }

  protected def cancelledDuringWork: Boolean = {
    _cancelLater
  }

  protected inline def checkCancellationBeforeWork(inline body: Try[Unit]): Try[Unit] | CancelledSentinel.type = {
    if cancelledDuringWork then TimerHeapOp.CancelledSentinel
    else body
  }

  final def cancel(): Unit =
    println(
      s"cancel called on ${this.getClass.getSimpleName}@${System.identityHashCode(this)} on thread ${Thread.currentThread().getName()}"
    )
    // A group cancels the members it had when its cancel began, so this can come after the performance it was meant
    // for has ended, even once the next has begun: it counts only if the group now linked to is cancelled.
    // Then attempts to claim the settlement as cancelled, else sets a request for workers to check before performing
    // work.
    val waiting = synchronized {
      if !linkedTo.isCancelled then null
      else if tryCancel() then removeListener()
      else
        _cancelLater = true
        null
    }
    if waiting != null then waiting.completeNow(TimerHeapOp.CancelledSentinel, this): Unit

  def isCancelled: Boolean = state.get() == TimerHeapOp.CancelledSentinel

  private def completeFromTimer(result: Outcome): Unit = {
    // Settled and its listener taken as one step, which `requestExit` waits for: a performer that sees the outcome and
    // leaves can't have its next performance's listener taken for this one.
    val waiting = synchronized {
      if !state.compareAndSet(TimerHeapOp.TimerFired, result) then throw new IllegalStateException("Did not claim work")
      removeListener()
    }
    if waiting != null then waiting.completeNow(result, this): Unit
  }

  private final def tryCancel(): Boolean = {
    state.compareAndSet(null, TimerHeapOp.CancelledSentinel)
  }

  // gears.async.Source implementation

  @volatile private var listener: Listener[Outcome] | Null = null

  final protected def removeListener(): Listener[Outcome] | Null = synchronized {
    val l = listener
    listener = null
    l
  }

  final override def poll(k: Listener[Outcome]): Boolean =
    pollListener(state.get(), k)

  final override def poll(): Option[Outcome] =
    state.get() match
      case null                                                                     => None
      case data: Try[Unit]                                                          => Some(data)
      case CancelledSentinel                                                        => Some(CancelledSentinel)
      case TimerHeapOp.TimerFired | TimerHeapOp.CloseFired | TimerHeapOp.ReadyFired => None

  private final def pollListener(state: TimerHeapOp.State, k: Listener[Outcome]): Boolean =
    state match
      case null              => false
      case data: Try[Unit]   => k.completeNow(data, this)
      case CancelledSentinel => k.completeNow(CancelledSentinel, this)
      case TimerHeapOp.TimerFired | TimerHeapOp.CloseFired | TimerHeapOp.ReadyFired => false

  final override protected def addListener(k: Listener[Outcome]): Unit = synchronized:
    if listener != null then throw new IllegalStateException("Listener already registered")
    listener = k

  /** Best effort, as `Source` has it: `settle` may already have taken the listener. */
  final override def dropListener(k: Listener[Outcome]): Unit = synchronized:
    if listener eq k then listener = null

  // end gears.async.Source implementation

  // TimerOp implementation

  private val timersLock = new Object

  @volatile private var worker: TimerHeap.TimerSource[?] | Null = null

  @volatile private var timeoutDeadline = -1L

  def minDeadline: Long = timersLock.synchronized(timeoutDeadline)

  final def deadlineNow: Long = timersLock.synchronized(minDeadline)

  def ownDeadline: Long = -1L

  /** Whether `ownDeadline` means anything. It can't say so itself: a deadline that has passed may be negative. */
  protected def hasOwnDeadline: Boolean = false

  final override def hasDeadline: Boolean = hasOwnDeadline || timeoutDeadline >= 0

  final def claimAtDeadline(): Boolean = {
    state.compareAndSet(null, TimerHeapOp.TimerFired)
  }

  final def claimAtCloseFire(): Boolean = {
    state.compareAndSet(null, TimerHeapOp.CloseFired)
  }

  protected def completeFromCloseFire(result: Outcome): Unit = {
    // Settled and its listener taken as one step, which `requestExit` waits for: a performer that sees the outcome and
    // leaves can't have its next performance's listener taken for this one.
    val waiting = synchronized {
      if !state.compareAndSet(TimerHeapOp.CloseFired, result) then throw new IllegalStateException("Did not claim work")
      removeListener()
    }
    if waiting != null then waiting.completeNow(result, this): Unit
  }

  /** Claims the op for a worker that found its handle ready, or for its handle closing: as `claimAtDeadline`. */
  final def claimAtReady(): Boolean = {
    state.compareAndSet(null, TimerHeapOp.ReadyFired)
  }

  protected def completeFromReady(result: Outcome): Unit = {
    // Settled and its listener taken as one step, as in `completeFromTimer`.
    val waiting = synchronized {
      if !state.compareAndSet(TimerHeapOp.ReadyFired, result) then throw new IllegalStateException("Did not claim work")
      removeListener()
    }
    if waiting != null then waiting.completeNow(result, this): Unit
  }

  /** Gives back a claim made with `claimAtReady`, the handle not having been ready after all: the op waits on, and can
    * be claimed again. A cancel asked for while it was claimed could not settle it, so it is settled as cancelled here
    * instead. Returns whether the op still waits.
    */
  final def releaseReady(): Boolean = {
    var stillWaiting = true
    val waiting = synchronized {
      if _cancelLater then
        if !state.compareAndSet(TimerHeapOp.ReadyFired, TimerHeapOp.CancelledSentinel) then
          throw new IllegalStateException("Did not claim work")
        stillWaiting = false
        removeListener()
      else
        if !state.compareAndSet(TimerHeapOp.ReadyFired, null) then throw new IllegalStateException("Did not claim work")
        null
    }
    if waiting != null then waiting.completeNow(TimerHeapOp.CancelledSentinel, this): Unit
    stillWaiting
  }

  final def setTimeout(t: Long, source: TimerHeap.TimerSource[?]): Unit = timersLock.synchronized {
    // Whatever the op's state: it goes on this heap even if it was cancelled as it was entered, and `clearTimeout`
    // must know where to take it from.
    worker = source
    if t < 0 then timeoutDeadline = -1L
    else timeoutDeadline = TimerHeap.deadlineAfter(t * 1000000L)
  }

  /** Puts the op, which has a time limit, on `source`'s heap, and has `clearTimeout` take it from there. */
  final def holdTimeoutOn(source: EventSource): Unit =
    val heap = source.holdTimer(this)
    timersLock.synchronized { worker = heap }

  /** Takes the op off its heap. The time limit stays until the next `setTimeout`: a firing still under way, which
    * `parkUntilSettled` waits for, reads it to tell whether it is the limit that has passed.
    */
  final def clearTimeout(): Unit =
    val w = timersLock.synchronized {
      val w = worker
      worker = null
      w
    }
    if w != null then w.removeTimer(this)

  /** Takes no op lock: the heap reads `deadlineNow` under its own lock, so that order is heap, then op. */
  final def requestTimersUpdate(): Unit = {
    val w = worker
    if w != null then w.notifyDeadlineChange(this)
  }

  /** Woken from the timer heap after provably entering on its deadline firing. (i.e. will not enter if already
    * cancelled). Always runs with `state == TimerHeapOp.TimerFired`.
    */
  final def timerWakeup(resumedAt: Long): Unit =
    println(s"timerWakeup called on thread ${Thread.currentThread().getName}")
    // Thread.sleep(1000)
    val timeoutDeadline0 = timeoutDeadline
    val ownDeadline0 = ownDeadline
    // The limit decides only if it has passed and came first: at a tie the op has made its own deadline.
    // A throwing `wakeup` becomes the outcome, so that the op is settled whatever happens.
    val result =
      try
        if timeoutDeadline0 >= 0 && resumedAt >= timeoutDeadline0 && (ownDeadline0 < 0 || timeoutDeadline0 < ownDeadline0)
        then Failure(new TimeoutException("operation timed out"))
        else wakeup(resumedAt)
      catch
        case NonFatal(ex)     => Failure(ex)
        case fatal: Throwable =>
          // Not handled here, and thrown on: but an op left claimed would keep its performer waiting for ever, with
          // no cancel able to release it.
          completeFromTimer(Failure(fatal))
          throw fatal
    completeFromTimer(result)

  // end TimerOp implementation
}

abstract class SingleUseAbstractTimerHeapOp extends AbstractTimerHeapOp {
  private val spentState = AtomicInteger(0)
  final def requestEntry()(using Async): Unit = {
    if !spentState.compareAndSet(0, 1) then throw new IllegalStateException("Already consumed")
    try
      println(
        s"requestEntry on ${this.getClass.getSimpleName}@${System.identityHashCode(this)} called on thread ${Thread.currentThread().getName}"
      )
      onEntry()
    catch
      case failure: Throwable =>
        // Not entered after all, so not linked either: `requestExit` will not be called to undo it.
        spentState.set(0)
        throw failure
  }
  final def requestExit()(using Async): Unit = {
    val entered = spentState.compareAndSet(1, 2) // Attempt to mark as exited
    if !entered then
      val v = spentState.get()
      if v == 0 then throw new IllegalStateException("Not entered")
      else throw new IllegalStateException("Already exited")

    println(
      s"requestExit on ${this.getClass.getSimpleName}@${System.identityHashCode(this)} called on thread ${Thread.currentThread().getName}"
    )
    val _ = removeListener()
  }
}

abstract class RepeatableAbstractTimerHeapOp extends AbstractTimerHeapOp with Repeatable {
  private val entered = AtomicBoolean(false)
  final def requestEntry()(using Async): Unit = {
    if !entered.compareAndSet(false, true) then throw new IllegalStateException("Concurrent entry not allowed")
    try {
      println(
        s"requestEntry on ${this.getClass.getSimpleName}@${System.identityHashCode(this)} called on thread ${Thread.currentThread().getName}"
      )
      reset()
      onEntry()
    } catch {
      case failure: Throwable =>
        // Not entered after all, so not linked either: `requestExit` will not be called to undo it.
        entered.set(false)
        throw failure
    }
  }
  final def requestExit()(using Async): Unit = {
    if !entered.get() then throw new IllegalStateException("Not entered")
    try {
      println(
        s"requestExit on ${this.getClass.getSimpleName}@${System.identityHashCode(this)} called on thread ${Thread.currentThread().getName}"
      )
      val _ = removeListener()
    } finally {
      // Last: a performer let in any sooner would have its listener and its link taken by the lines above.
      entered.set(false)
    }
  }
}

sealed trait TimeoutOnWake extends TimerHeapOp {
  final def wakeup(resumedAt: Long): Outcome = Failure(new TimeoutException("Operation timed out"))
}

sealed abstract class ImmediateOp extends SingleUseAbstractTimerHeapOp, TimeoutOnWake {
  def perform(): Unit
}

abstract class AbstractCloseOp(val handle: BaseHandle)
    extends SingleUseAbstractTimerHeapOp,
      TimeoutOnWake,
      CloseListener {

  /** Whether the handle is closed already. If not, work claimed on it is still under way, and the last of it to be
    * released closes the handle and tells this op, its one owner.
    */
  final def pollClose(): Boolean =
    if handle.requestClose() then true
    else
      handle.onClosed(this)
      false

  /** The op is performed once, so this can only be for the performance that waits; it may by then have been
    * cancelled or timed out, and the handle closes all the same.
    */
  final def handleClosed(): Unit =
    if claimAtCloseFire() then completeFromCloseFire(WorkpoolScheduler.SuccessUnit)
}

/** An op that does its work when its handle is ready for it: tried at once by its performer and, if the handle was not
  * ready, again by the worker whose event source reports that it is. `W` is that event source.
  */
abstract class AbstractReadinessOp[H <: BaseHandle, W <: EventSource](handle: H)
    extends RepeatableAbstractTimerHeapOp,
      TimeoutOnWake,
      ReadinessOp { op =>

  /** One attempt at the work, which must not block, with the handle's work claimed: how many bytes it moved, which
    * may be none, as at the end of a stream, or `Blocked` if the handle was not ready, and nothing was done.
    */
  protected def attempt(): Int

  /** Whether the op waits for the handle to be readable, rather than writable. */
  protected def isRead: Boolean

  final def handleClosing(): Unit =
    if claimAtReady() then completeFromReady(Failure(new ClosedChannelException()))

  private def attemptClaimed(): Int =
    handle.claimWork()
    try attempt()
    finally handle.releaseWork()

  final def actImmediate(): Boolean = attemptClaimed() != AbstractReadinessOp.Blocked

  final def readyWakeup(available: Long, source: EventSource): Boolean =
    val result: Outcome | Null =
      try
        if cancelledDuringWork then TimerHeapOp.CancelledSentinel
        else
          val moved = attemptClaimed()
          if moved == AbstractReadinessOp.Blocked then null
          else
            // All that was there as the source took its report: whatever comes after it is reported anew, so until
            // then there is nothing, and the next op need not look. Not at the end of a stream, where nothing was
            // moved, since no report follows it.
            if moved > 0 && moved >= available then handle.markDrained(isRead, source)
            WorkpoolScheduler.SuccessUnit
      catch
        case NonFatal(ex)     => Failure(ex)
        case fatal: Throwable =>
          // As in `timerWakeup`: an op left claimed would keep its performer waiting for ever.
          completeFromReady(Failure(fatal))
          throw fatal
    if result == null then false
    else
      completeFromReady(result)
      true

  /** Does the work at once, which most often does it, or else waits for the handle's listener to find the handle
    * ready and do it, for the handle to close, or for the op's time limit or cancel. The listener is `self`, the
    * performer's worker, if the handle had none, and from then on whichever worker that was: it does the work and
    * resumes the performer, which goes on from there on that worker.
    */
  final def performWhenReady(self: W)(using Async): Unit =
    var waiting = false
    var done = false
    while !done && !waiting do
      val tick = handle.readinessTick(isRead)
      handle.claimWork()
      try
        // A handle known to be drained is not tried: its listener reports it when there is something again.
        if !handle.drainedAt(isRead, tick) && attempt() != AbstractReadinessOp.Blocked then done = true
        else
          val listener = handle.awaitReadiness(isRead, this, tick, self)
          if listener != null then // else reported ready since the tick was read: again
            waiting = true
            try
              // On the listener's heap, not the performer's: a claim the listener gives back can't then have kept
              // the time limit from firing meanwhile, as one thread does both.
              if hasDeadline then holdTimeoutOn(listener)
            catch
              case failure: Throwable =>
                handle.clearWaiter(isRead, this): Unit
                throw failure
      finally handle.releaseWork()
    if waiting then
      try parkUntilSettled()
      finally handle.clearWaiter(isRead, this): Unit
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
abstract class AbstractStreamReadOp[H <: BaseReadableHandle, W <: EventSource](handle: H)
    extends AbstractReadinessOp[H, W](handle),
      NonBlockingReadOps {

  type ReadableHandle = H
  type WorkerSource = W

  protected final def isRead: Boolean = true
}

abstract class AbstractReadOp[H <: BaseReadableHandle, W <: EventSource](handle: H, buf: ByteBuffer)
    extends AbstractStreamReadOp[H, W](handle) {
  if buf.isReadOnly() then throw new IllegalArgumentException("A read needs a buffer it can write to")

  override def onEntry(): Unit =
    super.onEntry()
    if !buf.hasRemaining() then throw new IllegalArgumentException("A read needs a buffer with room")

  protected final def attempt(): Int = AbstractStreamReadOp.moved(_readHandle(handle, buf))
}

/** A scattering read: into `bufs` in order, each filled before the next is begun. */
abstract class AbstractScatterReadOp[H <: BaseReadableHandle, W <: EventSource](handle: H, bufs: Array[ByteBuffer])
    extends AbstractStreamReadOp[H, W](handle) {
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
abstract class AbstractPooledReadOp[H <: BaseReadableHandle, W <: EventSource](
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
abstract class AbstractStreamWriteOp[H <: BaseWritableHandle, W <: EventSource](handle: H)
    extends AbstractReadinessOp[H, W](handle),
      NonBlockingWriteOps {

  type WriteableHandle = H
  type WorkerSource = W

  protected final def isRead: Boolean = false
}

abstract class AbstractWriteOp[H <: BaseWritableHandle, W <: EventSource](handle: H, buf: ByteBuffer)
    extends AbstractStreamWriteOp[H, W](handle) {

  /** A write that would block is -1, which is `Blocked`; with nothing to write, the op is done, moving nothing. */
  protected final def attempt(): Int = if buf.hasRemaining() then _writeHandle(handle, buf) else 0
}

/** A gathering write: from `bufs` in order, each drained before the next is begun. */
abstract class AbstractGatherWriteOp[H <: BaseWritableHandle, W <: EventSource](handle: H, bufs: Array[ByteBuffer])
    extends AbstractStreamWriteOp[H, W](handle) {

  protected final def attempt(): Int = if bufs.exists(_.hasRemaining()) then _writevHandle(handle, bufs) else 0
}

trait PipeOpener:
  type PipeReadable <: BaseReadableHandle
  type PipeWritable <: BaseWritableHandle
  def pipe(): (read: PipeReadable, write: PipeWritable)

abstract class AbstractMakePipeOp[PR <: BaseReadableHandle, PW <: BaseWritableHandle](
    readSlot: Slot[PR],
    writeSlot: Slot[PW]
) extends ImmediateOp,
      PipeOpener {
  type PipeReadable <: PR
  type PipeWritable <: PW

  /** Before anything is opened: an occupied slot could not take its end. */
  override def onEntry(): Unit =
    super.onEntry()
    if !readSlot.isEmpty || !writeSlot.isEmpty then
      throw new IllegalArgumentException("A pipe needs two empty slots")

  final def perform(): Unit =
    val pair = pipe()
    readSlot.set(pair.read)
    writeSlot.set(pair.write)
}

abstract class AbstractMakeTimerOp(duration: FiniteDuration) extends RepeatableAbstractTimerHeapOp {

  /** When this performance's `duration` is up: a timer counts from each submission. */
  @volatile private var initialDeadline: Long = -1L

  override def onEntry(): Unit = {
    super.onEntry()
    initialDeadline = TimerHeap.deadlineAfter(duration.toNanos)
  }

  override def minDeadline: Long = {
    val sup = super.minDeadline
    if sup >= 0 then return math.min(sup, initialDeadline)
    else initialDeadline
  }

  override def ownDeadline: Long = initialDeadline

  override protected def hasOwnDeadline: Boolean = true

  def wakeup(resumedAt: Long): Try[Unit] | CancelledSentinel.type =
    checkCancellationBeforeWork(WorkpoolScheduler.SuccessUnit)
}

abstract class AbstractMakeDeadlineOp(initial: Long) extends RepeatableAbstractTimerHeapOp with Deadline {
  private val myDeadline = AtomicLong(initial)

  override def deadline: Long = myDeadline.get()

  override def moveTo(nanoTime: Long): Unit =
    if nanoTime < 0 then throw new IllegalArgumentException("Deadline must be non-negative")
    // Compared with the very deadline it replaces: of movers racing each other, whichever leaves the deadline earlier
    // than the heap knows of is then sure to tell it.
    val earlier = nanoTime < myDeadline.getAndSet(nanoTime)
    // The worker may be sleeping until the old deadline. If it is moved later, the heap finds out when that comes round.
    if earlier then requestTimersUpdate()

  override def minDeadline: Long = {
    val sup = super.minDeadline
    val own = myDeadline.get()
    if sup >= 0 then return math.min(sup, own)
    else own
  }

  override def ownDeadline: Long = myDeadline.get()

  override protected def hasOwnDeadline: Boolean = true

  def wakeup(resumedAt: Long): Try[Unit] | CancelledSentinel.type =
    checkCancellationBeforeWork(WorkpoolScheduler.SuccessUnit)
}
