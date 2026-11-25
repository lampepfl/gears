package gears.async.io.epoll

import gears.async.io.eventpolling.EventPollingScheduler
import gears.async.io.shareable.HandleOwner
import gears.async.io.shareable.Starvation

/** The pool whose workers each wait on an epoll instance: Linux. */
class EpollScheduler(workers: Int, onStarvation: Starvation.Handler = Starvation.printWarning)
    extends EventPollingScheduler(workers, onStarvation) {

  override def makeWorker(id: Int, owner: HandleOwner): EpollWorker = new EpollWorker(id, owner)

  class EpollWorker(id: Int, owner: HandleOwner) extends EventPollingWorker(id, owner), EpollPoller(255)
}
