package gears.async

import TaskSchedule.ExponentialBackoff

import scala.collection.mutable
import mutable.ListBuffer

import scala.compiletime.uninitialized
import scala.util.{Failure, Success, Try}
import scala.annotation.unchecked.uncheckedVariance
import java.util.concurrent.CancellationException
import scala.annotation.tailrec
import scala.util
import gears.async.Async.Listener

private val cancellationException = CancellationException()

/** A cancellable future that can suspend waiting for other asynchronous sources
 */
trait Future[+T] extends Async.OriginalSource[Try[T]], Cancellable:

  /** Wait for this future to be completed and return its result */
  def result(using async: Async): Try[T]

  /** Wait for this future to be completed, return its value in case of success,
   *  or rethrow exception in case of failure.
   */
  def value(using async: Async): T = result.get

  /** Eventually stop computation of this future and fail with
   *  a `Cancellation` exception.
   */
  def cancel(): Unit

object Future:

  /**  A future that is completed explicitly by calling its
   *  `complete` method. There are two public implementations
   *
   *   - RunnableFuture: Completion is done by running a block of code
   *   - Promise.future: Completion is done by external request.
   */
  private class CoreFuture[+T] extends Future[T]:

    @volatile protected var hasCompleted: Boolean = false
    protected var cancelRequest = false
    private var result: Try[T] = uninitialized // guaranteed to be set if hasCompleted = true
    private val waiting: mutable.Set[Try[T] => Boolean] = mutable.Set()

    // Async.Source method implementations

    def poll(k: Async.Listener[Try[T]]): Boolean =
      hasCompleted && k(result)

    def addListener(k: Async.Listener[Try[T]]): Unit = synchronized:
      waiting += k

    def dropListener(k: Async.Listener[Try[T]]): Unit = synchronized:
      waiting -= k

    // Cancellable method implementations

    def cancel(): Unit =
      cancelRequest = true

    // Future method implementations

    def result(using async: Async): Try[T] =
      val r = async.await(this)
      if cancelRequest then Failure(cancellationException) else r

    /** Complete future with result. If future was cancelled in the meantime,
     *  return a CancellationException failure instead.
     *  Note: @uncheckedVariance is safe here since `complete` is called from
     *  only two places:
     *   - from the initializer of RunnableFuture, where we are sure that `T`
     *     is exactly the type with which the future was created, and
     *   - from Promise.complete, where we are sure the type `T` is exactly
     *     the type with which the future was created since `Promise` is invariant.
     */
    private[Future] def complete(result: Try[T] @uncheckedVariance): Unit =
      val toNotify = synchronized:
        if hasCompleted then Nil
        else
          this.result = result
          hasCompleted = true
          val ws = waiting.toList
          waiting.clear()
          ws
      for listener <- toNotify do listener(result)

  end CoreFuture

  /** A future that is completed by evaluating `body` as a separate
   *  asynchronous operation in the given `scheduler`
   */
  private class RunnableFuture[+T](body: Async ?=> T)(using ac: Async) extends CoreFuture[T]:

    private var awaited: Option[ac.support.Suspension[Try[Nothing], Unit]] = None

    private def checkCancellation(): Unit =
      if cancelRequest then throw cancellationException

    /** a handler for Async */
    private def async(body: Async ?=> Unit)(using ac.support.Label[Unit]): Unit =
      class FutureAsync(val group: CompletionGroup)(using label: ac.support.Label[Unit]) extends Async(using ac.support, ac.scheduler):

        /** Await a source first by polling it, and, if that fails, by suspending
         *  in a onComplete call.
         */
        override def await[U](src: Async.Source[U]): U =
          checkCancellation()
          src.poll().getOrElse:
            ac.support.suspend[Try[U], Unit](k =>
              RunnableFuture.this.synchronized:
                checkCancellation()
                awaited = Some(k)
              src.onComplete: x =>
                val resumeNow = RunnableFuture.this.synchronized:
                  if awaited.isDefined then
                    awaited = None
                    true
                  else false
                if resumeNow then
                  k.resumeAsync(Try:
                    checkCancellation()
                    x
                  )
                true
            ).get

        override def sleep(millis: Long): Unit = // TODO
          val th = Thread.currentThread()
          RunnableFuture.this.synchronized:
            checkCancellation()
            awaited = Some(new FutureAsync.this.support.Suspension[Try[Nothing], Unit]{
              def resume(arg: util.Try[Nothing]): Unit = th.interrupt()

              private[async] def resumeAsync(arg: util.Try[Nothing])(using FutureAsync.this.support.Scheduler): Unit = resume(arg)
            }.asInstanceOf[ac.support.Suspension[Try[Nothing], Unit]])
            // cast is safe b/c FutureAsync.this.support == ac.support
          try Thread.sleep(millis)
          catch
            case _: InterruptedException => throw cancellationException

        override def withGroup(group: CompletionGroup) = FutureAsync(group)

      Async.awaitingGroup(body)(using FutureAsync(CompletionGroup().link()))
    end async

    ac.support.execute(() => ac.support.blockingBoundary[Unit](async:
      complete(Try({
        try
          val r = body
          checkCancellation()
          r
        catch
          case _: InterruptedException => throw cancellationException
          case _: CancellationException => throw cancellationException
      }))
      signalCompletion()
    ))

    override def cancel(): Unit =
      super.cancel()
      RunnableFuture.this.synchronized:
        val awaited1 = awaited
        awaited = None
        awaited1
      .foreach(_.resumeAsync(Failure(cancellationException)))

    link()

  end RunnableFuture

  /** Create a future that asynchronously executes `body` that defines
   *  its result value in a Try or returns failure if an exception was thrown.
   *  If the future is created in an Async context, it is added to the
   *  children of that context's root.
   */
  def apply[T](body: Async ?=> T)(using Async): Future[T] =
    RunnableFuture(body)

  /** A future that immediately terminates with the given result */
  def now[T](result: Try[T]): Future[T] =
    val f = CoreFuture[T]()
    f.complete(result)
    f

  extension [T](f1: Future[T])

    /** Parallel composition of two futures.
     *  If both futures succeed, succeed with their values in a pair. Otherwise,
     *  fail with the failure that was returned first.
     */
    def zip[U](f2: Future[U])(using Async): Future[(T, U)] = Future:
      Async.await(Async.either(f1, f2)) match
        case Left(Success(x1))  => (x1, f2.value)
        case Right(Success(x2)) => (f1.value, x2)
        case Left(Failure(ex))  => throw ex
        case Right(Failure(ex)) => throw ex

    /** Parallel composition of tuples of futures.
     * Future.Success(EmptyTuple) might be treated as Nil.
     */
    def *:[U <: Tuple](f2: Future[U])(using Async): Future[T *: U] = Future:
      Async.await(Async.either(f1, f2)) match
        case Left(Success(x1)) => x1 *: f2.value
        case Right(Success(x2)) => f1.value *: x2
        case Left(Failure(ex)) => throw ex
        case Right(Failure(ex)) => throw ex

    /** Alternative parallel composition of this task with `other` task.
     *  If either task succeeds, succeed with the success that was returned first.
     *  Otherwise, fail with the failure that was returned last.
     */
    def alt(f2: Future[T])(using Async): Future[T] = Future:
      Async.await(Async.either(f1, f2)) match
        case Left(Success(x1))    => x1
        case Right(Success(x2))   => x2
        case Left(_: Failure[?])  => f2.value
        case Right(_: Failure[?]) => f1.value

    /** Like `alt` but the slower future is cancelled.
     * If either task succeeds, succeed with the success that was returned first and the other is cancelled.
     * Otherwise, fail with the failure that was returned last.
     */
    def altC(f2: Future[T])(using Async): Future[T] = Future:
      Async.await(Async.either(f1, f2)) match
        case Left(Success(x1)) =>
          f2.cancel()
          x1
        case Right(Success(x2)) =>
          f1.cancel()
          x2
        case Left(_: Failure[?]) => f2.value
        case Right(_: Failure[?]) => f1.value

  end extension

  /** A promise defines a future that is be completed via the
   *  promise's `complete` method.
   */
  class Promise[T]:
    private val myFuture = CoreFuture[T]()

    /** The future defined by this promise */
    val future: Future[T] = myFuture

    /** Define the result value of `future`. However, if `future` was
     *  cancelled in the meantime complete with a `CancellationException`
     *  failure instead.
     */
    def complete(result: Try[T]): Unit = myFuture.complete(result)

  end Promise
end Future

/**
 * TaskSchedule describes the way in which a task should be repeated.
 * Tasks can be set to run for example every 100 milliseconds or repeated as long as they fail.
 * `maxRepetitions` describes the maximum amount of repetitions allowed, after that regardless
 * of TaskSchedule chosen, the task is not repeated anymore and the last returned value is returned.
 * `maxRepetitions` equal to zero means that repetitions can go on potentially forever.
 */
enum TaskSchedule:
  case Every(val millis: Long, val maxRepetitions: Long = 0)
  case ExponentialBackoff(val millis: Long, val exponentialBase: Int = 2, val maxRepetitions: Long = 0)
  case FibonacciBackoff(val millis: Long, val maxRepetitions: Long = 0)
  case RepeatUntilFailure(val millis: Long = 0, val maxRepetitions: Long = 0)
  case RepeatUntilSuccess(val millis: Long = 0, val maxRepetitions: Long = 0)

/** A task is a template that can be turned into a runnable future
 *  Composing tasks can be referentially transparent.
 *  Tasks can be also ran on a specified schedule.
 */
class Task[+T](val body: Async ?=> T):

  /** Start a future computed from the `body` of this task */
  def run(using Async) = Future(body)

  def schedule(s: TaskSchedule)(using async: Async): Task[T] =
    s match {
      case TaskSchedule.Every(millis, maxRepetitions) =>
        assert(millis >= 1)
        assert(maxRepetitions >= 0)
        Task {
          var repetitions = 0
          var ret: T = body
          repetitions += 1
          if (maxRepetitions == 1) ret
          else {
            while (maxRepetitions == 0 || repetitions < maxRepetitions) {
              async.sleep(millis)
              ret = body
              repetitions += 1
            }
            ret
          }
        }
      case TaskSchedule.ExponentialBackoff(millis, exponentialBase, maxRepetitions) =>
        assert(millis >= 1)
        assert(exponentialBase >= 2)
        assert(maxRepetitions >= 0)
        Task {
          var repetitions = 0
          var ret: T = body
          repetitions += 1
          if (maxRepetitions == 1) ret
          else {
            var timeToSleep = millis
            while (maxRepetitions == 0 || repetitions < maxRepetitions) {
              async.sleep(timeToSleep)
              timeToSleep *= exponentialBase
              ret = body
              repetitions += 1
            }
            ret
          }
        }
      case TaskSchedule.FibonacciBackoff(millis, maxRepetitions) =>
        assert(millis >= 1)
        assert(maxRepetitions >= 0)
        Task {
          var repetitions = 0
          var a: Long = 0
          var b: Long = 1
          var ret: T = body
          repetitions += 1
          if (maxRepetitions == 1) ret
          else {
            async.sleep(millis)
            ret = body
            repetitions += 1
            if (maxRepetitions == 2) ret
            else {
              while (maxRepetitions == 0 || repetitions < maxRepetitions) {
                val aOld = a
                a = b
                b = aOld + b
                async.sleep(b * millis)
                ret = body
                repetitions += 1
              }
              ret
            }
          }
        }
      case TaskSchedule.RepeatUntilFailure(millis, maxRepetitions) =>
        assert(millis >= 0)
        assert(maxRepetitions >= 0)
        Task {
          @tailrec
          def helper(repetitions: Long = 0): T =
            if (repetitions > 0 && millis > 0)
              async.sleep(millis)
            val ret: T = body
            ret match {
              case Failure(_) => ret
              case _ if (repetitions+1) == maxRepetitions && maxRepetitions != 0 => ret
              case _ => helper(repetitions + 2)
            }
          helper()
        }
      case TaskSchedule.RepeatUntilSuccess(millis, maxRepetitions) =>
        assert(millis >= 0)
        assert(maxRepetitions >= 0)
        Task {
          @tailrec
          def helper(repetitions: Long = 0): T =
            if (repetitions > 0 && millis > 0)
              async.sleep(millis)
            val ret: T = body
            ret match {
              case Success(_) => ret
              case _ if (repetitions + 1) == maxRepetitions && maxRepetitions != 0 => ret
              case _ => helper(repetitions + 2)
            }
          helper()
        }
    }

end Task

private def altAndAltCImplementation[T](shouldCancel: Boolean, futures: Future[T]*)(using Async): Future[T] = Future[T]:
  val fs: Seq[Future[(Try[T], Int)]] = futures.zipWithIndex.map({ (f, i) =>
    Future:
      try
        (Success(f.value), i)
      catch case e => (Failure(e), i)
  })

  @tailrec
  def helper(failed: Int, fs: Seq[(Future[(Try[T], Int)], Int)]): Try[T] =
    Async.await(Async.race( fs.map(_._1)* )) match
      case Success((Success(x), i)) =>
        if (shouldCancel) {
          for ((f, j) <- futures.zipWithIndex) {
            if (j != i) f.cancel()
          }
        }
        Success(x)
      case Success((Failure(e), i)) =>
        if (failed + 1 == futures.length)
          Failure(e)
        else
          helper(failed + 1, fs.filter({ case (_, j) => j != i }))
      case _ => assert(false)

  helper(0, fs.zipWithIndex).get

/** `alt` defined for multiple futures, not only two.
 * If either task succeeds, succeed with the success that was returned first.
 * Otherwise, fail with the failure that was returned last.
 */
def alt[T](futures: Future[T]*)(using Async): Future[T] =
  altAndAltCImplementation(false, futures*)

/** `altC` defined for multiple futures, not only two.
 * If either task succeeds, succeed with the success that was returned first and cancel all other tasks.
 * Otherwise, fail with the failure that was returned last.
 */
def altC[T](futures: Future[T]*)(using Async): Future[T] =
  altAndAltCImplementation(true, futures*)

def uninterruptible[T](body: Async ?=> T)(using ac: Async) =
  val fut = Async.blocking{
    val f = Future(body)

    def forceJoin(): Unit =
      try {
        Async.await(f)
      } catch {
        case e: InterruptedException =>
          forceJoin()
          throw e
        case e: CancellationException =>
          forceJoin()
          throw e
      }

    forceJoin()
    f
    }(using ac.support, ac.scheduler)
  Async.await(fut)
