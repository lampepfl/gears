package gears.async.io
package epoll

import gears.async.io.eventpolling.EventPollingScheduler
import gears.async.io.eventpolling.FailingPolls
import gears.async.io.eventpolling.PollFailureContract
import gears.async.io.shareable.HandleOwner

/** `PollFailureContract` over epoll. */
class EpollPollFailureSuite extends PollFailureContract {

  private final class FailingPool(workers: Int) extends EpollScheduler(workers), FailingPolls {
    override def makeWorker(id: Int, owner: HandleOwner): EpollWorker = new EpollWorker(id, owner) {
      override protected def pollEpoll(timeout: Long): Int =
        if failing.get() then failPoll() else super.pollEpoll(timeout)
    }
  }

  protected def failingPool(workers: Int): EventPollingScheduler & FailingPolls = new FailingPool(workers)
}
