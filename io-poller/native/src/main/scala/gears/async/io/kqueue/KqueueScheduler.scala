package gears.async.io.kqueue

import gears.async.io.eventpolling.EventPollingScheduler
import gears.async.io.shareable.HandleOwner
import gears.async.io.shareable.Starvation

/** The pool whose workers each wait on a kqueue: macOS and FreeBSD. */
class KqueueScheduler(workers: Int, onStarvation: Starvation.Handler = Starvation.printWarning)
    extends EventPollingScheduler(workers, onStarvation) {

  override def makeWorker(id: Int, owner: HandleOwner): KqueueWorker = new KqueueWorker(id, owner)

  class KqueueWorker(id: Int, owner: HandleOwner) extends EventPollingWorker(id, owner), KqueuePoller(255)
}
