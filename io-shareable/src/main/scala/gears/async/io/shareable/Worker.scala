package gears.async.io.shareable

import gears.async.Async
import gears.async.Cancellable
import gears.async.Suspension
import gears.async.io.shareable.TimerHeap.TimerOp
import gears.async.io.shareable.WorkpoolScheduler.WorkerThread

import scala.util.control.NonFatal

/** One of a pool's workers: a thread with a heap of timers, an inbox of tasks, the futures it is about to resume, and
  * an event source of its own, which its poller is. It turns for ever: firing the timers that are due, resuming what is
  * ready, running its tasks, and then waiting on its poller for whichever comes first of an event, a deadline and a
  * wake-up. When it has nothing to do it takes tasks from workers that have too many (`Inbox`).
  *
  * @param handleOwner
  *   what the handles opened on this worker belong to: its pool's, the same for each of the pool's workers.
  */
abstract class Worker(val id: Int, final val handleOwner: HandleOwner) extends TimerHeapImpl, EventSource {

  /** The ops this worker performs: its pool's. */
  type Op <: TimerOp

  /** Its pool's ops, and what its scheduler is asked to run later. */
  type Timer = Op | Worker.DoAfter

  /** The tasks given to this worker that it has not come to yet, which a worker with nothing to do may take some of. */
  private val inbox = new Inbox

  /** Whether a wake-up for a task given from another thread is on its way and the worker has not yet begun the turn it
    * brings: a task given meanwhile needs no wake-up of its own, as it is in the inbox before that turn drains it.
    * Cleared by the worker as it begins a turn, before it looks at its inbox.
    */
  private val wakePending = new java.util.concurrent.atomic.AtomicBoolean(false)

  /** Whether the worker has taken `task`, which it refuses once it has left `loop`. */
  def submit(task: Runnable): Boolean = {
    inbox.add(task): Unit
    if wakePending.compareAndSet(false, true) then wake()
    !exited || !inbox.takeBack(task)
  }

  /** A task from this worker's own thread: no wake-up, as the worker is running, and comes to its inbox in turn. */
  private[shareable] def spawnLocal(task: Runnable): Unit = {
    // One task, or a pair, which most often work with each other, the worker comes to as soon as what it is running
    // lets go. A third behind them is worth another worker's while, and is what gets a thief called if what is
    // running keeps the worker.
    val behindOthers = inbox.add(task) > 1
    if behindOthers then askForThief()
  }

  /** For `thief`, a worker with nothing to do, on its own thread: a task of this worker's for it to run now, with half
    * of what this one has to spare moved to its inbox, or null (see `Inbox.takeHalfInto`).
    */
  private[shareable] def takeHalfFor(thief: Worker): Runnable | Null = inbox.takeHalfInto(thief.inbox)

  /** How many turns the worker has begun. Read by the pool's starvation monitor, to tell a worker that is not coming to
    * its inbox: written by the worker's thread only, and read there a look at a time, so it is not volatile.
    */
  private var turns = 0L

  private[shareable] def turnsBegun: Long = turns

  /** Whether tasks wait in the inbox. */
  private[shareable] def hasQueued: Boolean = !inbox.isEmpty

  /** Whether something waits for the worker's next turn that another thread can see: a task in its inbox, or a timer
    * due at `now`. Events of its handles that its poller has not yet been asked for, and futures it is about to resume,
    * can't be seen from outside it.
    */
  private[shareable] def hasWaiting(now: Long): Boolean =
    hasQueued || {
      val deadline = peekNextDeadline()
      deadline >= 0 && deadline - now <= 0
    }

  /** For the pool's starvation monitor, on its thread, this worker being kept from its inbox: the tasks there now are
    * given to `other`, in their order. How many were. Its timers, and what it is about to resume, stay its own.
    */
  private[shareable] def moveQueuedTo(other: Worker): Int = {
    var moved = 0
    var more = inbox.size
    while more > 0 do
      val task = inbox.take()
      if task == null then more = 0
      else if other.submit(task) then
        moved += 1
        more -= 1
      else
        // The pool is shutting down, and `other` has left: back where it was, for this worker to run as it leaves.
        inbox.add(task): Unit
        more = 0
    moved
  }

  /** Set by the scheduler before the worker's thread starts: a task from another worker's inbox, or null. */
  private[shareable] var steal: () => Runnable | Null = () => null

  /** Set by the scheduler before the worker's thread starts: wakes a worker that waits with nothing to do. */
  private[shareable] var callThief: () => Unit = () => ()

  /** Whether this worker has called for a thief this turn. Worker thread only. One is called for at most once a turn,
    * when the worker is given a task it will not come to at once or ends a turn with tasks left over: so that what one
    * worker can keep up with stays on it, and what it can't, or what waits behind a task that keeps it, is shared out a
    * worker at a time.
    */
  private var thiefAsked = false

  private def askForThief(): Unit =
    inbox.declareSurplus()
    if !thiefAsked then
      thiefAsked = true
      callThief()

  /** Whether the worker is waiting, or about to, with nothing to do. */
  @volatile private var parked = false

  private[shareable] def wakeIfParked(): Boolean =
    if parked then
      parked = false // so that the next call wakes another
      wake()
      true
    else false

  /** How many ops have been performed on this worker since it last began a turn or had a future yield: an op that waits
    * ends the run of whoever performs it, so only ops done as they are tried add up. Worker thread only.
    */
  private var opsThisRun = 0

  /** Whether the future performing an op now should yield first: every `OpBudget` ops without a turn between. */
  private[shareable] def opBudgetSpent(): Boolean =
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

  private[shareable] def stop(): Unit = {
    stopped = true
    wake()
  }

  private class Continuation[T, R](val suspension: Suspension[T, R], val value: T):
    def run(): Unit = {
      val _ = suspension.resume(value)
    }

  /** Worker thread only: continuations to run as soon as the listener resuming them has returned, ahead of the rest of
    * the timers and the inbox. Declared before `thread`, which reads it from the moment it starts.
    */
  private val ready = new java.util.ArrayDeque[Continuation[?, ?]]

  private[shareable] def resumeNext[T, R](suspension: Suspension[T, R], value: T): Unit =
    ready.addLast(Continuation(suspension, value))

  /** The thread that drives this worker now: runs its loop, and so its tasks, its timers and its poller. Given by the
    * scheduler as the pool starts, and again each time the one before steps out (`stepOut`).
    */
  @volatile private[shareable] var driver: WorkerThread | Null = null

  /** Set by the scheduler before the worker is first driven: gives the worker another thread to drive it, as the
    * calling thread, which drove it, steps out. Throws if there is none to be had.
    */
  private[shareable] var handOver: () => Unit = () => throw new IllegalStateException("No thread to hand over to")

  /** How many times the worker has been handed from one thread to the next. A thread's `loop` remembers the count it
    * began at, and stops the moment it differs: by then another thread drives the worker, and everything of the
    * worker's own, its inbox, its timers, what it is about to resume, is that thread's to touch.
    */
  private var drives = 0

  /** For a future running on this worker's thread that is about to block the thread, as blocking code and a file's
    * calls do: the thread stops being the worker's, and another takes the worker over, from where this one left it, so
    * that the worker's other tasks, its timers and its handles' events go on meanwhile. Whether it did: if not, there
    * was no thread to be had, and the caller is still on the worker.
    *
    * From here on the calling thread is no worker's. It must touch nothing of this worker's but through what any thread
    * may call, and the future it runs must not go on to its own code there: it comes back with `comeBack`, which
    * suspends it and resumes it on the worker. As the future suspends, the thread returns into the `loop` it was
    * running, which sees the worker has moved on, and leaves.
    */
  private[shareable] def stepOut(): Boolean = {
    val me = Thread.currentThread()
    if driver ne me then false
    else
      val thread = me.asInstanceOf[WorkerThread]
      thread.worker = null
      drives += 1
      try
        handOver()
        true
      catch
        case NonFatal(_) =>
          // Nobody else drives it: this thread still does, as if it had never stepped out.
          drives -= 1
          thread.worker = this
          driver = thread
          false
  }

  /** Whether the worker has tasks to run or futures to resume besides the one its thread is running now. Worker thread
    * only.
    */
  private[shareable] def hasOtherWork: Boolean = !inbox.isEmpty || !ready.isEmpty()

  /** For a future whose thread stepped out of this worker (`stepOut`), once what blocked has returned: suspends the
    * future, and resumes it on the worker, whichever thread drives it by then. Whatever the future's group says: it
    * must not go on where it is.
    */
  private[shareable] def comeBack()(using async: Async): Unit = {
    val worker = this
    val back = new Async.Source[Unit] {
      def poll(k: gears.async.Listener[Unit]): Boolean = false
      def onComplete(k: gears.async.Listener[Unit]): Unit =
        // On the worker, which resumes the future there as it does any waiter it completes. A worker that has left
        // takes nothing: then from here, which gives it to whichever worker will have it.
        if !worker.submit(() => k.completeNow((), this): Unit) then k.completeNow((), this): Unit
      def dropListener(k: gears.async.Listener[Unit]): Unit = ()
    }
    async.withGroup(gears.async.CompletionGroup.Unlinked).await(back)
  }

  /** Does the core work of performing an operation, when it returns the operation is guaranteed to have been processed.
    */
  def performOp(op: Op)(using Async): Unit

  /** As a task of this worker's: the report is made on its thread, where its own are. */
  override def reportLater(handle: Handle, read: Boolean): Unit =
    submit(() => handle.readiness(read).report(this, ReadinessOp.Unknown)): Unit

  /** As a timer of this worker's. */
  override protected def makeSchedule(nanos: Long, body: Runnable): Worker.DoAfter =
    Worker.DoAfter(this, TimerHeap.deadlineAfter(nanos), body)

  def isEmpty(): Boolean = inbox.isEmpty && ready.isEmpty() && !pollOnly

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
  private[shareable] var onFatal: Throwable => Unit = _ => ()

  /** A worker whose poller has failed can't be told that its handles are ready: the ops waiting on them would wait for
    * ever. Fatal to the program, as an error in a worker is: it is reported, and cancels the program (see `rootGroup`),
    * which the worker stays for, as it still runs its tasks and fires its timers.
    */
  override protected def pollerFailed(failure: Throwable): Unit = {
    reportFailure(failure)
    onFatal(failure)
  }

  private def reportFailure(failure: Throwable): Unit = {
    val current = Thread.currentThread()
    // A handler that throws must not take the worker with it either.
    try current.getUncaughtExceptionHandler().uncaughtException(current, failure)
    catch case _: Throwable => failure.printStackTrace()
  }

  /** Has a budget so that tasks resuming each other can't keep the worker from its timers and inbox. Whether this
    * thread still drives the worker after it: a future it ran may have had it step out (see `drives`).
    */
  private def runReady(drive: Int): Boolean = {
    var readyBudget = 32
    while readyBudget > 0 do
      val continuation = ready.pollFirst()
      if continuation == null then readyBudget = 0
      else
        guarded(continuation.run())
        if drives != drive then return false
        readyBudget -= 1
    true
  }

  /** Each timer's waiter runs before the next timer leaves the heap, so that it can still move or cancel that one. */
  private def fireTimers(now: Long, drive: Int): Boolean = {
    var timerBudget = 32
    while timerBudget > 0 do
      val timer = nextReadyTimer(now)
      if timer == null then timerBudget = 0
      else
        guarded(timer.timerWakeup(now))
        if !runReady(drive) then return false
        timerBudget -= 1
    true
  }

  private def drainInbox(drive: Int): Boolean = {
    var inboxBudget = 32
    while inboxBudget > 0 do
      val task = inbox.take()
      if task == null then inboxBudget = 0
      else
        guarded(task.run())
        if drives != drive || !runReady(drive) then return false
        inboxBudget -= 1
    true
  }

  /** Runs the worker on the calling thread, which the scheduler made its driver, until the worker stops for good, or
    * until a future it runs has this thread step out of the worker (`stepOut`): from that moment the loop touches
    * nothing more of the worker's, and returns as soon as that future lets go of the thread.
    */
  def loop(): Unit = {
    val drive = drives
    drivenBy(Thread.currentThread())
    while !stopped || !isEmpty() do
      turns += 1
      if wakePending.get() then wakePending.set(false)
      val now = System.nanoTime()
      if !fireTimers(now, drive) || !runReady(drive) || !drainInbox(drive) then return
      // `stop`'s wake-up may have been spent while the inbox was still draining.
      if !stopped then
        // More than a turn's worth, and more than the task or two that a turn's budget may leave for the next.
        if inbox.size > 2 then askForThief()
        else inbox.clearSurplus()
        val stolen = if isEmpty() then steal() else null
        if stolen != null then
          guarded(stolen.run())
          if drives != drive || !runReady(drive) then return
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
    while !isEmpty() do if !drainInbox(drive) || !runReady(drive) then return
    close()
  }
}

object Worker {
  final class DoAfter(val owner: Worker, val deadlineNow: Long, body: Runnable) extends TimerOp, Cancellable {
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
}
