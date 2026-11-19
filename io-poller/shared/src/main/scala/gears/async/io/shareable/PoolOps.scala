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

/** A worker of a pool backend: it performs its backend's ops, each as its kind is performed (`OpPerformer`). */
abstract class PoolOpWorker[S <: PoolSource](id: Int, owner: HandleOwner) extends Worker(id, owner), OpPerformer[S] {
  self: S =>
  type Op <: BackendOp[S]

  override final def performOp(op: Op)(using Async): Unit = op.performOn(this)
}
