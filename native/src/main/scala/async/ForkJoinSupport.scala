package gears.async.native

import gears.async._
import scala.scalanative.runtime.{Continuations => native}
import java.util.concurrent.ForkJoinPool
import scala.concurrent.ExecutionContext
import scala.concurrent.JavaConversions._
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicBoolean
import gears.async.Future.Promise
import java.util.concurrent.CancellationException

class NativeContinuation[-T, +R] private[native] (val cont: T => R) extends Suspension[T, R]:
  def resume(arg: T): R = cont(arg)

trait NativeSuspend extends SuspendSupport:
  type Label[R] = native.BoundaryLabel[R]
  type Suspension[T, R] = NativeContinuation[T, R]

  override def boundary[R](body: (Label[R]) ?=> R): R =
    native.boundary(body)

  override def suspend[T, R](body: Suspension[T, R] => R)(using Label[R]): T = native.suspend[T, R](f => body(NativeContinuation(f)))
end NativeSuspend

/** Spawns a single thread that does all the sleeping. */
class ExecutorWithSleepThread(val exec: ExecutionContext) extends ExecutionContext with Scheduler {
  import scala.collection.mutable
  private case class Sleeper(wakeTime: Deadline, toRun: Runnable):
    var isCancelled = false
  private given Ordering[Sleeper] = new Ordering[Sleeper] {
    import math.Ordered.orderingToOrdered
    override def compare(x: Sleeper, y: Sleeper): Int = y.wakeTime.compare(x.wakeTime)
  }
  private val sleepers = mutable.PriorityQueue.empty[Sleeper]
  private var sleepingUntil: Option[Deadline] = None

  override def execute(body: Runnable): Unit = exec.execute(body)
  override def reportFailure(cause: Throwable): Unit = exec.reportFailure(cause)
  override def schedule(delay: FiniteDuration, body: Runnable): Cancellable = {
    val sleeper = Sleeper(delay.fromNow, body)
    // push to the sleeping priority queue
    this.synchronized {
      sleepers += sleeper
      val shouldWake = sleepingUntil.map(sleeper.wakeTime < _).getOrElse(true)
      if shouldWake then this.notifyAll()
    }
    () => { sleeper.isCancelled = true }
  }

  private def consideredOverdue(d: Deadline): Boolean = d.timeLeft <= 0.milli

  // Sleep until the first timer is due, or .wait() otherwise
  private def sleepLoop(): Unit = this.synchronized {
    while (true) {
      sleepingUntil = sleepers.headOption.map(_.wakeTime)
      sleepingUntil match
        case None => this.wait()
        case Some(value) =>
          if value.hasTimeLeft() then
            this.wait(value.timeLeft.max(10.millis).toMillis)
      // Pop sleepers until no more available
      while (sleepers.headOption.map(_.wakeTime.isOverdue()) == Some(true)) {
        val task = sleepers.dequeue()
        if !task.isCancelled then execute(task.toRun)
      }
    }
  }

  val sleeperThread = Thread(() => sleepLoop())
  sleeperThread.start()
}

class SuspendExecutorWithSleep(exec: ExecutionContext) extends ExecutorWithSleepThread(exec)
  with AsyncSupport
  with AsyncOperations
  with NativeSuspend {
  type Scheduler = this.type
  override def sleep(millis: Long)(using ac: Async): Unit = {
    val sleepingFut = Promise[Unit]()
    val innerCancellable = schedule(millis.millis, () => sleepingFut.complete(scala.util.Success(())))
    cancellationScope(() =>
      // we need to wrap the cancellable so that we mark the cancellation as well
      sleepingFut.complete(scala.util.Failure(CancellationException()))
      innerCancellable.cancel()
    ):
      sleepingFut.future.value
  }
}

class ForkJoinSupport extends SuspendExecutorWithSleep(new ForkJoinPool())
