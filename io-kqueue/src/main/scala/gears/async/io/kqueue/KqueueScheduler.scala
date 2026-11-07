package gears.async.io.kqueue

import gears.async.native.NativeSuspend
import gears.async.io.shareable.WorkpoolScheduler

class KqueueScheduler(workers: Int) extends WorkpoolScheduler(KqueueBackend, workers) with NativeSuspend {
  override protected def selectWorker() = workerOfCurrentThread()

  override def makeWorker(id: Int): KqueueWorker = new KqueueWorker(id)

  class KqueueWorker(id: Int) extends WorkpoolScheduler.Worker(id, KqueueBackend) {

    private inline val Done = true
    private inline val Scheduled = false

    /** either schedules a waiter (false), or completes immediately (true) */
    override def tryPerform(op: KqueueBackend.Op): Boolean = {
      op match
        case timer: KqueueMakeTimer =>
          addTimer(timer)
          Scheduled
        case deadline: KqueueMakeDeadline =>
          addTimer(deadline)
          Scheduled
    }
  }
}
