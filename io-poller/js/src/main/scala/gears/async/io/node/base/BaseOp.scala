package gears.async.io.node.base

import gears.async.Async
import gears.async.io.shareable
import gears.async.io.shareable.WorkpoolScheduler

import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import scala.util.Failure

/** An op of this backend, as the shared layer has ops (`AbstractTimerHeapOp`): how it is entered and left, claimed and
  * settled, limited in time and cancelled are the same as on every platform. Its timers are kept on the backend's one
  * event source.
  */
trait BaseOp extends shareable.BackendOp[NodeSource.type] {
  final def _holdTimer(source: NodeSource.type): Unit = source.addTimer(this)

  /** Whether this performance suspended its performer, and so left the event loop a turn. */
  def suspendedPerformer: Boolean
}

/** What is Node's own among ops: one whose work is a call of the event loop's that calls back, where on a platform with
  * threads it would be done on another thread (`WorkOffThread`). The op is settled from the callback, with the shared
  * layer's claim for work done elsewhere.
  *
  * Until the callback, the op either only waits, and a cancel or its time limit settles it at once, or it has been
  * claimed for the call (`claimForCall`), which Node cannot take back: what comes for it meanwhile is kept, for the
  * callback to decide on (`interruptedBy`).
  */
trait CallState extends shareable.AbstractTimerHeapOp, shareable.TimeoutOnWake, BaseOp {

  /** Whether the op is claimed for a call under way. */
  private var calling = false

  override protected def reset(): Unit = {
    calling = false
    super.reset()
  }

  /** Whether the op is being performed and has no outcome yet. */
  protected final def pending: Boolean = !settled

  /** Claims the op for work the event loop cannot take back, from its performer. */
  protected final def claimForCall(): Unit = {
    if !claimAtWork() then throw new IllegalStateException(s"$this is not waiting")
    calling = true
    expose()
  }

  /** Settles the op with its work's outcome, a failure or null for none, if it is still being performed: from the call
    * it was claimed for, or, for one that only waited, if nothing else has settled it first.
    */
  protected final def complete(failure: Throwable | Null): Unit = {
    val outcome = if failure == null then WorkpoolScheduler.SuccessUnit else Failure(failure)
    if calling then {
      calling = false
      completeFromWork(outcome)
    } else if !settled && claimAtWork() then completeFromWork(outcome)
  }

  /** The call the op was claimed for was not made after all: it is settled with why. */
  protected final def abandonCall(failure: Throwable): Unit =
    if calling then {
      calling = false
      completeFromWork(Failure(failure))
    }

  /** What came for the op while its work was being done and could not stop it, a cancel or its time limit; null if
    * nothing did. Whoever does the work decides what it means for the outcome.
    */
  protected final def interruptedBy: Throwable | Null =
    if cancelledDuringWork then new CancellationException()
    else {
      val limit = minDeadline
      if hasDeadline && System.nanoTime() - limit >= 0 then new TimeoutException(s"$this timed out") else null
    }

  override def toString: String = getClass.getSimpleName
}

/** An op performed once: performed again, or by a second performer at once, it is refused. */
abstract class SingleUseOp extends shareable.SingleUseAbstractTimerHeapOp, CallState

/** An op that may be performed again once its performance has ended, by one performer at a time. */
abstract class RepeatableOp extends shareable.RepeatableAbstractTimerHeapOp, CallState

/** An op whose work is a call of the event loop's that calls back: it waits for that, on a handle or to open one. Mixed
  * into an op that is performed once, or one that is repeatable.
  */
trait CallOp extends CallState {

  /** Makes the call. A failure thrown is the op's. */
  protected def call(): Unit

  /** Called once the performance is over, however it ended: undoes what a call that was abandoned left behind. */
  protected def finish(): Unit = ()

  /** The owner of the handles of the run performing the op: what a handle the op opens, in a callback long after, is
    * made with.
    */
  protected final def runOwner: shareable.HandleOwner = performedFor.nn

  private var performedFor: shareable.HandleOwner | Null = null

  /** Called as the op begins to be performed, before its call: refuses what is not this run's. */
  protected def admit(owner: shareable.HandleOwner): Unit = ()

  /** Makes the call and waits for it, the op's time limit running. A call that throws once the op was claimed for it
    * was never made: the op is settled with what it threw, so that nothing is left claimed.
    */
  final def performCall(source: NodeSource.type)(using Async): Unit =
    try {
      val owner = source.handleOwner
      performedFor = owner
      admit(owner)
      try call()
      catch {
        case failure: Throwable =>
          abandonCall(failure)
          throw failure
      }
      if !settled then {
        expose()
        if hasDeadline then holdTimerOn(source)
      }
      parkUntilSettled()
    } finally finish()

  /** The kind of op that is Node's own, beside the shared layer's (`BackendOp`). */
  final def performOn(performer: shareable.OpPerformer[NodeSource.type] & NodeSource.type)(using Async): Unit =
    performCall(performer)
}

/** A call on a handle that the event loop cannot take back, a file's read or a datagram's send: the handle's work is
  * claimed until it calls back, so that a close waits for it, and so is the op, so that a cancel or a time limit that
  * comes meanwhile is kept for the callback to decide on.
  */
trait HandleCall[H <: BaseHandle] extends CallOp {
  protected def handle: H

  /** A handle is its run's. */
  override protected def admit(owner: shareable.HandleOwner): Unit = owner.check(handle)

  /** Claims the handle's work and the op, and makes the call with `body`; if that throws, nothing was begun. */
  protected final def calling(body: => Unit): Unit = {
    handle.claimWork()
    try {
      claimForCall()
      body
    } catch {
      case failure: Throwable =>
        handle.releaseWork()
        throw failure
    }
  }

  /** The call has called back: releases the handle's work, and gives what the op fails with if the call's outcome is to
    * be dropped, what came for the op meanwhile or its handle's close; null if the outcome stands.
    */
  protected final def returned(): Throwable | Null = {
    handle.releaseWork()
    val came = interruptedBy
    if came != null then came else handle.failureIfClosed
  }
}

/** An op that does its work when its handle is ready for it: the shared layer's (`ReadyWaiting`), which tries it at
  * once and, if the handle was not ready, has it wait as the handle's waiter until the handle reports that it is. What
  * its attempt does is its subclass's, over what Node has handed the handle.
  */
abstract class AbstractReadinessOp[H <: WaitableHandle](protected val handle: H)
    extends shareable.AbstractReadinessOp[H](handle),
      BaseOp,
      shareable.BackendOp.Readiness[NodeSource.type]
