package gears.async.io.kqueue

import gears.async.Async
import gears.async.io.shareable.MonitorPoller
import gears.async.io.shareable.WorkpoolScheduler
import gears.async.native.NativeSuspend

import java.io.IOException

class KqueueScheduler(workers: Int) extends WorkpoolScheduler[KqueueOp, KqueueOps.type](KqueueBackend, KqueueOps, workers),
      NativeSuspend {
  override protected def selectWorker() = workerOfCurrentThread()

  override def makeWorker(id: Int): KqueueWorker = new KqueueWorker(id)

  class KqueueWorker(id: Int) extends WorkpoolScheduler.Worker[KqueueOp](id), KqueuePoller(255) {

    /** either schedules a waiter (false), or completes immediately (true) */
    override def performOp(op: KqueueOp)(using Async): Unit = {
      op match
        case timer: KqueueMakeTimer =>
          addTimer(timer)
          timer.parkUntilSettled()
        case deadline: KqueueMakeDeadline =>
          addTimer(deadline)
          deadline.parkUntilSettled()
        case pipe: KqueueMakePipe =>
          pipe.perform()
        case close: KqueueCloseHandle =>
          performClose(close)
        case read: KqueueRead =>
          performRead(read)
        case write: KqueueWrite =>
          performWrite(write)
    }
  }
}
