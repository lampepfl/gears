package gears.async.io
package shareable

import gears.async.Async
import gears.async.CompletionGroup
import gears.async.Future

import java.util.concurrent.atomic.AtomicInteger
import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal

/** The threads that work which may block runs on, off the workers: blocking code, the file calls, name lookups. As many
  * as there is work for at once, each kept a while for the next.
  */
object BlockingPool {

  /** How long a thread with no work waits for some before it ends. */
  inline val IdleMillis = 60_000L

  private val queue = new java.util.concurrent.LinkedBlockingQueue[Runnable]()

  /** The threads waiting for work that no task has claimed yet: `execute` claims one, or starts a thread if there is
    * none, so that there is a thread for each task. A task is queued, not handed over: whoever gives it does not wait
    * for the thread that takes it, and a thread that finds the next task queued does not sleep between them.
    */
  private val idle = AtomicInteger(0)
  private val count = AtomicInteger(0)

  def execute(task: Runnable): Unit = if claim() then queue.put(task) else start(task)

  private def claim(): Boolean = {
    var waiting = idle.get()
    while waiting > 0 do
      if idle.compareAndSet(waiting, waiting - 1) then return true
      else waiting = idle.get()
    false
  }

  private def start(first: Runnable): Unit = {
    val thread = new Thread(() => loop(first), s"gears-io-blocking-${count.incrementAndGet()}")
    thread.setDaemon(true)
    thread.start()
  }

  private def loop(first: Runnable): Unit = {
    var task: Runnable | Null = first
    while task != null do
      Thread.interrupted(): Unit // never begun with an interrupt meant for the last
      try task.run()
      catch case failure: Throwable => failure.printStackTrace() // the thread goes on
      task = next()
  }

  /** The next task, waited for: null once none has come for `IdleMillis`, unless one claimed this thread meanwhile,
    * which is then on its way.
    */
  private def next(): Runnable | Null = {
    idle.incrementAndGet()
    var claimed = false
    var task: Runnable | Null = null
    while task == null do
      try
        if claimed then task = queue.take()
        else
          task = queue.poll(IdleMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
          if task == null then
            if claim() then return null
            else claimed = true
      catch case _: InterruptedException => () // a late interrupt does not end the thread
    task
  }
}

/** An op whose work may block, and so is done on a thread of `BlockingPool`'s while its performer waits. Once the work
  * is done, its thread claims the op, as a firing timer or a ready handle does: only then is what it made written where
  * the caller sees it (`commit`), and the op completed, on the performer's own worker, which resumes it there. An op
  * that was cancelled or timed out first is not claimed, so nothing is written after either, and what the work made is
  * dropped (`discard`).
  *
  * A cancel or the time limit settles the op at once, as for any op. What becomes of the work is then its own kind's:
  * work not yet begun is skipped; work under way is interrupted if it is `interruptible`; and if the performer `waits`
  * for it, `perform` returns only once the work has, so that what the work was lent, a buffer for one, is the caller's
  * again.
  */
trait WorkOffThread extends AbstractTimerHeapOp, TimeoutOnWake {

  /** The work, on a pool thread, which may block. What it makes it keeps to itself until `commit`. */
  protected def work(): Unit

  /** Before the work is handed to any thread, on the performer's: does it here and now if that takes no blocking, as an
    * answer kept from the last time does, and says whether it did. What it made is then the caller's at once
    * (`commit`), and the op is done as it was tried, with nothing to wait for.
    */
  protected def workWithoutBlocking(): Boolean = false

  /** After `work`, on its thread, with the op claimed: what the work made becomes the caller's. */
  protected def commit(): Unit

  /** After `work`, on its thread, the op having been settled otherwise meanwhile: what the work made is dropped. */
  protected def discard(): Unit = ()

  /** The handle the work is done on, if any: it must be the performing pool's run's. */
  protected def worksOn: BaseHandle | Null = null

  /** The performing pool's, for work that opens a handle: set as the op is performed, before its work begins. */
  @volatile private var performerOwner: HandleOwner | Null = null

  protected final def owner: HandleOwner = performerOwner.nn

  /** Whether work under way is interrupted when the op is cancelled or times out, as blocking code is. */
  protected def interruptible: Boolean

  /** Whether an op that was cancelled or timed out waits for work under way to return before its `perform` does. */
  protected def waits: Boolean

  /** One performance's work, as the pool runs it. */
  private final class Run(worker: Worker) extends Runnable {
    private inline val Pending = 0
    private inline val Running = 1
    private inline val Finished = 2
    private inline val Skipped = 3

    private val state = AtomicInteger(Pending)
    private var thread: Thread | Null = null // guarded by this run's monitor, as the two below are
    private var returned = false
    private var waiter: Future.Promise[Unit] | Null = null

    def run(): Unit =
      if state.compareAndSet(Pending, Running) then
        synchronized { thread = Thread.currentThread() }
        var failure: Throwable | Null = null
        try work()
        catch case thrown: Throwable => failure = thrown
          // No interrupt comes after this, and one that came too late for the work is not the next task's.
        synchronized {
          state.set(Finished)
          thread = null
        }
        Thread.interrupted(): Unit
        if claimAtWork() then
          val outcome =
            if failure != null then Failure(failure)
            else
              try
                commit()
                WorkpoolScheduler.SuccessUnit
              catch
                case NonFatal(thrown) => Failure(thrown)
          // On the performer's worker, which resumes it there as it does for any op it completes. A worker that has
          // left takes nothing: then from here.
          if !worker.submit(() => completeFromWork(outcome)) then completeFromWork(outcome)
        else if failure == null then
          try discard()
          catch case NonFatal(_) => ()
        val waiting = synchronized {
          returned = true
          waiter
        }
        if waiting != null then waiting.complete(Success(())): Unit

    /** The op was settled otherwise: work not begun is skipped, and work under way interrupted if `interrupt`. Whether
      * the work is, or was, under way.
      */
    def stop(interrupt: Boolean): Boolean =
      if state.compareAndSet(Pending, Skipped) then false
      else
        if interrupt then
          synchronized {
            val running = thread
            if running != null then running.interrupt()
          }
        true

    /** Waits for the work to have returned, whatever the performer's group says: it is why the performer waits. */
    def awaitReturned()(using async: Async): Unit =
      val w = synchronized {
        var p: Future.Promise[Unit] | Null = null
        if !returned then
          p = Future.Promise[Unit]()
          waiter = p
        p
      }
      if w != null then w.awaitResult(using async.withGroup(CompletionGroup.Unlinked)): Unit
  }

  /** Hands the work to the pool and waits for the op's outcome. `worker` is the performer's: it keeps the op's time
    * limit, and is where the op is completed once its work is done.
    */
  final def performOffThread(worker: Worker & Source)(using Async): Unit =
    val owner = worker.handleOwner
    val on = worksOn
    if on != null then owner.check(on)
    performerOwner = owner
    if workWithoutBlocking() then
      commit()
      return
    val run = new Run(worker)
    expose()
    if hasDeadline then holdTimerOn(worker)
    BlockingPool.execute(run)
    try parkUntilSettled()
    catch
      case stopped: Throwable =>
        // A cancel, the time limit, or the work's own failure, by when it has returned and there is nothing to stop.
        if run.stop(interruptible) && waits then run.awaitReturned()
        throw stopped

  /** One performance's work, done on the performer's own thread (see `performOnCaller`): what cancels it, and what its
    * time limit does.
    */
  private final class Here(thread: Thread) extends gears.async.Cancellable {
    private var running = false // guarded by this run's monitor

    def run(): Unit =
      // Settled already by a cancel that came first: the work is skipped, as work not yet begun is.
      if !settled then
        synchronized { running = true }
        var failure: Throwable | Null = null
        try work()
        catch case thrown: Throwable => failure = thrown
          // No interrupt comes after this, and one that came too late for the work is not the performer's to keep.
        synchronized { running = false }
        Thread.interrupted(): Unit
        if claimAtWork() then
          val outcome =
            if failure != null then Failure(failure)
            else
              try
                commit()
                WorkpoolScheduler.SuccessUnit
              catch case NonFatal(thrown) => Failure(thrown)
          completeFromWork(outcome)
        else if failure == null then
          try discard()
          catch case NonFatal(_) => ()

    private def interruptWork(): Unit =
      if interruptible then synchronized { if running then thread.interrupt() }

    /** The performer's group is cancelled: the op is settled as cancelled, unless its work is done, and the work
      * interrupted.
      */
    def cancel(): Unit = if cancelByPerformer() then interruptWork()

    /** The op's time limit is up: it is settled as timed out, unless its work is done, and the work interrupted. */
    def expire(): Unit =
      if claimAtDeadline() then
        timerWakeup(System.nanoTime())
        interruptWork()
  }

  /** As `performOffThread`, for a performer whose own thread may block, as a virtual thread may: the work is done here
    * and now, with no thread to hand it to and none to be woken by. A cancel or the time limit still settles the op at
    * once and interrupts work that is `interruptible`, but the performer, being the one at work, returns only once the
    * work has. So an op that does not wait for its work (`waits`) is handed to the pool all the same, since here it
    * could not leave it behind. `worker` fires the op's time limit.
    */
  final def performOnCaller(worker: Worker & Source)(using async: Async): Unit =
    if !waits then performOffThread(worker)
    else
      val owner = worker.handleOwner
      val on = worksOn
      if on != null then owner.check(on)
      performerOwner = owner
      if workWithoutBlocking() then
        commit()
        return
      val here = new Here(Thread.currentThread())
      expose()
      val limit =
        if hasDeadline then worker.runAfter(Math.max(deadlineNow - System.nanoTime(), 0L), () => here.expire())
        else null
      try gears.async.cancellationScope(here)(here.run())
      finally if limit != null then limit.cancel()
      parkUntilSettled() // settled by now: this returns its outcome, or throws it
}

/** Blocking code as an op (`Blocking.blocking`): interrupted by a cancel or its time limit, which wait for it. */
abstract class AbstractBlockingOp(body: () => Unit) extends SingleUseAbstractTimerHeapOp, WorkOffThread {
  protected final def interruptible: Boolean = true
  protected final def waits: Boolean = true
  protected final def work(): Unit = body()
  protected final def commit(): Unit = ()
}

/** As `AbstractBlockingOp`, with the code's value written into `into` once the op is claimed for it. */
abstract class AbstractBlockingValueOp[A <: AnyRef](body: () => A, into: Slot[A])
    extends SingleUseAbstractTimerHeapOp,
      WorkOffThread {
  private var value: A | Null = null

  override def onEntry(): Unit =
    super.onEntry()
    Checks.checkEmpty(into)

  protected final def interruptible: Boolean = true
  protected final def waits: Boolean = true
  protected final def work(): Unit = value = body()
  protected final def commit(): Unit = into.set(value.nn)
}

/** Resolves a host name (`Resolving.resolve`), which blocks, so off the workers. A lookup can't be interrupted, and
  * nothing of the caller's is lent to it: a cancel or the time limit is delivered at once, the lookup running on to its
  * end with its result dropped. Performed once.
  *
  * A name that was looked up a little while ago is answered from `ResolveCache`, on the performer's own thread, with no
  * lookup: what a lookup finds is kept there, whether or not the op that asked was still waiting for it.
  */
abstract class AbstractResolveOp(host: String, into: Slot[List[IpAddress]])
    extends SingleUseAbstractTimerHeapOp,
      WorkOffThread {
  Checks.checkHost(host)

  /** Every address of `host`, each once, or an `IOException` saying why there is none. May block. */
  def _lookup(host: String): List[IpAddress]

  private var found: List[IpAddress] = Nil

  override def onEntry(): Unit =
    super.onEntry()
    Checks.checkEmpty(into)

  protected final def interruptible: Boolean = false
  protected final def waits: Boolean = false
  override protected final def workWithoutBlocking(): Boolean =
    val kept = ResolveCache.get(host)
    if kept != null then found = kept
    kept != null

  protected final def work(): Unit =
    val looked = Checks.resolved(host, _lookup(host))
    ResolveCache.put(host, looked)
    found = looked

  protected final def commit(): Unit = into.set(found)
}
