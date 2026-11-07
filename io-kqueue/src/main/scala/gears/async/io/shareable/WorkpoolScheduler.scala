package gears.async.io.shareable

import gears.async.Async
import gears.async.AsyncOperations
import gears.async.AsyncSupport
import gears.async.Cancellable
import gears.async.CompletionGroup
import gears.async.Listener
import gears.async.Scheduler
import gears.async.io.Backend
import gears.async.io.Io
import gears.async.native.NativeSuspend

import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import WorkpoolScheduler.{ReentrantOp, AsyncOp, Worker}
import gears.async.io.shareable.TimerHeap.{TimerOp, TimerSource}
import gears.async.io.shareable.WorkpoolScheduler.DoAfter
import gears.async.SuspendSupport
import gears.async.io.shareable.TimerHeap

abstract class WorkpoolScheduler[Op1 <: AsyncOp & ReentrantOp, B <: Backend { type Op = Op1 }](val backend: B, workers: Int)
    extends Scheduler
    with AsyncSupport
    with AsyncOperations
    with Io[B] {

  override private[async] def resumeAsync[T, R](suspension: Suspension[T, R])(arg: T)(using s: Scheduler): Unit =
    // Not here and now: the caller holds the listener's lock, and may hold a source's, partway through changing it.
    members.get(Thread.currentThread()) match
      case Some(worker) => worker.resumeNext(() => suspension.resume(arg))
      // A pool that has shut down refuses it: like a timer still pending, that waiter is never resumed.
      case None         => nextWorker().submit(() => suspension.resume(arg)): Unit

  /** Schedule a computation with the suspension boundary already created. */
  // override private[async] def scheduleBoundary(body: Label[Unit] ?=> Unit)(using s: Scheduler): Unit =
  //   s.execute(() => boundary(body))


  def makeWorker(id: Int): Worker[Op1, B]

  private val workerTicker = new java.util.concurrent.atomic.AtomicInteger(0)
  private val workerGroup: Array[Worker[Op1, B]] = Array.tabulate(workers)(makeWorker)
  private val members = workerGroup.map(w => w.thread -> w).toMap
  private def nextWorker(): Worker[Op1, B] = {
    val index = Math.floorMod(workerTicker.getAndIncrement(), workerGroup.length)
    workerGroup(index)
  }
  protected final def workerOfCurrentThread(): Worker[Op1, B] =
    members.getOrElse(Thread.currentThread(), throw new IllegalStateException("Current thread is not a worker"))

  /** The worker for an op. It must be the performer's own, or otherwise unable to fire the op before `await` has
    * registered its listener, as firing takes no listener to mean that the performer was cancelled.
    */
  protected def selectWorker(): Worker[Op1, B]

  type Scheduler = this.type

  override def performWithin(op: backend.Op, timeoutMillis: Int)(using Async): Unit = {
    op.requestEntry()
    try {
      val worker = selectWorker()
      try {
        op.setTimeout(timeoutMillis, worker)
        if !worker.tryPerform(op) then
          try {
            op.await
          } finally {
            // Cancelled asynchronously: a firing still under way must finish before the op can be performed again.
            if !op.settled && !worker.withdraw(op) then
              summon[Async].withGroup(CompletionGroup.Unlinked).await(op.settling)
          }
      } finally {
        op.clearTimeout()
      }
    } finally {
      op.requestExit()
    }
  }

  /** Guards `execute` and `schedule`: work given to a pool that has shut down would never run. */
  @volatile private var isShutDown = false

  private def checkRunning(): Unit =
    if isShutDown then throw new IllegalStateException("Scheduler has shut down")

  /** Stops the workers and waits for them to finish what they are running, which may be the end of the program, as a
    * worker resumes its waiters itself. Timers still pending never fire. Call once the program has finished, from
    * outside the pool: a worker can't wait for itself.
    */
  final def shutdown(): Unit = {
    if members.contains(Thread.currentThread()) then
      throw new IllegalStateException("Can't shut down a scheduler from one of its workers")
    isShutDown = true
    workerGroup.foreach(_.stop())
    workerGroup.foreach(_.thread.join())
  }

  override def execute(body: Runnable): Unit =
    checkRunning()
    // The check alone can pass just before the workers leave.
    if !nextWorker().submit(body) then throw new IllegalStateException("Scheduler has shut down")

  override def schedule(delay: FiniteDuration, body: Runnable): Cancellable = {
    checkRunning()
    val worker = nextWorker()
    val timerOp = new WorkpoolScheduler.DoAfter(TimerHeap.deadlineAfter(delay.toNanos), body)
    worker.addTimer(timerOp)
    new Cancellable {
      override def cancel(): Unit = {
        timerOp.timerCancelled()
        worker.removeTimer(timerOp): Unit
      }
    }
  }

}

object WorkpoolScheduler {

  trait AsyncOp extends TimerOp, Async.Source[Try[Unit]] {
    def setTimeout(timeoutMillis: Int, source: TimerSource[?]): Unit
    def clearTimeout(): Unit
    def settled: Boolean
    def settling: Async.Source[Unit]
  }

  val SuccessUnit: Try[Unit] = Success(())

  trait ReentrantOp {
    def requestEntry(): Unit
    def requestExit(): Unit
  }

  class DoAfter(val deadlineNow: Long, body: Runnable) extends TimerOp {
    @volatile private var cancelled = false
    def timerWakeup(actAfter: Long): Unit = {
      if !cancelled then body.run()
    }
    def timerCancelled(): Unit = {
      cancelled = true
    }
  }

  abstract class Worker[Op1 <: TimerOp & ReentrantOp, B <: Backend { type Op = Op1 }](val id: Int, val backend: B) extends TimerHeap[Op1 | DoAfter] {
    // private[kqueue] val inbox: java.util.concurrent.ArrayBlockingQueue[Runnable] =
    //   new java.util.concurrent.ArrayBlockingQueue[Runnable](1024)
    private val inbox: java.util.concurrent.ConcurrentLinkedQueue[Runnable] =
      new java.util.concurrent.ConcurrentLinkedQueue[Runnable]

    /** Whether the worker has taken `task`, which it refuses once it has left `loop`. */
    def submit(task: Runnable): Boolean = {
      inbox.add(task)
      wake()
      !exited || !inbox.remove(task)
    }

    /** Once set, the worker finishes its inbox and leaves `loop`. */
    @volatile private var stopped = false

    /** Set as the worker leaves `loop`, for `submit` to take back a task that came too late for it. */
    @volatile private var exited = false

    private[WorkpoolScheduler] def stop(): Unit = {
      stopped = true
      wake()
    }

    /** Worker thread only: continuations to run as soon as the listener resuming them has returned, ahead of the rest
      * of the timers and the inbox. Declared before `thread`, which reads it from the moment it starts.
      */
    private val ready = new java.util.ArrayDeque[Runnable]

    private[WorkpoolScheduler] def resumeNext(continuation: Runnable): Unit = ready.addLast(continuation)

    private[WorkpoolScheduler] val thread = {
      val t = new Thread(() => loop())
      t.setDaemon(true)
      t.start()
      t
    }

    /**
     * Attempt to perform the given operation, putting it on the heap only if it `hasDeadline`.
     * @return true if the operation is already completed, otherwise false which requires a subsequent await
     */
    def tryPerform(op: Op1): Boolean

    /** Takes back an op whose performer was cancelled, from any thread, as that performer can resume on another worker.
      *
      * @return
      *   true if nothing will fire the op now, false if it has left the heap to fire, which will settle it.
      */
    def withdraw(op: Op1): Boolean = !op.hasDeadline || removeTimer(op)

    def isEmpty(): Boolean = inbox.isEmpty() && ready.isEmpty()

    /** A worker outlives what it runs: its timers and inbox would otherwise be lost, with nothing to say so. */
    private inline def guarded(inline body: Unit): Unit =
      try body
      catch case ex: Throwable => ex.printStackTrace()

    /** Has a budget so that tasks resuming each other can't keep the worker from its timers and inbox. */
    private def runReady(): Unit = {
      var readyBudget = 32
      while readyBudget > 0 do
        val continuation = ready.pollFirst()
        if continuation == null then readyBudget = 0
        else
          guarded(continuation.run())
          readyBudget -= 1
    }

    /** Each timer's waiter runs before the next timer leaves the heap, so that it can still move or cancel that one. */
    private def fireTimers(now: Long): Unit = {
      var timerBudget = 32
      while timerBudget > 0 do
        val timer = nextReadyTimer(now)
        if timer == null then timerBudget = 0
        else
          guarded(timer.timerWakeup(now))
          runReady()
          timerBudget -= 1
    }

    def drainInbox(): Unit = {
      var inboxBudget = 32
      while inboxBudget > 0 do
        val task = inbox.poll()
        if task == null then inboxBudget = 0
        else
          guarded(task.run())
          runReady()
          inboxBudget -= 1
    }

    def loop(): Unit = {
      while !stopped || !isEmpty() do
        val now = System.nanoTime()
        fireTimers(now)
        runReady()
        drainInbox()
        // `stop`'s wake-up may have been spent while the inbox was still draining.
        if !stopped then waitForDeadline(peekNextDeadline())
      exited = true
      // A task added before `exited` showed is either run here or taken back by `submit`, never lost.
      while !isEmpty() do
        drainInbox()
        runReady()
    }
  }
}
