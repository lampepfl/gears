package gears.async

import scala.annotation.unchecked.uncheckedVariance
import java.util.concurrent.locks.ReentrantLock
import scala.concurrent.duration.FiniteDuration

// given VThreadScheduler.type = VThreadScheduler
// given VThreadSupport.type = VThreadSupport

object VThreadScheduler extends Scheduler:
  override def execute(body: Runnable): Unit = Thread.startVirtualThread(body)

  override def schedule(delay: FiniteDuration, body: Runnable): Cancellable =
    val th = Thread.startVirtualThread: () =>
      Thread.sleep(delay.toMillis)
      execute(body)
    () => th.interrupt()

object VThreadSupport extends AsyncSupport:

  type Scheduler = VThreadScheduler.type

  private final class VThreadLabel[R]():
    private var result: Option[R] = None
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    private[VThreadSupport] def clearResult() =
      lock.lock()
      result = None
      lock.unlock()

    private[VThreadSupport] def setResult(data: R) =
      lock.lock()
      try
        result = Some(data)
        cond.signalAll()
      finally
        lock.unlock()

    private[VThreadSupport] def waitResult(): R =
      lock.lock()
      try
        while result.isEmpty do
          cond.await()
        result.get
      finally
        lock.unlock()

  override opaque type Label[R] = VThreadLabel[R]

  // outside boundary: waiting on label
  //  inside boundary: waiting on suspension
  private final class VThreadSuspension[-T, +R](using private[VThreadSupport] val l: Label[R] @uncheckedVariance) extends gears.async.Suspension[T, R]:
    private var nextInput: Option[T] = None
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    private[VThreadSupport] def setInput(data: T) =
      lock.lock()
      try
        nextInput = Some(data)
        cond.signalAll()
      finally
        lock.unlock()

    // variance is safe because the only caller created the object
    private[VThreadSupport] def waitInput(): T @uncheckedVariance =
      lock.lock()
      try
        while nextInput.isEmpty do
          cond.await()
        nextInput.get
      finally
        lock.unlock()

    // normal resume only tells other thread to run again -> resumeAsync may redirect here
    override def resume(arg: T): R =
      l.clearResult()
      setInput(arg)
      l.waitResult()

  override opaque type Suspension[-T, +R] <: gears.async.Suspension[T, R] = VThreadSuspension[T, R]

  override def boundary[R](body: (Label[R]) ?=> R): R =
    val label = VThreadLabel[R]()
    Thread.startVirtualThread: () =>
      val result = body(using label)
      label.setResult(result)

    label.waitResult()

  override private[async] def resumeAsync[T, R](suspension: Suspension[T, R])(arg: T)(using Scheduler): Unit =
      suspension.l.clearResult()
      suspension.setInput(arg)

  override def scheduleBoundary(body: (Label[Unit]) ?=> Unit)(using Scheduler): Unit =
    Thread.startVirtualThread: () =>
      val label = VThreadLabel[Unit]()
      body(using label)

  override def suspend[T, R](body: Suspension[T, R] => R)(using l: Label[R]): T =
    val sus = new VThreadSuspension[T, R]()
    val res = body(sus)
    l.setResult(res)
    sus.waitInput()
