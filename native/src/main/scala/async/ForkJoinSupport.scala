package gears.async.native

import gears.async.Future.Promise
import gears.async._

import java.util.concurrent.CancellationException
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
import scala.concurrent.JavaConversions._
import scala.concurrent.duration._
import scala.scalanative.runtime.{Continuations => nativeContinuations}

class NativeContinuation[-T, +R] private[native] (val cont: T => R) extends Suspension[T, R]:
  def resume(arg: T): R = cont(arg)

trait NativeSuspend extends SuspendSupport:
  type Label[R] = nativeContinuations.BoundaryLabel[R]
  type Suspension[T, R] = NativeContinuation[T, R]

  override def boundary[R](body: (Label[R]) ?=> R): R =
    nativeContinuations.boundary(body)

  override def suspend[T, R](body: Suspension[T, R] => R)(using Label[R]): T =
    nativeContinuations.suspend[T, R](f => body(NativeContinuation(f)))
end NativeSuspend

/** Spawns a single thread that does all the sleeping. */
class ExecutorWithSleepThread(val exec: ExecutionContext) extends ExecutionContext with Scheduler {
  import scala.collection.mutable
  private case class Sleeper(wakeTime: Deadline, toRun: Runnable):
    var isCancelled = false
  private given Ordering[Sleeper] = Ordering.by((sleeper: Sleeper) => sleeper.wakeTime).reverse
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

  // Sleep until the first timer is due, or .wait() otherwise
  private def sleepLoop(): Unit = this.synchronized {
    while (true) {
      sleepingUntil = sleepers.headOption.map(_.wakeTime)
      val current = sleepingUntil match
        case None =>
          this.wait()
          Deadline.now
        case Some(value) =>
          val current0 = Deadline.now
          val timeLeft = value - current0

          if timeLeft.toNanos > 0 then
            this.wait(timeLeft.toMillis.max(10))
            Deadline.now
          else current0

      // Pop sleepers until no more available
      while (sleepers.headOption.exists(_.wakeTime <= current)) {
        val task = sleepers.dequeue()
        if !task.isCancelled then execute(task.toRun)
      }
    }
  }

  val sleeperThread = Thread(() => sleepLoop())
  sleeperThread.setDaemon(true)
  sleeperThread.start()
}

class SuspendExecutorWithSleep(exec: ExecutionContext)
    extends ExecutorWithSleepThread(exec)
    with AsyncSupport
    with AsyncOperations
    with NativeSuspend {
  type Scheduler = this.type
}

class ForkJoinSupport extends SuspendExecutorWithSleep(new ForkJoinPool())
