package async

import scala.annotation.unchecked.uncheckedVariance
import scala.util.Try
import scala.util.Success

trait VThreadSuspendFoundations extends SuspendFoundations:

  final class VThreadLabel[R]():
    private var result: Option[R] = None

    private[VThreadSuspendFoundations] def clearResult() = synchronized:
      result = None

    private[VThreadSuspendFoundations] def setResult(data: R) = synchronized:
      result = Some(data)
      notifyAll()

    private[VThreadSuspendFoundations] def waitResult(): R = synchronized:
      if result.isEmpty then
        wait()
      result.get

  type Label[R] = VThreadLabel[R]

  // outside boundary: waiting on label
  //  inside boundary: waiting on suspension
  final class VThreadSuspension[-T, +R](using l: Label[R]) extends Suspension[T, R]:
    private var nextInput: Option[T] = None

    private[VThreadSuspendFoundations] def setInput(data: T) = synchronized:
      nextInput = Some(data)
      notifyAll()

    // variance is safe because the only caller created the object
    private[VThreadSuspendFoundations] def waitInput(): T @uncheckedVariance = synchronized:
      if nextInput.isEmpty then
        wait()
      nextInput.get

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
