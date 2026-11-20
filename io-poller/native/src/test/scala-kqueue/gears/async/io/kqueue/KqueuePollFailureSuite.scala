package gears.async.io
package kqueue

import gears.async.io.eventpolling.EventPollingScheduler
import gears.async.io.eventpolling.FailingPolls
import gears.async.io.eventpolling.PollFailureContract
import gears.async.io.shareable.HandleOwner

/** `PollFailureContract` over kqueue: both of a worker's polls fail, the look before a wait and the wait. */
class KqueuePollFailureSuite extends PollFailureContract {

  private final class FailingPool(workers: Int) extends KqueueScheduler(workers), FailingPolls {
    override def makeWorker(id: Int, owner: HandleOwner): KqueueWorker = new KqueueWorker(id, owner) {
      override protected def pollKqueue(timeout: Long): Int =
        if failing.get() then failPoll() else super.pollKqueue(timeout)

      override protected def pollBriefly(): Int =
        if failing.get() then failPoll() else super.pollBriefly()
    }
  }

  protected def failingPool(workers: Int): EventPollingScheduler & FailingPolls = new FailingPool(workers)
}
