package gears.async.io.kqueue

import gears.async.Async
import gears.async.io.shareable.MonitorPoller
import gears.async.io.shareable.WorkpoolScheduler
import gears.async.native.NativeSuspend

class KqueueScheduler(workers: Int) extends WorkpoolScheduler(KqueueBackend, workers), NativeSuspend {
  override protected def selectWorker() = workerOfCurrentThread()

  override def makeWorker(id: Int): KqueueWorker = new KqueueWorker(id)

  class KqueueWorker(id: Int) extends WorkpoolScheduler.Worker(id, KqueueBackend), KqueuePoller {

    /** either schedules a waiter (false), or completes immediately (true) */
    override def performOp(op: KqueueBackend.Op)(using Async): Unit = {
      op match
        case timer: KqueueMakeTimer =>
          addTimer(timer)
          timer.parkUntilSettled()
        case deadline: KqueueMakeDeadline =>
          addTimer(deadline)
          deadline.parkUntilSettled()
    }
  }
}
