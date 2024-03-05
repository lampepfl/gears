package gears.async

import scala.util.Random
import scala.util.{Try, Success, Failure}
import scala.util.boundary
import scala.util.control.NonFatal
import scala.concurrent.duration._

import gears.async.Async
import gears.async.AsyncOperations.sleep
import gears.async.Retry.Delay

/** Utility class to perform asynchronous actions with retrying policies on exceptions.
  *
  * See [[Retry]] companion object for common policies as a starting point.
  */
case class Retry(
    retryOnSuccess: Boolean = false,
    maximumFailures: Option[Int] = None,
    delay: Delay = Delay.none
):
  /** Runs [[body]] with the current policy in its own scope, returning the result or the last failure as an exception.
    */
  def apply[T](op: => T)(using Async, AsyncOperations): T =
    @scala.annotation.tailrec
    def loop(failures: Int, lastDelay: Duration): T =
      Try(op) match
        case Failure(exception) =>
          if maximumFailures.exists(_ == failures) then // maximum failure count reached
            throw exception
          else
            val toSleep = delay.delayFor(failures + 1, lastDelay)
            sleep(toSleep.toMillis)
            loop(failures + 1, toSleep)
        case Success(value) =>
          if retryOnSuccess then
            sleep(delay.delayFor(0, lastDelay).toMillis)
            loop(0, 0.second)
          else value

    loop(0, 0.seconds)

  /** Set the maximum failure count. */
  def withMaximumFailures(max: Int) =
    assert(max >= 0)
    this.copy(maximumFailures = Some(max))

  /** Set the delay policy between runs. See [[Delay]]. */
  def withDelay(delay: Delay) = this.copy(delay = delay)

object Retry:
  /** Ignores the result and attempt the action in an infinite loop. [[Retry.withMaximumFailures]] can be useful for
    * bailing on multiple failures. [[scala.util.boundary]] can be used for manually breaking.
    */
  val forever = Retry(retryOnSuccess = true)

  /** Returns the result, or attempt to retry if an exception is raised. */
  val untilSuccess = Retry(retryOnSuccess = false)

  /** Attempt to retry the operation *until* an exception is raised. In this mode, [[Retry]] always throws an exception
    * on return.
    */
  val untilFailure = Retry(retryOnSuccess = true).withMaximumFailures(0)

  /** Defines a delay policy based on the number of successive failures and the duration of the last delay. See
    * [[Delay]] companion object for some provided delay policies.
    */
  trait Delay:
    /** Return the expected duration to delay the next attempt from the current attempt.
      *
      * @param failuresCount
      *   The number of successive failures until the current attempt. Note that if the last attempt was a success,
      *   [[failuresCount]] is `0`.
      * @param lastDelay
      *   The duration of the last delay.
      */
    def delayFor(failuresCount: Int, lastDelay: Duration): Duration

  object Delay:
    /** No delays. */
    val none = constant(0.second)

    /** A fixed amount of delays, whether the last attempt was a success or failure. */
    def constant(duration: Duration) = new Delay:
      def delayFor(failuresCount: Int, lastDelay: Duration): Duration = duration

    /** Returns a delay policy for exponential backoff.
      * @param maximum
      *   The maximum duration possible for a delay.
      * @param starting
      *   The delay duration between successful attempts, and after the first failures.
      * @param multiplier
      *   Scale the delay duration by this multiplier for each successive failure. Defaults to `2`.
      * @param jitter
      *   An additional jitter to randomize the delay duration. Defaults to none. See [[Jitter]].
      */
    def backoff(maximum: Duration, starting: Duration, multiplier: Double = 2, jitter: Jitter = Jitter.none) =
      new Delay:
        def delayFor(failuresCount: Int, lastDelay: Duration): Duration =
          jitter
            .jitterDelay(
              lastDelay,
              if failuresCount <= 1 then starting
              else starting * scala.math.pow(multiplier, failuresCount - 1)
            )
            .min(maximum)

    /** Decorrelated exponential backoff: randomize between the last delay duration and a multiple of that duration. */
    def deccorelated(maximum: Duration, starting: Duration, multiplier: Double = 3) =
      new Delay:
        def delayFor(failuresCount: Int, lastDelay: Duration): Duration =
          val lowerBound =
            if failuresCount <= 1 then 0.second else lastDelay
          val upperBound =
            (if failuresCount <= 1 then starting
             else multiplier * lastDelay).min(maximum)
          Random.between(lowerBound.toMillis, upperBound.toMillis + 1).millis

  /** A randomizer for the delay duration, to avoid accidental coordinated DoS on failures. See [[Jitter]] companion
    * objects for some provided jitter implementations.
    */
  trait Jitter:
    /** Returns the *actual* duration to delay between attempts, given the theoretical given delay and actual last delay
      * duration, possibly with some randomization.
      * @param last
      *   The last delay duration performed, with jitter applied.
      * @param maximum
      *   The theoretical amount of delay governed by the [[Delay]] policy, serving as an upper bound.
      */
    def jitterDelay(last: Duration, maximum: Duration): Duration

  object Jitter:
    /** No jitter, always return the exact duration suggested by the policy. */
    val none = new Jitter:
      def jitterDelay(last: Duration, maximum: Duration): Duration = maximum

    /** Full jitter: randomize between 0 and the suggested delay duration. */
    val full = new Jitter:
      def jitterDelay(last: Duration, maximum: Duration): Duration = Random.between(0, maximum.toMillis + 1).millis

    /** Equal jitter: randomize between the last delay duration and the suggested delay duration. */
    val equal = new Jitter:
      def jitterDelay(last: Duration, maximum: Duration): Duration =
        val base = maximum.toMillis / 2
        (base + Random.between(0, maximum.toMillis - base + 1)).millis
