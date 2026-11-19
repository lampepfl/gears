package gears.async.io.shareable

import gears.async.Async

import java.io.IOException

/** The event source of a worker of a pool backend, whichever the backend: a selector on the JVM, the system's event
  * queue on Scala Native. A backend's own source type is what its handles are listened for by and its ops wait on.
  *
  * It has what such a source does once its queue has failed for good
  * (`giveUp`): it waits without it, parked until its deadline or a wake-up, so that the worker still fires its timers
  * and runs what it is given, and the program can unwind. No handle is reported ready from then on.
  */
trait PoolSource extends Poller, EventSource {

  @volatile private var gaveUp = false

  /** Whether the queue has been given up. */
  protected final def hasGivenUp: Boolean = gaveUp

  /** The worker's thread, once it drives this: what a wake-up unparks when there is no queue to wake. */
  @volatile private var waitingThread: Thread | Null = null

  override protected def drivenBy(thread: Thread): Unit = waitingThread = thread

  /** Gives the queue up, and tells the source's owner why (`pollerFailed`). Once. */
  protected final def giveUp(failure: IOException): Unit = {
    gaveUp = true
    pollerFailed(failure)
  }

  /** Wakes the worker where it waits without its queue. */
  protected final def wakeWaiting(): Unit = {
    val thread = waitingThread
    if thread != null then java.util.concurrent.locks.LockSupport.unpark(thread)
  }

  /** Waits without the queue: at most `timeout` nanoseconds, not at all if that is 0, and until woken if it is
    * negative.
    */
  protected final def waitWithoutQueue(timeout: Long): Unit =
    if timeout > 0 then java.util.concurrent.locks.LockSupport.parkNanos(this, timeout)
    else if timeout < 0 then java.util.concurrent.locks.LockSupport.park(this)
}

/** An op of a pool backend whose event sources are `S`s. It is of one of the kinds below, which is what says how a
  * worker performs it (`performOn`): so an op of no kind cannot be made, and a worker need not ask an op what it is.
  */
trait PoolOp[S <: PoolSource] extends TimerHeapOp {
  type Source = S

  /** Performs the op on `worker`, a worker of its pool's, as its kind is performed. */
  def performOn(worker: PoolOpWorker[S] & S)(using Async): Unit
}

/** The kinds of op a pool's worker performs. A backend's op is one of them, with the shared class that does its work.
  */
object PoolOp {

  /** An op that waits for its handle to be ready, on the worker that listens for it (see `ReadyWaiting`). */
  trait Readiness[S <: PoolSource] extends PoolOp[S] {
    def performWhenReady(self: S)(using Async): Unit
    final def performOn(worker: PoolOpWorker[S] & S)(using Async): Unit = performWhenReady(worker)
  }

  /** An op done as it is performed, with nothing to wait for (see `ImmediateOp`). */
  trait Immediate[S <: PoolSource] extends PoolOp[S] {
    def perform(owner: HandleOwner): Unit
    final def performOn(worker: PoolOpWorker[S] & S)(using Async): Unit = perform(worker.handleOwner)
  }

  /** An op whose work blocks (see `WorkOffThread`): done on the blocking pool's thread, on the performer's own, or on
    * the worker's with the worker handed over, as its backend's workers choose (`PoolOpWorker.performBlocking`).
    */
  trait OffThread[S <: PoolSource] extends PoolOp[S] {
    def performOffThread(worker: Worker & S)(using Async): Unit
    def performOnCaller(worker: Worker & S)(using Async): Unit
    def performInPlace(worker: Worker & S)(using Async): Unit
    final def performOn(worker: PoolOpWorker[S] & S)(using Async): Unit = worker.performBlocking(this)
  }

  /** A connect, which makes the handle it then waits on (see `AbstractConnectOp`). */
  trait Connect[S <: PoolSource] extends PoolOp[S] {
    def performConnect(self: S)(using Async): Unit
    final def performOn(worker: PoolOpWorker[S] & S)(using Async): Unit = performConnect(worker)
  }

  /** A timer or a deadline: an op that is nothing but its time on a worker's heap (`PoolOpWorker.performTimed`). */
  trait Timed[S <: PoolSource] extends PoolOp[S] {
    final def performOn(worker: PoolOpWorker[S] & S)(using Async): Unit = worker.performTimed(this)
  }

  /** A close, which waits for the handle's work and its listeners to let go (see `AbstractCloseOp`). */
  trait Close[S <: PoolSource] extends PoolOp[S] {
    def performClose(self: S)(using Async): Unit
    final def performOn(worker: PoolOpWorker[S] & S)(using Async): Unit = performClose(worker)
  }
}

/** A worker of a pool backend: it performs its backend's ops, each as its kind is performed. What it does with one
  * that blocks is its backend's to say (`performBlocking`), as is anything else it does with a timer than wait for it.
  */
abstract class PoolOpWorker[S <: PoolSource](id: Int, owner: HandleOwner) extends Worker(id, owner) { self: S =>
  type Op <: PoolOp[S]

  /** Does the work of an op that blocks, where this backend does it. */
  protected[io] def performBlocking(work: PoolOp.OffThread[S])(using Async): Unit

  /** Waits for a timer or a deadline on this worker's heap. */
  protected[io] def performTimed(timed: PoolOp.Timed[S])(using Async): Unit = {
    timed.holdTimerOn(this)
    timed.parkUntilSettled()
  }

  override final def performOp(op: Op)(using Async): Unit = op.performOn(this)
}
