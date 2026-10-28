package gears.async

import java.lang.invoke.{MethodHandles, VarHandle}
import java.util.concurrent.locks.ReentrantLock
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.duration.FiniteDuration

object VThreadScheduler extends Scheduler:
  private val VTFactory = Thread
    .ofVirtual()
    .name("gears.async.VThread-", 0L)
    .factory()

  override def execute(body: Runnable): Unit =
    val th = VTFactory.newThread(body)
    th.start()

  override def schedule(delay: FiniteDuration, body: Runnable): Cancellable = ScheduledRunnable(delay, body)

  private class ScheduledRunnable(val delay: FiniteDuration, val body: Runnable) extends Cancellable {
    @volatile var interruptGuard = true // to avoid interrupting the body

    val th = VTFactory.newThread: () =>
      try Thread.sleep(delay.toMillis)
      catch case e: InterruptedException => () /* we got cancelled, don't propagate */
      if ScheduledRunnable.interruptGuardVar.getAndSet(this, false) then body.run()
    th.start()

    final override def cancel(): Unit =
      if ScheduledRunnable.interruptGuardVar.getAndSet(this, false) then th.interrupt()
  }

  private object ScheduledRunnable:
    val interruptGuardVar =
      MethodHandles
        .lookup()
        .in(classOf[ScheduledRunnable])
        .findVarHandle(classOf[ScheduledRunnable], "interruptGuard", classOf[Boolean])

object VThreadSupport extends AsyncSupport:

  type Scheduler = VThreadScheduler.type

  /** Where a boundary's body leaves its result each time it suspends or finishes. Results are counted, so a `resume`
    * waits for a result newer than the one it saw before resuming, rather than for one not yet cleared: with
    * clear-then-wait, a `resumeAsync` clearing the result before a waiting `resume` had looked left that `resume`
    * waiting on, woken by every later suspension of the body for nothing.
    */
  private final class VThreadLabel[R]():
    private var result: Option[R] = None
    private var results = 0L // how many times a result has been set
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    /** How many results have been set so far, to wait for a newer one with `waitResult`. */
    private[VThreadSupport] def seen(): Long =
      lock.lock()
      try results
      finally lock.unlock()

    private[VThreadSupport] def setResult(data: R) =
      lock.lock()
      try
        result = Some(data)
        results += 1
        cond.signalAll()
      finally lock.unlock()

    /** The first result set after `after` results. */
    private[VThreadSupport] def waitResult(after: Long): R =
      lock.lock()
      try
        while results <= after do cond.await()
        result.get
      finally lock.unlock()

  override opaque type Label[R] = VThreadLabel[R]

  // outside boundary: waiting on label
  //  inside boundary: waiting on suspension
  private final class VThreadSuspension[-T, +R](using private[VThreadSupport] val l: Label[R] @uncheckedVariance)
      extends gears.async.Suspension[T, R]:
    private var nextInput: Option[T] = None
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    private[VThreadSupport] def setInput(data: T) =
      lock.lock()
      try
        nextInput = Some(data)
        cond.signalAll()
      finally lock.unlock()

    // variance is safe because the only caller created the object
    private[VThreadSupport] def waitInput(): T @uncheckedVariance =
      lock.lock()
      try
        while nextInput.isEmpty do cond.await()
        nextInput.get
      finally lock.unlock()

    // normal resume only tells other thread to run again -> resumeAsync may redirect here
    override def resume(arg: T): R =
      val after = l.seen()
      setInput(arg)
      l.waitResult(after)

  override opaque type Suspension[-T, +R] <: gears.async.Suspension[T, R] = VThreadSuspension[T, R]

  override def boundary[R](body: (Label[R]) ?=> R): R =
    val label = VThreadLabel[R]()
    VThreadScheduler.execute: () =>
      val result = body(using label)
      label.setResult(result)

    label.waitResult(after = 0)

  override private[async] def resumeAsync[T, R](suspension: Suspension[T, R])(arg: T)(using Scheduler): Unit =
    suspension.setInput(arg)

  override def scheduleBoundary(body: (Label[Unit]) ?=> Unit)(using Scheduler): Unit =
    VThreadScheduler.execute: () =>
      val label = VThreadLabel[Unit]()
      body(using label)
      label.setResult(()) // releases a `resume` waiting for the body's next result, which there is none of

  override def suspend[T, R](body: Suspension[T, R] => R)(using l: Label[R]): T =
    val sus = new VThreadSuspension[T, R]()
    val res = body(sus)
    l.setResult(res)
    sus.waitInput()
