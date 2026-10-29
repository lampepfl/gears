package gears.async

import gears.async.AsyncOperations.sleep

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

/** Defines fundamental operations that require the support of the scheduler. This is commonly provided alongside with
  * the given implementation of [[Scheduler]].
  * @see
  *   [[Scheduler]] for the definition of the scheduler itself.
  */
trait AsyncOperations:
  import scala.concurrent.duration.*

  /** Suspends the current [[Async]] context for at least `millis` milliseconds. */
  def sleep(millis: Long)(using async: Async): Unit =
    Future
      .withResolver[Unit]: resolver =>
        val cancellable = async.scheduler.schedule(millis.millis, () => resolver.resolve(()))
        resolver.onCancel: () =>
          cancellable.cancel()
          resolver.rejectAsCancelled()
      .link()
      .await

  /** Yields the current [[Async]] context, possibly allowing other computations to run. */
  def `yield`()(using async: Async): Unit =
    val source = async match
      case _: Async.LockingAsync => new AsyncOperations.ScheduledYield(async.scheduler)
      case _                     => AsyncOperations.Yield
    async.withGroup(CompletionGroup.Unlinked).await(source)
    if async.group.isCancelled then throw new java.util.concurrent.CancellationException()

object AsyncOperations:
  // Poll must refuse the fast path. Registering the listener schedules the continuation at the back of the
  // scheduler queue, with no intermediate future, resolver, or extra cancellation-group membership.
  private object Yield extends Async.Source[Unit]:
    override def poll(): Option[Unit] = None
    def poll(listener: Listener[Unit]): Boolean = false
    def onComplete(listener: Listener[Unit]): Unit = listener.completeNow((), this)
    def dropListener(listener: Listener[Unit]): Unit = ()

  // A blocking boundary has no continuation to enqueue when a listener completes synchronously. Schedule its
  // notification explicitly, so yielding there also gives the scheduler a turn.
  private class ScheduledYield(scheduler: Scheduler) extends Async.Source[Unit]:
    override def poll(): Option[Unit] = None
    def poll(listener: Listener[Unit]): Boolean = false
    def onComplete(listener: Listener[Unit]): Unit = scheduler.execute(() => listener.completeNow((), this))
    def dropListener(listener: Listener[Unit]): Unit = ()

  /** Suspends the current [[Async]] context for at least `millis` milliseconds.
    * @param millis
    *   The duration to suspend, in milliseconds. Must be a positive integer.
    */
  inline def sleep(millis: Long)(using AsyncOperations, Async): Unit =
    summon[AsyncOperations].sleep(millis)

  /** Suspends the current [[Async]] context for `duration`.
    * @param duration
    *   The duration to suspend. Must be positive.
    */
  inline def sleep(duration: FiniteDuration)(using AsyncOperations, Async): Unit =
    sleep(duration.toMillis)

  /** Yields the current [[Async]] context, possibly allowing other computations to run. */
  inline def `yield`()(using AsyncOperations, Async) =
    summon[AsyncOperations].`yield`()

/** Runs `op` with a timeout. When the timeout occurs, `op` is cancelled through the given [[Async]] context, and
  * [[java.util.concurrent.TimeoutException]] is thrown.
  */
def withTimeout[T](timeout: FiniteDuration)(op: Async ?=> T)(using AsyncOperations, Async): T =
  Async.group:
    Async.select(
      Future(op).handle(_.get),
      Future(sleep(timeout)).handle: _ =>
        throw TimeoutException()
    )

/** Runs `op` with a timeout. When the timeout occurs, `op` is cancelled through the given [[Async]] context, and
  * [[None]] is returned.
  */
def withTimeoutOption[T](timeout: FiniteDuration)(op: Async ?=> T)(using AsyncOperations, Async): Option[T] =
  Async.group:
    Async.select(
      Future(op).handle(v => Some(v.get)),
      Future(sleep(timeout)).handle(_ => None)
    )
