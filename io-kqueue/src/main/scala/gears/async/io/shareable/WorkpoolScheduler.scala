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
import gears.async.io.Interface
import gears.async.io.Io
import gears.async.io.shareable.TimerHeap
import gears.async.io.shareable.TimerHeap.{TimerOp, TimerSource}
import gears.async.io.shareable.WorkpoolScheduler.DoAfter
import gears.async.native.NativeSuspend

import java.io.IOException
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import WorkpoolScheduler.{AsyncOp, ReentrantOp, Worker}

abstract class WorkpoolScheduler[Op1 <: AsyncOp & ReentrantOp, I <: Interface { type Op = Op1 }](
    val backend: Backend { type Interface <: I },
    val ops: I,
    workers: Int
) extends Scheduler
    with AsyncSupport
    with AsyncOperations
    with Io[I] {

  override private[async] def resumeAsync[T, R](suspension: Suspension[T, R])(arg: T)(using s: Scheduler): Unit =
    // Not here and now: the caller holds the listener's lock, and may hold a source's, partway through changing it.
    val thread = Thread.currentThread()
    val worker = workerOf(thread)
    if worker != null then
      // Where whoever is resuming it says a pinned future belongs (`resumingAt`). Any other future is resumed here,
      // and may be given to a worker with nothing to do before it is (`Worker.shareUnpinned`).
      val home = thread.asInstanceOf[WorkpoolScheduler.WorkerThread].resumeHome
      if home == null then worker.resumeNext(suspension, arg, pinned = false)
      else if home eq worker then worker.resumeNext(suspension, arg, pinned = true)
      else home.asInstanceOf[Worker[Op1]].submit(() => suspension.resume(arg)): Unit
    // A pool that has shut down refuses it: like a timer still pending, that waiter is never resumed.
    else nextWorker().submit(() => suspension.resume(arg)): Unit

  /** Schedule a computation with the suspension boundary already created. */
  // override private[async] def scheduleBoundary(body: Label[Unit] ?=> Unit)(using s: Scheduler): Unit =
  //   s.execute(() => boundary(body))

  def makeWorker(id: Int): Worker[Op1]

  private val workerTicker = new java.util.concurrent.atomic.AtomicInteger(0)
  private val workerGroup: Array[Worker[Op1]] = Array.tabulate(workers)(makeWorker)

  /** The worker of this pool that `thread` runs, or null: a worker's thread carries its worker, so this is no lookup
    * by thread.
    */
  private def workerOf(thread: Thread): Worker[Op1] | Null = thread match
    case thread: WorkpoolScheduler.WorkerThread =>
      val worker = thread.worker
      if worker.id < workerGroup.length && (workerGroup(worker.id) eq worker) then worker.asInstanceOf[Worker[Op1]]
      else null // another pool's
    case _ => null

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

  workerGroup.foreach { worker =>
    worker.onFatal = failFatally
    worker.steal = () => stealFor(worker)
    worker.callThief = () => wakeThief(worker)
    worker.idleExists = () => anyParked()
  }
  // Only now: a worker's thread started as the worker was made would run it before its subclass had its fields.
  workerGroup.foreach(_.thread.start())
  private def nextWorker(): Worker[Op1] = {
    val index = Math.floorMod(workerTicker.getAndIncrement(), workerGroup.length)
    workerGroup(index)
  }
  protected final def workerOfCurrentThread(): Worker[Op1] =
    val worker = workerOf(Thread.currentThread())
    if worker == null then throw new IllegalStateException("Current thread is not a worker")
    worker

  /** The worker for an op, whose heap holds its deadline, and which fires it. An op that `tryPerform` completes must
    * not be firing when it returns, which holds for the performer's own worker: only its thread fires its timers.
    */
  protected def selectWorker(): Worker[Op1]

  type Scheduler = this.type

  /** An op that `tryPerform` completes is complete, whatever its state says: that state is neither read nor waited for.
    * Any other is parked on, and its own outcome is the performance's, not what the wait made of it: a performer
    * cancelled while a worker was already doing the op's work waits for that work and returns its outcome, as the work
    * was done. Its cancel still holds, at its next `await`.
    */
  override def performWithin(op: Op, timeoutMillis: Long)(using Async): Unit = {
    op.performWith {
      var worker = selectWorker()
      // An op that is done as it is tried never gives its worker up: a future whose ops are always ready, a read at
      // the end of a stream for one, would keep it from every other task, its timers and its handles' events.
      if worker.opBudgetSpent() then
        `yield`()
        worker = selectWorker() // the future may go on elsewhere
      op.setTimeout(timeoutMillis, worker)
      op.pinnedTo(summon[Async] match
        case pinned: PinnedAsync => pinned.home
        case _                   => null)
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
    if workerOf(Thread.currentThread()) != null then
      throw new IllegalStateException("Can't shut down a scheduler from one of its workers")
    isShutDown = true
    workerGroup.foreach(_.stop())
    workerGroup.foreach(_.thread.join())
  }

  /** A task from one of this pool's workers stays with that worker, behind what it already has: what a future spawns
    * is most often to do with what it is doing, its handles among it, and a handle is cheapest used from one thread.
    * It is not tied there: a worker with nothing to do takes it (`stealFor`). A task from any other thread goes to the
    * workers in turn.
    */
  override def execute(body: Runnable): Unit =
    checkRunning()
    val current = workerOf(Thread.currentThread())
    if current != null then current.spawnLocal(body)
    // The check alone can pass just before the workers leave.
    else if !nextWorker().submit(body) then throw new IllegalStateException("Scheduler has shut down")

  /** A task for `thief`, which has nothing to do, to run now, with half of what the worker it came from had to spare
    * moved to the thief's inbox: from the next worker with tasks to spare, or null.
    */
  private def stealFor(thief: Worker[Op1]): Runnable | Null = {
    val count = workerGroup.length
    var task: Runnable | Null = null
    var i = 1
    while task == null && i < count do
      task = workerGroup((thief.id + i) % count).takeHalfFor(thief)
      i += 1
    task
  }

  private def anyParked(): Boolean = {
    var i = 0
    var found = false
    while !found && i < workerGroup.length do
      found = workerGroup(i).isParked
      i += 1
    found
  }

  /** `Io.pinned` on a pool: the future stays on the worker it is on while `body` runs. Whatever resumes it, it goes
    * on there and nowhere else: an op of this pool's resumes it there itself, and anything else it waits for is
    * followed by a move back. Outside any such scope a future is resumed where its op was done, and from there may be
    * taken by a worker with nothing to do (`Worker.shareUnpinned`); as `body` ends the future offers itself to one,
    * if there is one. A scope inside another is already pinned, and does neither.
    */
  override def pinned[T](body: Async ?=> T)(using async: Async): T =
    async match
      case _: PinnedAsync => body
      case _              =>
        try body(using PinnedAsync(async, workerOfCurrentThread()))
        finally offer(async)

  /** Moves the future to `home`, if it is not there. Under no group: a cancel must not leave it somewhere else. */
  private def goTo(base: Async, home: Worker[Op1]): Unit =
    if workerOf(Thread.currentThread()) ne home then
      base.withGroup(CompletionGroup.Unlinked).await(Hop(home))

  /** Has a worker with nothing to do take the future, if there is one; otherwise it goes on where it is. */
  private def offer(base: Async): Unit = {
    val worker = workerOf(Thread.currentThread())
    if worker != null && anyParked() then base.withGroup(CompletionGroup.Unlinked).await(Offer(worker))
  }

  /** The context of a future inside `pinned`: whatever it waits for, it goes on at `home`. */
  private final class PinnedAsync(val outer: Async, val home: Worker[Op1])
      extends Async(using outer.support, outer.scheduler) {
    def group: CompletionGroup = outer.group
    def withGroup(group: CompletionGroup): Async = PinnedAsync(outer.withGroup(group), home)
    def await[T](src: Async.Source[T]): T =
      // An op of this pool's resumes it at home itself (`resumingAt`). Anything else may resume it elsewhere.
      try outer.await(src)
      finally goTo(outer, home)
  }

  /** Completes its one listener on `home`'s thread, which resumes the future awaiting it there. */
  private final class Hop(home: Worker[Op1]) extends Async.Source[Unit] {
    def poll(k: Listener[Unit]): Boolean = false
    def onComplete(k: Listener[Unit]): Unit =
      home.submit(() => WorkpoolScheduler.resumingAt(home)(k.completeNow((), this): Unit)): Unit
    def dropListener(k: Listener[Unit]): Unit = ()
  }

  /** Completes its one listener from a task of `worker`'s that any worker with nothing to do may take, and that
    * `worker` runs itself if none does: the future awaiting it goes on wherever that is.
    */
  private final class Offer(worker: Worker[Op1]) extends Async.Source[Unit] {
    def poll(k: Listener[Unit]): Boolean = false
    def onComplete(k: Listener[Unit]): Unit = worker.offer(() => k.completeNow((), this): Unit)
    def dropListener(k: Listener[Unit]): Unit = ()
  }

  /** Wakes one worker that is waiting with nothing to do, if there is one, for it to take from `busy`. */
  private def wakeThief(busy: Worker[Op1]): Unit = {
    val count = workerGroup.length
    var i = 1
    while i < count do
      val worker = workerGroup((busy.id + i) % count)
      if worker.wakeIfParked() then i = count
      else i += 1
  }

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
    def setTimeout(timeoutMillis: Long, source: TimerSource[?]): Unit

    /** Suspends until the op is settled, and returns or throws its outcome. Only the op ends the wait: cancelled
      * through the group it is linked to, it settles at once as cancelled if no worker has claimed it, and otherwise
      * keeps the performer waiting for the worker that claimed it, which should test for cancellation as soon as
      * possible or resolve with a complete outcome.
      */
    def parkUntilSettled()(using Async): Unit

    def clearTimeout(): Unit

    /** The worker the future performing the op is pinned to, or null: set for each performance. */
    def pinnedTo(home: AnyRef | Null): Unit
  }

  val SuccessUnit: Try[Unit] = Success(())

  /** How many ops one worker performs between turns before the future performing the next yields. */
  inline val OpBudget = 64

  /** A worker's thread, which carries its worker for whoever asks which worker the current thread is. */
  final class WorkerThread(val worker: Worker[?], body: Runnable) extends Thread(body) {

    /** While this thread completes the listener of a pinned future: the worker the future is pinned to, which is
      * where the scheduler then resumes it. Null otherwise. This thread's alone.
      */
    var resumeHome: AnyRef | Null = null
  }

  /** Runs `complete`, which completes a listener of a future pinned to `home`: a worker's thread notes `home` for the
    * scheduler's `resumeAsync`, which comes within. On any other thread the future is resumed on some worker, and
    * finds its own way home (`PinnedAsync.await`).
    */
  inline def resumingAt(home: AnyRef)(inline complete: Unit): Unit =
    Thread.currentThread() match
      case thread: WorkerThread =>
        thread.resumeHome = home
        try complete
        finally thread.resumeHome = null
      case _ => complete

  trait ReentrantOp extends Cancellable {

    /** The op is not linked to the performer's group here: it links itself for as long as it waits, in
      * `parkUntilSettled`, and an op that is done as it is tried never does.
      */
    inline final def performWith(inline fn: Async ?=> Unit)(using Async): Unit =
      requestEntry()
      try fn
      finally requestExit()

    /** Aquires an entry permit. */
    def requestEntry()(using Async): Unit

    /** Potentially resets the entry permit. The op is unlinked by then. */
    def requestExit()(using Async): Unit
  }

  final class DoAfter(val owner: Worker[?], val deadlineNow: Long, body: Runnable) extends TimerOp {
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

  abstract class Worker[Op1 <: TimerOp](val id: Int)
      extends TimerHeapImpl[Op1 | DoAfter],
        EventSource {

    type ThisWorkerSource <: EventSource
    def asThisWorkerSource: this.type & ThisWorkerSource

    // private[kqueue] val inbox: java.util.concurrent.ArrayBlockingQueue[Runnable] =
    //   new java.util.concurrent.ArrayBlockingQueue[Runnable](1024)
    private val inbox: java.util.concurrent.ConcurrentLinkedQueue[Runnable] =
      new java.util.concurrent.ConcurrentLinkedQueue[Runnable]

    /** Whether the worker has taken `task`, which it refuses once it has left `loop`. */
    def submit(task: Runnable): Boolean = {
      println(
        s"submit called on worker $id on thread ${Thread.currentThread().getName()}"
      )
      queued.incrementAndGet()
      inbox.add(task)
      wake()
      !exited || !takeBack(task)
    }

    /** A task from this worker's own thread: no wake-up, as the worker is running, and comes to its inbox in turn. */
    private[WorkpoolScheduler] def spawnLocal(task: Runnable): Unit = {
      // One task, or a pair, which most often work with each other, the worker comes to as soon as what it is running
      // lets go. A third behind them is worth another worker's while, and is what gets a thief called if what is
      // running keeps the worker.
      val behindOthers = queued.getAndIncrement() > 1
      inbox.add(task)
      if behindOthers then askForThief()
    }

    /** How many tasks are in the inbox, or about to be: what tells one task waiting its turn from a backlog. */
    private val queued = new java.util.concurrent.atomic.AtomicInteger(0)

    /** The next task in the inbox, for this worker or a thief, or null. */
    private def takeNext(): Runnable | Null = {
      val task = inbox.poll()
      if task != null then queued.decrementAndGet()
      task
    }

    private def takeBack(task: Runnable): Boolean = {
      val removed = inbox.remove(task)
      if removed then queued.decrementAndGet()
      removed
    }

    /** For `thief`, a worker with nothing to do, on its own thread: half of this worker's tasks that it has not come
      * to yet, the oldest, if it has more than it will come to soon (`surplus`). One is returned for the thief to run
      * now, and the rest are put in the thief's inbox; null if there is nothing to take. Half at once, not one at a
      * time: a thief that came back for each would get few of them before this worker had run the rest, and how many
      * would be down to how the two happened to interleave. A worker that is merely awake does not take what another
      * is about to run.
      */
    private[WorkpoolScheduler] def takeHalfFor(thief: Worker[?]): Runnable | Null =
      if !surplus then null
      else
        var more = (queued.get() + 1) / 2 - 1 // besides the first
        val first = takeNext()
        if first != null then
          while more > 0 do
            val task = takeNext()
            if task == null then more = 0
            else
              thief.adopt(task)
              more -= 1
        first

    /** On this worker's own thread: a task taken from another worker, behind what this one already has. */
    private def adopt(task: Runnable): Unit = {
      queued.incrementAndGet()
      inbox.add(task)
    }

    /** Whether this worker has tasks to spare: set as it calls for a thief, and until a turn leaves its inbox empty. */
    @volatile private var surplus = false

    /** Set by the scheduler before the worker's thread starts: a task from another worker's inbox, or null. */
    private[WorkpoolScheduler] var steal: () => Runnable | Null = () => null

    /** Set by the scheduler before the worker's thread starts: wakes a worker that waits with nothing to do. */
    private[WorkpoolScheduler] var callThief: () => Unit = () => ()

    /** Whether this worker has called for a thief this turn. Worker thread only. One is called for at most once a
      * turn, when the worker is given a task it will not come to at once or ends a turn with tasks left over: so that
      * what one worker can keep up with stays on it, and what it can't, or what waits behind a task that keeps it, is
      * shared out a worker at a time.
      */
    private var thiefAsked = false

    private def askForThief(): Unit =
      surplus = true
      if !thiefAsked then
        thiefAsked = true
        callThief()

    /** Whether the worker is waiting, or about to, with nothing to do. */
    @volatile private var parked = false

    private[WorkpoolScheduler] def isParked: Boolean = parked

    /** Set by the scheduler before the worker's thread starts: whether some worker waits with nothing to do. */
    private[WorkpoolScheduler] var idleExists: () => Boolean = () => false

    /** On this worker's own thread: a task any worker with nothing to do may take at once, and one is called for. */
    private[WorkpoolScheduler] def offer(task: Runnable): Unit = {
      queued.incrementAndGet()
      inbox.add(task)
      askForThief()
    }

    private[WorkpoolScheduler] def wakeIfParked(): Boolean =
      if parked then
        parked = false // so that the next call wakes another
        wake()
        true
      else false

    /** How many ops have been performed on this worker since it last began a turn or had a future yield: an op that
      * waits ends the run of whoever performs it, so only ops done as they are tried add up. Worker thread only.
      */
    private var opsThisRun = 0

    /** Whether the future performing an op now should yield first: every `OpBudget` ops without a turn between. */
    private[WorkpoolScheduler] def opBudgetSpent(): Boolean =
      opsThisRun += 1
      if opsThisRun > WorkpoolScheduler.OpBudget then
        opsThisRun = 0
        true
      else false

    /** Worker thread only: makes `isEmpty` false for a poll that must not wait. */
    private var pollOnly = false

    /** Once set, the worker finishes its inbox and leaves `loop`. */
    @volatile private var stopped = false

    /** Set as the worker leaves `loop`, for `submit` to take back a task that came too late for it. */
    @volatile private var exited = false

    private[WorkpoolScheduler] def stop(): Unit = {
      stopped = true
      wake()
    }

    /** A future to resume. One that is `pinned` to this worker is resumed by it; any other may be given away. */
    private class Continuation[T, R](val suspension: Suspension[T, R], val value: T, val pinned: Boolean)
        extends Runnable:
      def run(): Unit = {
        val _ = suspension.resume(value)
      }

    /** Worker thread only: continuations to run as soon as the listener resuming them has returned, ahead of the rest
      * of the timers and the inbox. Declared before `thread`, which reads it from the moment it starts.
      */
    private val ready = new java.util.ArrayDeque[Continuation[?, ?]]

    private[WorkpoolScheduler] def resumeNext[T, R](suspension: Suspension[T, R], value: T, pinned: Boolean): Unit =
      println(
        s"resumeNext called on worker $id on thread ${Thread.currentThread().getName()}"
      )
      ready.addLast(Continuation(suspension, value, pinned))

    private[WorkpoolScheduler] val thread = {
      val t = new WorkerThread(this, () => loop())
      t.setName(s"WorkpoolSchedulerWorker-$id")
      t.setDaemon(true)
      t
    }

    /** Does the core work of performing an operation, when it returns the operation is guaranteed to have been
      * processed.
      */
    def performOp(op: Op1)(using Async): Unit

    def performClose(op: Op1 & AbstractCloseOp)(using Async): Unit =
      var shouldWait = false
      if op.hasDeadline then
        addTimer(op)
        shouldWait = true
      if !op.pollClose() then shouldWait = true
      if shouldWait then op.parkUntilSettled()

    /** Does a read or a write at once, or has it wait for its handle to be ready (see `performWhenReady`). */
    def performWhenReady(op: Op1 & AbstractReadinessOp[?, ThisWorkerSource])(using Async): Unit =
      op.performWhenReady(asThisWorkerSource)

    def isEmpty(): Boolean = inbox.isEmpty() && ready.isEmpty() && !pollOnly

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

    /** Worker thread only: the pinned futures `shareUnpinned` passes over, on their way back to `ready`. */
    private val passedOver = new java.util.ArrayDeque[Continuation[?, ?]]

    /** As a turn begins, with the futures the last poll found ready to resume: if there are more than a couple and
      * some worker has nothing to do, half of them, the latest, go to the inbox for it to take, and it is called.
      * Those pinned to this worker stay. What is not taken this worker runs itself, after the rest.
      */
    private def shareUnpinned(): Unit = {
      val waiting = ready.size()
      if waiting > 2 && idleExists() then
        var look = waiting / 2
        var moved = 0
        while look > 0 do
          val continuation = ready.pollLast()
          if continuation.pinned then passedOver.addFirst(continuation)
          else
            queued.incrementAndGet()
            inbox.add(continuation)
            moved += 1
          look -= 1
        while !passedOver.isEmpty() do ready.addLast(passedOver.pollFirst())
        if moved > 0 then askForThief()
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
        val task = takeNext()
        if task == null then inboxBudget = 0
        else
          guarded(task.run())
          runReady()
          inboxBudget -= 1
    }

    def loop(): Unit = {
      while !stopped || !isEmpty() do
        val now = System.nanoTime()
        shareUnpinned()
        fireTimers(now)
        runReady()
        drainInbox()
        // `stop`'s wake-up may have been spent while the inbox was still draining.
        if !stopped then
          // More than a turn's worth, and more than the task or two that a turn's budget may leave for the next.
          if queued.get() > 2 then askForThief()
          else if surplus then surplus = false
          val stolen = if isEmpty() then steal() else null
          if stolen != null then
            guarded(stolen.run())
            runReady()
            // Its own handles' events still get their turn, without waiting: there may be more to take.
            pollOnly = true
            try guarded(waitForDeadline(peekNextDeadline()))
            finally pollOnly = false
          else
            parked = isEmpty()
            try guarded(waitForDeadline(peekNextDeadline()))
            finally parked = false
        thiefAsked = false
        opsThisRun = 0
      exited = true
      // A task added before `exited` showed is either run here or taken back by `submit`, never lost.
      while !isEmpty() do
        drainInbox()
        runReady()
      close()
    }
  }
}
