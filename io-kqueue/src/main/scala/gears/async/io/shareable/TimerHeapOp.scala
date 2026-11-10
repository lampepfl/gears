package gears.async.io.shareable

import gears.async.Async
import gears.async.CompletionGroup
import gears.async.Listener
import gears.async.io.Deadline
import gears.async.io.Repeatable
import gears.async.io.shareable.TimerHeapOp.CancelledSentinel

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
}

trait TimerHeapOp extends WorkpoolScheduler.AsyncOp, WorkpoolScheduler.ReentrantOp

sealed abstract class AbstractTimerHeapOp
    extends Async.OriginalSource[Try[Unit]]
    with TimerHeapOp
    with TimerHeapOp.Hooks {

  /** Perform work after a timer has woken and there was no timeout. Result will be used to complete the operation.
    */
  def wakeup(resumedAt: Long): Try[Unit]

  /** Waits for the op's outcome, and returns or throws it. The outcome is the op's own, not what the `await` made of
    * it: if the wait is cancelled, or fails in any other way, the op is settled first, so that the performer never
    * leaves with a worker still firing it. A cancelled wait then gives whatever the op settled as, which is its work's
    * outcome if a worker had already claimed it, and the wait's own cancellation if not; any other failure of the wait
    * is rethrown once the op has settled, carrying as suppressed the op's own failure, if it settled as one, or a
    * failure to settle it.
    */
  def parkUntilSettled()(using Async): Unit = {
    val outcome =
      try summon[Async].await(this)
      catch
        case cancel: CancellationException =>
          ensureSettled() match
            // Cancelled in time: the wait's own exception says so, with the trace of where it was waiting.
            case Failure(_: CancellationException) => throw cancel
            // Claimed before the cancel: the op's outcome stands. The cancel is not lost, as the group stays cancelled.
            case settled => settled
        case NonFatal(failure) =>
          try
            ensureSettled() match
              // The op's own failure goes with the wait's. A cancellation is no news: settling an unclaimed op makes one.
              case Failure(outcome) if !outcome.isInstanceOf[CancellationException] && (outcome ne failure) =>
                failure.addSuppressed(outcome)
              case _ => ()
          catch
            case NonFatal(unsettled) if (unsettled ne failure) =>
              failure.addSuppressed(unsettled)
          throw failure
    outcome.get
  }

  /** Settles the op if nothing has claimed it, and otherwise waits for whoever claimed it to settle it. The op may
    * still be on its heap: settling it as cancelled here is what keeps the heap from claiming it, as both go for the
    * same state, and only one can win.
    */
  private final def ensureSettled()(using Async): Try[Unit] =
    println(
      s"ensureSettled called on ${this.getClass.getSimpleName}@${System.identityHashCode(this)} on thread ${Thread.currentThread().getName()}"
    )
    if !tryCancel() && (state.get() eq TimerHeapOp.TimerFired) then
      val _ = removeListener() // the performer's own cancelled listener may still be there
      // Not under the performer's group, which may be cancelled, and would not wait.
      val _ = summon[Async].withGroup(CompletionGroup.Unlinked).await(this)
    settledResult() // settled by now, one way or another

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

  protected inline def checkCancellationBeforeWork(inline body: Try[Unit]): Try[Unit] = {
    if cancelledDuringWork then Failure(new CancellationException("Operation cancelled"))
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
    if waiting != null then completeListener(TimerHeapOp.CancelledSentinel, waiting): Unit

  def cancelledImmediately: Boolean = state.get() == TimerHeapOp.CancelledSentinel

  /** Settles the op with the given result. Throws if not completed from the timer state. */
  private def completeFromTimer(result: Try[Unit]): Unit = {
    // Settled and its listener taken as one step, which `requestExit` waits for: a performer that sees the outcome and
    // leaves can't have its next performance's listener taken for this one.
    val waiting = synchronized {
      if !state.compareAndSet(TimerHeapOp.TimerFired, result) then throw new IllegalStateException("Did not claim work")
      removeListener()
    }
    if waiting != null then completeListener(result, waiting): Unit
  }

  private final def tryCancel(): Boolean = {
    state.compareAndSet(null, TimerHeapOp.CancelledSentinel)
  }

  // gears.async.Source implementation

  @volatile private var listener: Listener[Try[Unit]] | Null = null

  final protected def removeListener(): Listener[Try[Unit]] | Null = synchronized {
    val l = listener
    listener = null
    l
  }

  final override def poll(k: Listener[Try[Unit]]): Boolean =
    completeListener(state.get(), k)

  final override def poll(): Option[Try[Unit]] =
    state.get() match
      case null                   => None
      case data: Try[Unit]        => Some(data)
      case CancelledSentinel      => Some(Failure(new CancellationException()))
      case TimerHeapOp.TimerFired => None

  final def settledResult(): Try[Unit] =
    state.get() match
      case null                   => Failure(new IllegalStateException("Op not settled"))
      case data: Try[Unit]        => data
      case CancelledSentinel      => Failure(new CancellationException())
      case TimerHeapOp.TimerFired => Failure(new IllegalStateException("Op not settled"))

  private final def completeListener(state: TimerHeapOp.State, k: Listener[Try[Unit]]): Boolean =
    state match
      case null                   => false
      case data: Try[Unit]        => k.completeNow(data, this)
      case CancelledSentinel      => k.completeNow(Failure(new CancellationException()), this)
      case TimerHeapOp.TimerFired => false

  final override protected def addListener(k: Listener[Try[Unit]]): Unit = synchronized:
    if listener != null then throw new IllegalStateException("Listener already registered")
    listener = k

  /** Best effort, as `Source` has it: `settle` may already have taken the listener. */
  final override def dropListener(k: Listener[Try[Unit]]): Unit = synchronized:
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
        case NonFatal(ex) => Failure(ex)
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
    link()
  }
  final def requestExit()(using Async): Unit = {
    try {
      val entered = spentState.compareAndSet(1, 2) // Attempt to mark as exited
      if !entered then
        val v = spentState.get()
        if v == 0 then throw new IllegalStateException("Not entered")
        else throw new IllegalStateException("Already exited")
      val _ = removeListener()
    } finally {
      unlink()
    }
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
    link()
  }
  final def requestExit()(using Async): Unit = {
    if !entered.get() then throw new IllegalStateException("Not entered")
    try {
      println(
        s"requestExit on ${this.getClass.getSimpleName}@${System.identityHashCode(this)} called on thread ${Thread.currentThread().getName}"
      )
      val _ = removeListener()
    } finally {
      unlink()
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

  def wakeup(resumedAt: Long): Try[Unit] =
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

  def wakeup(resumedAt: Long): Try[Unit] =
    checkCancellationBeforeWork(WorkpoolScheduler.SuccessUnit)
}
