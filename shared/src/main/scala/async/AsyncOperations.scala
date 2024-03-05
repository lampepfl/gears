package gears.async

import scala.concurrent.duration.Duration
import java.util.concurrent.TimeoutException
import gears.async.AsyncOperations.sleep

trait AsyncOperations:
  def sleep(millis: Long)(using Async): Unit

object AsyncOperations:
  inline def sleep(millis: Long)(using AsyncOperations, Async): Unit =
    summon[AsyncOperations].sleep(millis)

/** Runs [[op]] with a timeout. When the timeout occurs, [[op]] is cancelled through the given [[Async]] context, and
  * [[TimeoutException]] is thrown.
  */
def withTimeout[T](timeout: Duration)(op: Async ?=> T)(using Async, AsyncOperations): T =
  Async.select(
    Future(op).handle(_.get),
    Future(sleep(timeout.toMillis)).handle: _ =>
      throw TimeoutException()
  )

/** Runs [[op]] with a timeout. When the timeout occurs, [[op]] is cancelled through the given [[Async]] context, and
  * [[None]] is returned.
  */
def withTimeoutOption[T](timeout: Duration)(op: Async ?=> T)(using Async, AsyncOperations): Option[T] =
  Async.select(
    Future(op).handle(v => Some(v.get)),
    Future(sleep(timeout.toMillis)).handle(_ => None)
  )
