package gears.async.io.nio

import gears.async.Async
import gears.async.Cancellable
import gears.async.io.Backend
import gears.async.io.shareable.HandleOwner
import gears.async.io.shareable.Starvation
import gears.async.io.shareable.PoolOp
import gears.async.io.shareable.PoolOpWorker
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

  /** A future is a virtual thread of its own. A pool that has shut down refuses it as the native one does: where the
    * pool carries the threads, the JDK is told so its own way (`RejectedExecutionException`), which is not a caller's
    * to see.
    */
  override def execute(body: Runnable): Unit =
    try startThread(body)
    catch
      case refused: java.util.concurrent.RejectedExecutionException =>
        throw new IllegalStateException(refused.getMessage, refused)

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

  /** Whether the futures run on threads of their own, not the workers': then an op's work that blocks is done by the
    * future itself, and a timer already due is a yield.
    */
  protected def futuresOnOwnThreads: Boolean = false

  class NioWorker(id: Int, owner: HandleOwner) extends PoolOpWorker[NioPoller](id, owner), NioPoller {
    type Op = NioOp

    // On a thread of its own a timer already due gives the other threads their turn, and is done.
    override protected[io] def performTimed(timed: PoolOp.Timed[NioPoller])(using Async): Unit = timed match
      case timer: NioMakeTimer if futuresOnOwnThreads && timer.isDue => Thread.`yield`()
      case _                                                         => super.performTimed(timed)

    protected[io] def performBlocking(work: PoolOp.OffThread[NioPoller])(using Async): Unit =
      if futuresOnOwnThreads then work.performOnCaller(this) else work.performOffThread(this)
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

  override protected def futuresOnOwnThreads: Boolean = true

  /** The futures are not the workers' tasks: one that never waits keeps nothing of a worker's. */
  override protected def budgetSpent(worker: PoolWorker): Boolean = false
}

/** The pool of a program whose virtual threads the workers carry, in place of the JDK's scheduler: a future runs on a
  * worker's thread, as on Scala Native, and one woken by a worker goes on there, with no other thread to hand it to. An
  * op that waits goes to the worker carrying the future performing it. It needs what `VirtualCarriers` does of the JDK.
  *
  * A future is not tied to its worker: one that blocks on a monitor, or is woken by a thread of the JDK's, may go on
  * elsewhere, between any two steps of an op. So nothing here relies on the caller being on a worker's own thread, any
  * more than the pool on the JDK's threads does; the worker is where the op's wait is cheapest, not where it must be.
  */
final class NioPoolScheduler(workers: Int, onStarvation: Starvation.Handler = Starvation.printWarning)
    extends NioScheduler(NioPoolBackend, workers, onStarvation) {

  /** A thread's continuation is a task of the worker carrying whoever made it ready to run, or of the workers in turn.
    */
  protected val threads: ThreadFactory = VirtualCarriers.factory { continuation =>
    try runOnWorker(continuation)
    catch
      case shutDown: IllegalStateException =>
        throw new java.util.concurrent.RejectedExecutionException(shutDown.getMessage)
  }

  override protected def callerThread(): Thread = VirtualCarriers.currentCarrier()

  override protected def selectWorker(): PoolWorker = {
    val carrying = workerOf(callerThread())
    if carrying != null then carrying else workerFor(Thread.currentThread().threadId())
  }
}
