package gears.async

import scala.concurrent.duration.FiniteDuration

trait AsyncOperations:
  /** Suspends the current [[Async]] context for at least [[millis]] milliseconds. */
  def sleep(millis: Long)(using Async): Unit

object AsyncOperations:
  /** Suspends the current [[Async]] context for at least [[millis]] milliseconds.
    * @param millis
    *   The duration to suspend. Must be a positive integer.
    */
  inline def sleep(millis: Long)(using AsyncOperations, Async): Unit =
    summon[AsyncOperations].sleep(millis)

  /** Suspends the current [[Async]] context for at least [[millis]] milliseconds.
    * @param duration
    *   The duration to suspend. Must be positive.
    */
  inline def sleep(duration: FiniteDuration)(using AsyncOperations, Async): Unit =
    sleep(duration.toMillis)
