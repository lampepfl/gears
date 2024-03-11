package gears.async

import TaskSchedule.ExponentialBackoff
import AsyncOperations.sleep

import scala.collection.mutable
import mutable.ListBuffer

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CancellationException
import scala.compiletime.uninitialized
import scala.util.{Failure, Success, Try}
import scala.annotation.unchecked.uncheckedVariance
import scala.annotation.tailrec
import scala.util
import scala.util.control.NonFatal

/** A cancellable future that can suspend waiting for other asynchronous sources
  */
trait Future[+T] extends Async.OriginalSource[Try[T]], Cancellable

object Future:

  /** A future that is completed explicitly by calling its `complete` method. There are three public implementations
    *
    *   - RunnableFuture: Completion is done by running a block of code
    *   - Promise.apply: Completion is done by external request.
    *   - withResolver: Completion is done by external request set up from a block of code.
    */
  private class CoreFuture[+T] extends Future[T]:

    @volatile protected var hasCompleted: Boolean = false
    protected var cancelRequest = AtomicBoolean(false)
    private var result: Try[T] = uninitialized // guaranteed to be set if hasCompleted = true
    private val waiting: mutable.Set[Listener[Try[T]]] = mutable.Set()

    // Async.Source method implementations

    def poll(k: Listener[Try[T]]): Boolean =
      if hasCompleted then
        k.completeNow(result, this)
        true
      else false

    def addListener(k: Listener[Try[T]]): Unit = synchronized:
      waiting += k

    def dropListener(k: Listener[Try[T]]): Unit = synchronized:
      waiting -= k

    // Cancellable method implementations

    def cancel(): Unit =
      setCancelled()

    override def link(group: CompletionGroup): this.type =
      // though hasCompleted is accessible without "synchronized",
      // we want it not to be run while the future was trying to complete.
      synchronized:
        if !hasCompleted || group == CompletionGroup.Unlinked then super.link(group)
        else this

    /** Sets the cancellation state and returns `true` if the future has not been completed and cancelled before. */
    protected final def setCancelled(): Boolean =
      !hasCompleted && cancelRequest.compareAndSet(false, true)

    /** Complete future with result. If future was cancelled in the meantime, return a CancellationException failure
      * instead. Note: @uncheckedVariance is safe here since `complete` is called from only two places:
      *   - from the initializer of RunnableFuture, where we are sure that `T` is exactly the type with which the future
      *     was created, and
      *   - from Promise.complete, where we are sure the type `T` is exactly the type with which the future was created
      *     since `Promise` is invariant.
      */
    private[Future] def complete(result: Try[T] @uncheckedVariance): Unit =
      val toNotify = synchronized:
        if hasCompleted then Nil
        else
          this.result = result
          hasCompleted = true
          val ws = waiting.toList
          waiting.clear()
          unlink()
          ws
      for listener <- toNotify do listener.completeNow(result, this)

  end CoreFuture

  /** A future that is completed by evaluating `body` as a separate asynchronous operation in the given `scheduler`
    */
  private class RunnableFuture[+T](body: Async.Spawn ?=> T)(using ac: Async) extends CoreFuture[T]:

    private var innerGroup: CompletionGroup = CompletionGroup()

    private def checkCancellation(): Unit =
      if cancelRequest.get() then throw new CancellationException()

    private class FutureAsync(val group: CompletionGroup)(using label: ac.support.Label[Unit])
        extends Async(using ac.support, ac.scheduler):
      /** Await a source first by polling it, and, if that fails, by suspending in a onComplete call.
        */
      override def await[U](src: Async.Source[U]): U =
        class CancelSuspension extends Cancellable:
          var suspension: ac.support.Suspension[Try[U], Unit] = uninitialized
          var listener: Listener[U] = uninitialized
          var completed = false

          def complete() = synchronized:
            val completedBefore = completed
            completed = true
            completedBefore

          override def cancel() =
            val completedBefore = complete()
            if !completedBefore then
              src.dropListener(listener)
              ac.support.resumeAsync(suspension)(Failure(new CancellationException()))

        if group.isCancelled then throw new CancellationException()

        src
          .poll()
          .getOrElse:
            val cancellable = CancelSuspension()
            val res = ac.support.suspend[Try[U], Unit](k =>
              val listener = Listener.acceptingListener[U]: (x, _) =>
                val completedBefore = cancellable.complete()
                if !completedBefore then ac.support.resumeAsync(k)(Success(x))
              cancellable.suspension = k
              cancellable.listener = listener
              cancellable.link(group) // may resume + remove listener immediately
              src.onComplete(listener)
            )
            cancellable.unlink()
            res.get

      override def withGroup(group: CompletionGroup) = FutureAsync(group)

    override def cancel(): Unit = if setCancelled() then this.innerGroup.cancel()

    link()
    ac.support.scheduleBoundary:
      val result = Async.withNewCompletionGroup(innerGroup)(Try({
        val r = body
        checkCancellation()
        r
      }).recoverWith { case _: InterruptedException | _: CancellationException =>
        Failure(new CancellationException())
      })(using FutureAsync(CompletionGroup.Unlinked))
      complete(result)

  end RunnableFuture

  /** Create a future that asynchronously executes [[body]] that defines its result value in a [[Try]] or returns
    * [[Failure]] if an exception was thrown.
    */
  def apply[T](body: Async.Spawn ?=> T)(using async: Async, spawnable: Async.Spawn & async.type): Future[T] =
    RunnableFuture(body)

  /** A future that immediately terminates with the given result */
  def now[T](result: Try[T]): Future[T] =
    val f = CoreFuture[T]()
    f.complete(result)
    f

  extension [T](f1: Future[T])

    /** Parallel composition of two futures. If both futures succeed, succeed with their values in a pair. Otherwise,
      * fail with the failure that was returned first.
      */
    def zip[U](f2: Future[U]): Future[(T, U)] =
      Future.withResolver: r =>
        Async
          .either(f1, f2)
          .onComplete(Listener { (v, _) =>
            v match
              case Left(Success(x1)) =>
                f2.onComplete(Listener { (x2, _) => r.complete(x2.map((x1, _))) })
              case Right(Success(x2)) =>
                f1.onComplete(Listener { (x1, _) => r.complete(x1.map((_, x2))) })
              case Left(Failure(ex))  => r.reject(ex)
              case Right(Failure(ex)) => r.reject(ex)
          })

    /** Parallel composition of tuples of futures. Future.Success(EmptyTuple) might be treated as Nil.
      */
    def *:[U <: Tuple](f2: Future[U]): Future[T *: U] = Future.withResolver: r =>
      Async
        .either(f1, f2)
        .onComplete(Listener { (v, _) =>
          v match
            case Left(Success(x1)) =>
              f2.onComplete(Listener { (x2, _) => r.complete(x2.map(x1 *: _)) })
            case Right(Success(x2)) =>
              f1.onComplete(Listener { (x1, _) => r.complete(x1.map(_ *: x2)) })
            case Left(Failure(ex))  => r.reject(ex)
            case Right(Failure(ex)) => r.reject(ex)
        })

    /** Alternative parallel composition of this task with `other` task. If either task succeeds, succeed with the
      * success that was returned first. Otherwise, fail with the failure that was returned last.
      */
    def alt(f2: Future[T]): Future[T] = altImpl(false)(f2)

    /** Like `alt` but the slower future is cancelled. If either task succeeds, succeed with the success that was
      * returned first and the other is cancelled. Otherwise, fail with the failure that was returned last.
      */
    def altWithCancel(f2: Future[T]): Future[T] = altImpl(true)(f2)

    inline def altImpl(inline withCancel: Boolean)(f2: Future[T]): Future[T] = Future.withResolver: r =>
      Async
        .raceWithOrigin(f1, f2)
        .onComplete(Listener { case ((v, which), _) =>
          v match
            case Success(value) =>
              inline if withCancel then (if which == f1 then f2 else f1).cancel()
              r.resolve(value)
            case Failure(_) =>
              (if which == f1 then f2 else f1).onComplete(Listener((v, _) => r.complete(v)))
        })

  end extension

  /** A promise defines a future that is be completed via the `complete` method.
    */
  trait Promise[T] extends Future[T]:
    inline def asFuture: Future[T] = this

    /** Define the result value of `future`. */
    def complete(result: Try[T]): Unit

  object Promise:
    def apply[T](): Promise[T] =
      new CoreFuture[T] with Promise[T]:
        override def cancel(): Unit =
          if setCancelled() then complete(Failure(new CancellationException()))

        /** Define the result value of `future`. However, if `future` was cancelled in the meantime complete with a
          * `CancellationException` failure instead.
          */
        override def complete(result: Try[T]): Unit = super[CoreFuture].complete(result)
  end Promise

  /** The group of handlers to be used in [[withResolver]]. As a Future is completed only once, only one of
    * resolve/reject/complete may be used and only once.
    */
  trait Resolver[-T]:
    /** Complete the future with a data item successfully */
    def resolve(item: T): Unit = complete(Success(item))

    /** Complete the future with a failure */
    def reject(exc: Throwable): Unit = complete(Failure(exc))

    /** Complete the future with a [[CancellationException]] */
    def rejectAsCancelled(): Unit = complete(Failure(new CancellationException()))

    /** Complete the future with the result, be it Success or Failure */
    def complete(result: Try[T]): Unit

    /** Register a cancellation handler to be called when the created future is cancelled. Note that only one handler
      * may be used. The handler should eventually complete the Future using one of complete/resolve/reject*. The
      * default handler is set up to [[rejectAsCancelled]] immediately.
      */
    def onCancel(handler: () => Unit): Unit
  end Resolver

  /** Create a promise that may be completed asynchronously using external means.
    *
    * The body is run synchronously on the callers thread to setup an external asynchronous operation whose
    * success/failure it communicates using the [[Resolver]] to complete the future.
    *
    * If the external operation supports cancellation, the body can register one handler using [[Resolver.onCancel]].
    */
  def withResolver[T](body: Resolver[T] => Unit): Future[T] =
    val future = new CoreFuture[T] with Resolver[T] with Promise[T] {
      @volatile var cancelHandle = () => rejectAsCancelled()
      override def onCancel(handler: () => Unit): Unit = cancelHandle = handler
      override def complete(result: Try[T]): Unit = super.complete(result)

      override def cancel(): Unit =
        if setCancelled() then cancelHandle()
    }
    body(future)
    future
  end withResolver

  /** Collects a list of futures into a channel of futures, arriving as they finish. */
  class Collector[T](futures: Future[T]*):
    private val ch = UnboundedChannel[Future[T]]()

    /** Output channels of all finished futures. */
    final def results = ch.asReadable

    private val listener = Listener((_, fut) =>
      // safe, as we only attach this listener to Future[T]
      ch.sendImmediately(fut.asInstanceOf[Future[T]])
    )

    protected final def addFuture(future: Future[T]) = future.onComplete(listener)

    futures.foreach(addFuture)
  end Collector

  /** Like [[Collector]], but exposes the ability to add futures after creation. */
  class MutableCollector[T](futures: Future[T]*) extends Collector[T](futures*):
    /** Add a new [[Future]] into the collector. */
    inline def add(future: Future[T]) = addFuture(future)
    inline def +=(future: Future[T]) = add(future)

  extension [T](fs: Seq[Future[T]])
    /** `.await` for all futures in the sequence, returns the results in a sequence, or throws if any futures fail. */
    def awaitAll(using Async) =
      val collector = Collector(fs*)
      for _ <- fs do collector.results.read().right.get.await
      fs.map(_.await)

    /** Like [[awaitAll]], but cancels all futures as soon as one of them fails. */
    def awaitAllOrCancel(using Async) =
      val collector = Collector(fs*)
      try
        for _ <- fs do collector.results.read().right.get.await
        fs.map(_.await)
      catch
        case NonFatal(e) =>
          fs.foreach(_.cancel())
          throw e

    /** Race all futures, returning the first successful value. Throws the last exception received, if everything fails.
      */
    def altAll(using Async): T = altImpl(false)

    /** Like [[altAll]], but cancels all other futures as soon as the first future succeeds. */
    def altAllWithCancel(using Async): T = altImpl(true)

    private inline def altImpl(withCancel: Boolean)(using Async): T =
      val collector = Collector(fs*)
      @scala.annotation.tailrec
      def loop(attempt: Int): T =
        collector.results.read().right.get.awaitResult match
          case Failure(exception) =>
            if attempt == fs.length then /* everything failed */ throw exception else loop(attempt + 1)
          case Success(value) =>
            inline if withCancel then fs.foreach(_.cancel())
            value
      loop(1)
end Future

/** TaskSchedule describes the way in which a task should be repeated. Tasks can be set to run for example every 100
  * milliseconds or repeated as long as they fail. `maxRepetitions` describes the maximum amount of repetitions allowed,
  * after that regardless of TaskSchedule chosen, the task is not repeated anymore and the last returned value is
  * returned. `maxRepetitions` equal to zero means that repetitions can go on potentially forever.
  */
enum TaskSchedule:
  case Every(val millis: Long, val maxRepetitions: Long = 0)
  case ExponentialBackoff(val millis: Long, val exponentialBase: Int = 2, val maxRepetitions: Long = 0)
  case FibonacciBackoff(val millis: Long, val maxRepetitions: Long = 0)
  case RepeatUntilFailure(val millis: Long = 0, val maxRepetitions: Long = 0)
  case RepeatUntilSuccess(val millis: Long = 0, val maxRepetitions: Long = 0)

/** A task is a template that can be turned into a runnable future Composing tasks can be referentially transparent.
  * Tasks can be also ran on a specified schedule.
  */
class Task[+T](val body: (Async, AsyncOperations) ?=> T):

  /** Run the current task and returns the result. */
  def run()(using Async, AsyncOperations): T = body

  /** Start a future computed from the `body` of this task */
  def start()(using async: Async, spawn: Async.Spawn & async.type, asyncOps: AsyncOperations) =
    Future(body)(using async, spawn)

  def schedule(s: TaskSchedule): Task[T] =
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
              sleep(millis)
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
              sleep(timeToSleep)
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
            sleep(millis)
            ret = body
            repetitions += 1
            if (maxRepetitions == 2) ret
            else {
              while (maxRepetitions == 0 || repetitions < maxRepetitions) {
                val aOld = a
                a = b
                b = aOld + b
                sleep(b * millis)
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
              sleep(millis)
            val ret: T = body
            ret match {
              case Failure(_)                                                      => ret
              case _ if (repetitions + 1) == maxRepetitions && maxRepetitions != 0 => ret
              case _                                                               => helper(repetitions + 2)
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
              sleep(millis)
            val ret: T = body
            ret match {
              case Success(_)                                                      => ret
              case _ if (repetitions + 1) == maxRepetitions && maxRepetitions != 0 => ret
              case _                                                               => helper(repetitions + 2)
            }
          helper()
        }
    }

end Task

def uninterruptible[T](body: Async ?=> T)(using ac: Async): T =
  val tracker = Cancellable.Tracking().link()

  val r =
    try
      val group = CompletionGroup()
      Async.withNewCompletionGroup(group)(body)
    finally tracker.unlink()

  if tracker.isCancelled then throw new CancellationException()
  r

def cancellationScope[T](cancel: Cancellable)(fn: => T)(using a: Async): T =
  cancel.link()
  try fn
  finally cancel.unlink()
