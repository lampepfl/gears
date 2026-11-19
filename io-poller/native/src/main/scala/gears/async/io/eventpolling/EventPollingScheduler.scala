package gears.async.io.eventpolling

import gears.async.Async
import gears.async.io.shareable.HandleOwner
import gears.async.io.shareable.Starvation
import gears.async.io.shareable.BackendOp
import gears.async.io.shareable.PoolOpWorker
import gears.async.io.shareable.WorkpoolScheduler
import gears.async.native.NativeSuspend

/** A pool of `workers` workers, each with an event poller of the system's. A worker kept from its tasks is reported to
  * `onStarvation` (see `Starvation`). Which poller is its subclass's: `KqueueScheduler` or `EpollScheduler`.
  */
abstract class EventPollingScheduler(workers: Int, onStarvation: Starvation.Handler)
    extends WorkpoolScheduler[EventPollingOps.type](EventPollingBackend, EventPollingOps, workers, onStarvation),
      NativeSuspend {
  override protected def selectWorker() = workerOfCurrentThread()

  override def makeWorker(id: Int, owner: HandleOwner): EventPollingWorker

  /** A worker of this backend's, whatever its poller is. */
  abstract class EventPollingWorker(id: Int, owner: HandleOwner)
      extends PoolOpWorker[EventPoller](id, owner),
        EventPoller {
    type Op = EventPollingOp

    /** On the worker's own thread, with the worker handed over, if it has nothing else to run (see `performInPlace`). */
    protected[io] def performBlocking(work: BackendOp.OffThread[EventPoller])(using Async): Unit = work.performInPlace(this)
  }
}

object EventPollingScheduler {

  /** The pool for the system the program is linked for (see `EventPollingBackend.scheduler`). */
  def apply(workers: Int, onStarvation: Starvation.Handler = Starvation.printWarning): EventPollingScheduler =
    EventPollingBackend.scheduler(workers, onStarvation)
}
