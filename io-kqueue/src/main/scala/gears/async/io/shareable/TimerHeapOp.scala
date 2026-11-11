package gears.async.io.shareable

import gears.async.Async
import gears.async.CompletionGroup
import gears.async.Listener
import gears.async.io.Deadline
import gears.async.io.Repeatable
import gears.async.io.shareable.TimerHeapOp.CancelledSentinel
import gears.async.io.shareable.TimerHeapOp.Outcome

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
  type State = Try[Unit] | TimerFired.type | CancelledSentinel.type | Null
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
      case null                   => None
      case data: Try[Unit]        => Some(data)
      case CancelledSentinel      => Some(CancelledSentinel)
      case TimerHeapOp.TimerFired => None

  private final def pollListener(state: TimerHeapOp.State, k: Listener[Outcome]): Boolean =
    state match
      case null                   => false
      case data: Try[Unit]        => k.completeNow(data, this)
      case CancelledSentinel      => k.completeNow(CancelledSentinel, this)
      case TimerHeapOp.TimerFired => false

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

  final def setTimeout(t: Int, source: TimerHeap.TimerSource[?]): Unit = timersLock.synchronized {
    // Whatever the op's state: it goes on this heap even if it was cancelled as it was entered, and `clearTimeout`
    // must know where to take it from.
    worker = source
    if t < 0 then timeoutDeadline = -1L
    else timeoutDeadline = TimerHeap.deadlineAfter(t * 1000000L)
  }

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
    try onEntry()
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
