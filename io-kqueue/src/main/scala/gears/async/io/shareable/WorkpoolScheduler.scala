package gears.async.io.shareable

import gears.async.Async
import gears.async.AsyncOperations
import gears.async.AsyncSupport
import gears.async.Cancellable
import gears.async.CompletionGroup
import gears.async.Listener
import gears.async.Scheduler
import gears.async.SuspendSupport
import gears.async.Suspension
import gears.async.cancellationScope
import gears.async.io.Backend
import gears.async.io.Io
import gears.async.io.shareable.TimerHeap
import gears.async.io.shareable.TimerHeap.{TimerOp, TimerSource}
import gears.async.io.shareable.WorkpoolScheduler.DoAfter
import gears.async.native.NativeSuspend

import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import WorkpoolScheduler.{AsyncOp, ReentrantOp, Worker}

abstract class WorkpoolScheduler[Op1 <: AsyncOp & ReentrantOp, B <: Backend { type Op = Op1 }](
    val backend: B,
    workers: Int
) extends Scheduler
    with AsyncSupport
    with AsyncOperations
    with Io[B] {

  override private[async] def resumeAsync[T, R](suspension: Suspension[T, R])(arg: T)(using s: Scheduler): Unit =
    // Not here and now: the caller holds the listener's lock, and may hold a source's, partway through changing it.
    members.get(Thread.currentThread()) match
      case Some(worker) => worker.resumeNext(suspension, arg)
      // A pool that has shut down refuses it: like a timer still pending, that waiter is never resumed.
      case None => nextWorker().submit(() => suspension.resume(arg)): Unit

  /** Schedule a computation with the suspension boundary already created. */
  // override private[async] def scheduleBoundary(body: Label[Unit] ?=> Unit)(using s: Scheduler): Unit =
  //   s.execute(() => boundary(body))

  def makeWorker(id: Int): Worker[Op1, B]

  private val workerTicker = new java.util.concurrent.atomic.AtomicInteger(0)
  private val workerGroup: Array[Worker[Op1, B]] = Array.tabulate(workers)(makeWorker)
  private val members = workerGroup.map(w => w.thread -> w).toMap

  /** The group the program runs under (see `Backend.run`), which a fatal error in any worker cancels: the workers go
    * on, as they must for the program to unwind, but nothing should be built on what a fatal error left behind.
    */
  val rootGroup: CompletionGroup = CompletionGroup()

  private val firstFatal = new java.util.concurrent.atomic.AtomicReference[Throwable | Null](null)

  /** The first fatal error a worker met, or null. Whoever runs the program rethrows it once the program has ended. */
  final def fatalFailure: Throwable | Null = firstFatal.get()

  private def failFatally(failure: Throwable): Unit = {
    firstFatal.compareAndSet(null, failure): Unit
    rootGroup.cancel()
  }

  workerGroup.foreach(_.onFatal = failFatally)
  // Only now: a worker's thread started as the worker was made would run it before its subclass had its fields.
  workerGroup.foreach(_.thread.start())
  private def nextWorker(): Worker[Op1, B] = {
    val index = Math.floorMod(workerTicker.getAndIncrement(), workerGroup.length)
    workerGroup(index)
  }
  protected final def workerOfCurrentThread(): Worker[Op1, B] =
    members.getOrElse(Thread.currentThread(), throw new IllegalStateException("Current thread is not a worker"))

  /** The worker for an op, whose heap holds its deadline, and which fires it. An op that `tryPerform` completes must
    * not be firing when it returns, which holds for the performer's own worker: only its thread fires its timers.
    */
  protected def selectWorker(): Worker[Op1, B]

  type Scheduler = this.type

  /** An op that `tryPerform` completes is complete, whatever its state says: that state is neither read nor waited for.
    * Any other is parked on, and its own outcome is the performance's, not what the wait made of it: a performer
    * cancelled while a worker was already doing the op's work waits for that work and returns its outcome, as the work
    * was done. Its cancel still holds, at its next `await`.
    */
  override def performWithin(op: backend.Op, timeoutMillis: Int)(using Async): Unit = {
    op.performWith {
      val worker = selectWorker()
      op.setTimeout(timeoutMillis, worker)
      try {
        worker.performOp(op)
      } finally {
        op.clearTimeout()
      }
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
    val timerOp = new WorkpoolScheduler.DoAfter(worker, TimerHeap.deadlineAfter(delay.toNanos), body)
    worker.addTimer(timerOp)
    timerOp
  }

}

object WorkpoolScheduler {

  trait AsyncOp extends TimerOp {
    def setTimeout(timeoutMillis: Int, source: TimerSource[?]): Unit

    /** Suspends until the op is settled, and returns or throws its outcome. Only the op ends the wait: cancelled
      * through the group it is linked to, it settles at once as cancelled if no worker has claimed it, and otherwise
      * keeps the performer waiting for the worker that claimed it, which should test for cancellation as soon as
      * possible or resolve with a complete outcome.
      */
    def parkUntilSettled()(using Async): Unit

    def clearTimeout(): Unit
  }

  val SuccessUnit: Try[Unit] = Success(())

  trait ReentrantOp extends Cancellable {

    inline final def performWith(inline fn: Async ?=> Unit)(using Async): Unit =
      requestEntry()
      try
        link()
        try fn
        finally unlink()
      finally requestExit()

    /** Aquires an entry permit, on success then caller may proceed to link. */
    def requestEntry()(using Async): Unit

    /** Potentially resets the entry permit. Caller should unlink first */
    def requestExit()(using Async): Unit
  }

  final class DoAfter(val owner: Worker[?, ?], val deadlineNow: Long, body: Runnable) extends TimerOp {
    @volatile private var cancelled = false
    def timerWakeup(actAfter: Long): Unit = {
      if !cancelled then body.run()
    }
    def cancel(): Unit = {
      cancelled = true
      owner.removeTimer(this): Unit
    }

    /** called under the heap's lock when retrieved. If true then the heap now owns the claim for this timer. */
    def claimAtDeadline(): Boolean = !cancelled
  }

  abstract class Worker[Op1 <: TimerOp, B <: Backend { type Op = Op1 }](val id: Int, val backend: B)
      extends TimerHeap[Op1 | DoAfter] {
    // private[kqueue] val inbox: java.util.concurrent.ArrayBlockingQueue[Runnable] =
    //   new java.util.concurrent.ArrayBlockingQueue[Runnable](1024)
    private val inbox: java.util.concurrent.ConcurrentLinkedQueue[Runnable] =
      new java.util.concurrent.ConcurrentLinkedQueue[Runnable]

    /** Whether the worker has taken `task`, which it refuses once it has left `loop`. */
    def submit(task: Runnable): Boolean = {
      println(
        s"submit called on worker $id on thread ${Thread.currentThread().getName()}"
      )
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

    private class Continuation[T, R](val suspension: Suspension[T, R], val value: T):
      def run(): Unit = {
        val _ = suspension.resume(value)
      }

    /** Worker thread only: continuations to run as soon as the listener resuming them has returned, ahead of the rest
      * of the timers and the inbox. Declared before `thread`, which reads it from the moment it starts.
      */
    private val ready = new java.util.ArrayDeque[Continuation[?, ?]]

    private[WorkpoolScheduler] def resumeNext[T, R](suspension: Suspension[T, R], value: T): Unit =
      println(
        s"resumeNext called on worker $id on thread ${Thread.currentThread().getName()}"
      )
      ready.addLast(Continuation(suspension, value))

    private[WorkpoolScheduler] val thread = {
      val t = new Thread(() => loop())
      t.setName(s"WorkpoolSchedulerWorker-$id")
      t.setDaemon(true)
      t
    }

    /** Does the core work of performing an operation, when it returns the operation is guaranteed to have been
      * processed.
      */
    def performOp(op: Op1)(using Async): Unit

    def isEmpty(): Boolean = inbox.isEmpty() && ready.isEmpty()

    /** A worker outlives what it runs, as event loops do: it can't be replaced, and its timers and inbox would be lost
      * with it. What a task throws, of any kind, is reported to the thread's uncaught exception handler, as it would be
      * had it ended the thread, so that a program can install its own; by default that prints it. A fatal error also
      * cancels the program (see `rootGroup`).
      */
    private inline def guarded(inline body: Unit): Unit =
      try body
      catch
        case failure: Throwable =>
          reportFailure(failure)
          if !NonFatal(failure) then onFatal(failure)

    /** Set by the scheduler before the worker's thread starts: what a fatal error does beyond being reported. */
    private[WorkpoolScheduler] var onFatal: Throwable => Unit = _ => ()

    private def reportFailure(failure: Throwable): Unit = {
      val current = Thread.currentThread()
      // A handler that throws must not take the worker with it either.
      try current.getUncaughtExceptionHandler().uncaughtException(current, failure)
      catch case _: Throwable => failure.printStackTrace()
    }

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
      close()
    }
  }
}
