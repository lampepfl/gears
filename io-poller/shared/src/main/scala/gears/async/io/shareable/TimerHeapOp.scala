package gears.async.io.shareable

import gears.async.Async
import gears.async.CompletionGroup
import gears.async.Listener
import gears.async.io.Repeatable
import gears.async.io.shareable.TimerHeapOp.CancelledSentinel
import gears.async.io.shareable.TimerHeapOp.Outcome

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.TimeoutException
import scala.util.Failure
import scala.util.Try
import scala.util.control.NonFatal

object TimerHeapOp {
  trait Hooks {
    def onEntry(): Unit = ()
  }

  case object CancelledSentinel
  case object TimerFired
  case object CloseFired
  case object ReadyFired
  case object WorkFired
  type State = Try[Unit] | CloseFired.type | TimerFired.type | ReadyFired.type | CancelledSentinel.type | Null
  type Outcome = Try[Unit] | CancelledSentinel.type
}

trait TimerHeapOp extends WorkpoolScheduler.AsyncOp, WorkpoolScheduler.ReentrantOp {

  /** Where this op's deadline or time limit can be kept: its backend's workers, whose timers its ops are. */
  type Source <: TimerHeap.TimerSource

  /** Puts this op among `source`'s timers, and nothing more. For the op's own backend to define, which knows that its
    * workers take its ops; everything else goes through `holdTimerOn`.
    */
  def _holdTimer(source: Source): Unit

  /** Puts the op, which has a deadline or a time limit, among `source`'s timers, where `clearTimeout` then takes it
    * from: the only way an op goes on a heap.
    */
  def holdTimerOn(source: Source): Unit
}

abstract class AbstractTimerHeapOp private[shareable] ()
    extends Async.Source[Outcome]
    with TimerHeapOp
    with TimerHeapOp.Hooks {

  /** Perform work after a timer has woken and there was no timeout. Result will be used to complete the operation.
    */
  def wakeup(resumedAt: Long): Outcome

  /** Waits for the op's outcome, and returns or throws it. The wait is under the performer's own group, so a cancel
    * ends it; what the cancel does to the op is then decided here, on the performer's own thread: if nothing has
    * claimed the op it is settled as cancelled, and otherwise the performer waits on, under no group, for whoever
    * claimed it, since the work was done, and returns that outcome. That cancel is not lost: the group stays cancelled.
    * Nothing links the op itself to the group, so no other thread ever cancels it.
    */
  def parkUntilSettled()(using async: Async): Unit = {
    exposed = true
    val out =
      outcomeOf(result.get()) match
        case null =>
          waited = true
          try Parking.parkOn(this)
          catch
            case _: CancellationException =>
              if cancelByPerformer() then TimerHeapOp.CancelledSentinel
              else Parking.parkUncancelled(this) // claimed: its outcome is the op's
        case outcome => outcome
    out match
      case result: Try[Unit]             => result.get
      case TimerHeapOp.CancelledSentinel => throw new CancellationException()
  }

  // async state management

  /** What a performer that is cancelled does to its op, as `parkUntilSettled` does it, which is the only way an op is
    * cancelled: word is left for whoever has claimed the op, to see before it does the work, and the op is settled as
    * cancelled if nobody has. Whether it was settled here.
    */
  private[io] final def cancelByPerformer(): Boolean =
    _cancelLater = true
    settleCancelled()

  /** A cancel that came while the op was claimed, which whoever claimed it looks for before working. */
  @volatile private var _cancelLater: Boolean = false

  /** Who may settle this performance: null while anyone may claim it, then the marker of whoever did, a firing timer, a
    * ready handle, a close or a cancel. Only its holder settles the op.
    */
  private val claim = AtomicReference[AnyRef | Null](null)

  /** Null, then the waiting performer's listener if it waits, then the outcome. Settling swaps the outcome in and takes
    * the listener out as one step, so nothing of a performance is left to do once its outcome shows: a performer that
    * sees it may leave, and perform the op again, with no lock to wait on.
    */
  private val result = AtomicReference[AnyRef | Null](null)

  private def outcomeOf(value: AnyRef | Null): Outcome | Null = value match
    case outcome: Try[?]               => outcome.asInstanceOf[Try[Unit]]
    case TimerHeapOp.CancelledSentinel => TimerHeapOp.CancelledSentinel
    case _                             => null

  def settled: Boolean = outcomeOf(result.get()) != null

  /** Forgets the last performance, for the next of a repeatable op. Each word is written only if it has to be: an op
    * that was done as it was tried left them as they were.
    */
  protected def reset(): Unit = {
    exposed = false
    if waited then waited = false
    if _cancelLater then _cancelLater = false
    if claim.get() != null then claim.set(null)
    if result.get() != null then result.set(null)
  }

  /** Gives the waiting performer its outcome, which resumes it. */
  private def tell(waiting: Listener[Outcome], result: Outcome): Unit =
    waiting.completeNow(result, this): Unit

  /** Whether this performance may have been given to something that can settle it: a worker's heap, its handle as a
    * waiter, or a close. The performer's alone to read and write.
    */
  private var exposed = false

  /** Whether this performance suspended its performer to wait for the op, which one done as it was tried did not. The
    * performer's alone to read and write.
    */
  private var waited = false

  /** Whether this performance suspended its performer (see `waited`): for a loop that counts the ops that did not, as
    * each of those left it no turn.
    */
  final def suspendedPerformer: Boolean = waited

  /** Before the op is given to something that can settle it other than a heap, which `hasDeadline` tells of. */
  protected final def expose(): Unit = exposed = true

  /** As the performer leaves. One that leaves with the op's outcome leaves nothing behind. One that leaves without,
    * because something threw after the op was given to what could settle it, must not have it settled later, into the
    * next performance: it claims the op itself, or waits for whoever already has.
    */
  protected final def leave()(using async: Async): Unit =
    if (exposed || hasDeadline) && outcomeOf(result.get()) == null then
      if !settleCancelled() then Parking.parkUncancelled(this): Unit

  protected def cancelledDuringWork: Boolean = {
    _cancelLater
  }

  protected inline def checkCancellationBeforeWork(inline body: Try[Unit]): Try[Unit] | CancelledSentinel.type = {
    if cancelledDuringWork then TimerHeapOp.CancelledSentinel
    else body
  }

  private def settleCancelled(): Boolean =
    if claim.compareAndSet(null, TimerHeapOp.CancelledSentinel) then
      settle(TimerHeapOp.CancelledSentinel)
      true
    else false

  /** For the holder of the claim: the outcome in, and the performer told if it waits. */
  private def settle(outcome: Outcome): Unit =
    result.getAndSet(outcome) match
      case waiting: Listener[?] => tell(waiting.asInstanceOf[Listener[Outcome]], outcome)
      case _                    => ()

  private def settleHolding(marker: AnyRef, outcome: Outcome): Unit =
    if claim.get() ne marker then throw new IllegalStateException("Did not claim work")
    settle(outcome)

  private def completeFromTimer(result: Outcome): Unit = settleHolding(TimerHeapOp.TimerFired, result)

  // gears.async.Source implementation

  final override def poll(k: Listener[Outcome]): Boolean =
    val outcome = outcomeOf(result.get())
    outcome != null && k.completeNow(outcome, this)

  final override def poll(): Option[Outcome] =
    val outcome = outcomeOf(result.get())
    if outcome == null then None else Some(outcome)

  /** One listener at a time, the performer's: registered if there is no outcome yet, and otherwise given it. */
  final override def onComplete(k: Listener[Outcome]): Unit =
    if !result.compareAndSet(null, k) then
      val outcome = outcomeOf(result.get())
      if outcome == null then throw new IllegalStateException("Listener already registered")
      tell(k, outcome)

  /** Best effort, as `Source` has it: whoever settles the op may already have taken the listener. */
  final override def dropListener(k: Listener[Outcome]): Unit = result.compareAndSet(k, null): Unit

  // end gears.async.Source implementation

  // TimerOp implementation

  // Both are written by the performer alone, one performance at a time, and read by whichever worker's heap holds the
  // op: volatile, with no lock, as each is read on its own, and a heap asks for the deadline with its own lock held.

  /** The heap the op is on, if it is on one. */
  @volatile private var worker: TimerHeap.TimerSource | Null = null

  @volatile private var timeoutDeadline = -1L

  def minDeadline: Long = timeoutDeadline

  final def deadlineNow: Long = minDeadline

  def ownDeadline: Long = -1L

  /** Whether `ownDeadline` means anything. It can't say so itself: a deadline that has passed may be negative. */
  protected def hasOwnDeadline: Boolean = false

  final override def hasDeadline: Boolean = hasOwnDeadline || timeoutDeadline >= 0

  final def claimAtDeadline(): Boolean = {
    claim.compareAndSet(null, TimerHeapOp.TimerFired)
  }

  final def claimAtCloseFire(): Boolean = {
    claim.compareAndSet(null, TimerHeapOp.CloseFired)
  }

  protected def completeFromCloseFire(result: Outcome): Unit = settleHolding(TimerHeapOp.CloseFired, result)

  /** Claims the op for a worker that found its handle ready, or for its handle closing: as `claimAtDeadline`. */
  final def claimAtReady(): Boolean = {
    claim.compareAndSet(null, TimerHeapOp.ReadyFired)
  }

  protected def completeFromReady(result: Outcome): Unit = settleHolding(TimerHeapOp.ReadyFired, result)

  /** Claims the op for the thread that did its work elsewhere (see `WorkOffThread`): as `claimAtDeadline`. */
  protected final def claimAtWork(): Boolean = claim.compareAndSet(null, TimerHeapOp.WorkFired)

  protected final def completeFromWork(result: Outcome): Unit = settleHolding(TimerHeapOp.WorkFired, result)

  /** Gives back a claim made with `claimAtReady`, the handle not having been ready after all: the op waits on, and can
    * be claimed again. A cancel that came while it was claimed could not settle it, so it is settled as cancelled here
    * instead: the performer, having left word in `_cancelLater`, tries the claim again itself, and whichever of the two
    * sees the other's write gets it. Returns whether the op still waits.
    */
  final def releaseReady(): Boolean = {
    if claim.get() ne TimerHeapOp.ReadyFired then throw new IllegalStateException("Did not claim work")
    claim.set(null)
    if _cancelLater then settleCancelled(): Unit
    outcomeOf(result.get()) == null
  }

  /** Sets this performance's time limit, or none if `t` is negative. It says nothing of where the op waits: an op goes
    * on a heap, if it has to, with `holdTimerOn`. With no limit now and none before there is nothing to write.
    */
  final def setTimeout(t: Long): Unit =
    if t >= 0 || timeoutDeadline >= 0 then
      if t < 0 then timeoutDeadline = -1L
      else timeoutDeadline = TimerHeap.deadlineAfter(t * 1000000L)

  final def holdTimerOn(source: Source): Unit = {
    // Before it is there: `clearTimeout` must know where to take it from, whatever comes of putting it.
    worker = source
    _holdTimer(source)
  }

  /** Takes the op off its heap. The time limit stays until the next `setTimeout`: a firing still under way, which
    * `parkUntilSettled` waits for, reads it to tell whether it is the limit that has passed.
    */
  final def clearTimeout(): Unit =
    val w = worker
    if w != null then // else it was on no heap: see `holdTimerOn`
      worker = null
      w.removeTimer(this): Unit

  final def requestTimersUpdate(): Unit = {
    val w = worker
    if w != null then w.notifyDeadlineChange(this)
  }

  /** Woken from the timer heap after provably entering on its deadline firing. (i.e. will not enter if already
    * cancelled). Always runs holding the claim, as `TimerHeapOp.TimerFired`.
    */
  final def timerWakeup(resumedAt: Long): Unit =
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
    leave()
  }
}

abstract class RepeatableAbstractTimerHeapOp extends AbstractTimerHeapOp with Repeatable {
  private val entered = AtomicBoolean(false)
  final def requestEntry()(using Async): Unit = {
    if !entered.compareAndSet(false, true) then throw new IllegalStateException("Concurrent entry not allowed")
    try {
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
      leave()
    } finally {
      // Last: a performer let in any sooner would find this one still leaving.
      entered.lazySet(false)
    }
  }
}

trait TimeoutOnWake extends TimerHeapOp {
  final def wakeup(resumedAt: Long): Outcome = Failure(new TimeoutException("Operation timed out"))
}
