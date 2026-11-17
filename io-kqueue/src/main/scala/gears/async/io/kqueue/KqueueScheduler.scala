package gears.async.io.kqueue

import gears.async.Async
import gears.async.io.shareable.Starvation
import gears.async.io.shareable.Worker
import gears.async.io.shareable.WorkpoolScheduler
import gears.async.native.NativeSuspend

/** A pool of `workers` kqueue workers. A worker kept from its tasks is reported to `onStarvation` (see `Starvation`).
  */
class KqueueScheduler(workers: Int, onStarvation: Starvation.Handler = Starvation.printWarning)
    extends WorkpoolScheduler[KqueueOps.type](KqueueBackend, KqueueOps, workers, onStarvation),
      NativeSuspend {
  override protected def selectWorker() = workerOfCurrentThread()

  override def makeWorker(id: Int): KqueueWorker = new KqueueWorker(id)

  class KqueueWorker(id: Int) extends Worker(id), KqueuePoller(255) {
    type Op = KqueueOp

    /** either schedules a waiter (false), or completes immediately (true) */
    override def performOp(op: KqueueOp)(using Async): Unit = {
      op match
        case timer: KqueueMakeTimer =>
          timer.holdTimerOn(this)
          timer.parkUntilSettled()
        case deadline: KqueueMakeDeadline =>
          deadline.holdTimerOn(this)
          deadline.parkUntilSettled()
        case close: KqueueCloseOp         => close.performClose(this)
        case ready: KqueueReadinessOp     => ready.performWhenReady(this)
        case immediate: KqueueImmediateOp => immediate.perform()
    }
  }
}
