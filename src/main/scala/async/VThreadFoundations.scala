package gears.async

import scala.annotation.unchecked.uncheckedVariance
import scala.util.Try
import scala.util.Success
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.Condition

trait VThreadSuspendFoundations extends SuspendFoundations:

  final class VThreadLabel[R]():
    private var result: Option[R] = None
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    private[VThreadSuspendFoundations] def clearResult() =
      lock.lock()
      result = None
      lock.unlock()

    private[VThreadSuspendFoundations] def setResult(data: R) =
      lock.lock()
      try
        result = Some(data)
        cond.signalAll()
      finally
        lock.unlock()

    private[VThreadSuspendFoundations] def waitResult(): R =
      lock.lock()
      try
        if result.isEmpty then
          cond.await()
        result.get
      finally
        lock.unlock()

  type Label[R] = VThreadLabel[R]

  // outside boundary: waiting on label
  //  inside boundary: waiting on suspension
  final class VThreadSuspension[-T, +R](using l: Label[R]) extends Suspension[T, R]:
    private var nextInput: Option[T] = None
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    private[VThreadSuspendFoundations] def setInput(data: T) =
      lock.lock()
      try
        nextInput = Some(data)
        cond.signalAll()
      finally
        lock.unlock()

    // variance is safe because the only caller created the object
    private[VThreadSuspendFoundations] def waitInput(): T @uncheckedVariance =
      lock.lock()
      try
        if nextInput.isEmpty then
          cond.await()
        nextInput.get
      finally
        lock.unlock()

    // normal resume only tells other thread to run again -> resumeAsync may redirect here
    override def resume(arg: T): R =
      l.clearResult()
      setInput(arg)
      l.waitResult()

    override def resumeAsync(arg: T): Unit =
      l.clearResult()
      setInput(arg)

  override def boundary[R](body: (Label[R]) ?=> R): R =
    val label = VThreadLabel[R]()
    Thread.startVirtualThread: () =>
      val result = body(using label)
      label.setResult(result)

    label.waitResult()

  override def blockingBoundary[R](body: (Label[Unit]) ?=> R): R =
    val label = VThreadLabel[Unit]()
    body(using label)

  override def suspend[T, R](body: Suspension[T, R] => R)(using l: Label[R]): T =
    val sus = new VThreadSuspension[T, R]()
    val res = body(sus)
    l.setResult(res)
    sus.waitInput()

trait VThreadSchedulerFoundations extends SchedulerFoundations:
  override def execute(run: Runnable): Unit = Thread.startVirtualThread(run)

  def sleep(millis: Long, cancelHandler: WaitSuspension => Unit = _ => {}): Unit =
    val thread = Thread.currentThread()
    @volatile var result: Try[Unit] = Success(())
    cancelHandler(new WaitSuspension:
      override def resume(arg: Try[Unit]): Unit =
        result = arg
        thread.interrupt()
      override def resumeAsync(arg: Try[Unit]): Unit = resume(arg)
    )
    Thread.sleep(millis)
    result.get

object VThreadFoundations extends AsyncFoundations
                          with VThreadSchedulerFoundations
                          with VThreadSuspendFoundations
