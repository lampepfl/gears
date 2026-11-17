package gears.async.io.nio

import gears.async.Async
import gears.async.Cancellable
import gears.async.io.Backend
import gears.async.io.shareable.HandleOwner
import gears.async.io.shareable.Starvation
import gears.async.io.shareable.Worker
import gears.async.io.shareable.WorkpoolScheduler

import java.util.concurrent.ThreadFactory
import scala.concurrent.duration.FiniteDuration

/** A pool of `workers` workers, each with a selector, a heap of timers and an inbox, as the kqueue backend's are, for a
  * program whose futures are virtual threads. A future's op is tried on its own thread, and one that must wait is given
  * to a worker: its time limit to the worker's heap, its handle to the worker's selector, which does the op's work once
  * the handle is ready and wakes the future.
  *
  * What carries the virtual threads is the subclass's: `threads` makes them.
  */
abstract class NioScheduler(
    backend: Backend { type Interface = NioOps.type },
    workers: Int,
    onStarvation: Starvation.Handler
) extends WorkpoolScheduler[NioOps.type](backend, NioOps, workers, onStarvation),
      VirtualThreadSuspend {

  /** Makes the virtual threads the program's futures are. */
  protected def threads: ThreadFactory

  protected final def startThread(body: Runnable): Unit = threads.newThread(body).start()

  /** A future is a virtual thread of its own. */
  override def execute(body: Runnable): Unit = startThread(body)

  override private[async] def scheduleBoundary(body: Label[Unit] ?=> Unit)(using s: Scheduler): Unit =
    startBoundary(body)

  /** The suspended thread is woken where it is: nothing of it runs here. */
  override private[async] def resumeAsync[T, R](suspension: Suspension[T, R])(arg: T)(using s: Scheduler): Unit =
    wake(suspension, arg)

  /** A worker fires the timer, and the body runs on a thread of its own, not the worker's. */
  override def schedule(delay: FiniteDuration, body: Runnable): Cancellable =
    super.schedule(delay, () => execute(body))

  override def `yield`()(using Async): Unit = Thread.`yield`()

  override def makeWorker(id: Int, owner: HandleOwner): NioWorker = new NioWorker(id, owner)

  class NioWorker(id: Int, owner: HandleOwner) extends Worker(id, owner), NioPoller {
    type Op = NioOp

    override def performOp(op: NioOp)(using Async): Unit =
      op match
        case timer: NioMakeTimer =>
          timer.holdTimerOn(this)
          timer.parkUntilSettled()
        case deadline: NioMakeDeadline =>
          deadline.holdTimerOn(this)
          deadline.parkUntilSettled()
        case close: NioCloseOp         => close.performClose(this)
        case ready: NioReadinessOp     => ready.performWhenReady(this)
        case immediate: NioImmediateOp => immediate.perform(handleOwner)
  }
}

/** The pool of a program on the JDK's own virtual threads: its futures run on the JDK's scheduler, and the workers do
  * nothing but wait on their selectors and timers for them. An op that waits goes to a worker chosen by the thread
  * performing it, so that one future's ops keep to one worker.
  */
final class NioThreadsScheduler(workers: Int, onStarvation: Starvation.Handler = Starvation.printWarning)
    extends NioScheduler(NioBackend, workers, onStarvation) {

  protected val threads: ThreadFactory = Thread.ofVirtual().name("gears-io-", 0L).factory()

  override protected def selectWorker(): PoolWorker = workerFor(Thread.currentThread().threadId())

  /** The futures are not the workers' tasks: one that never waits keeps nothing of a worker's. */
  override protected def budgetSpent(worker: PoolWorker): Boolean = false
}
