package gears.async

import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NonFatal
import scala.util.Failure
import scala.util.Success
import gears.async.AsyncOperations.sleep
import scala.util.Random

case class Retry(
    retryOnSuccess: Boolean = false,
    maximumFailures: Option[Int] = None,
    delay: Delay = Delay.none
):
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
            sleep(delay.delayFor(0, 0.second).toMillis)
            loop(0, 0.second)
          else value

    loop(0, 0.seconds)

  def withMaximumFailures(max: Int) =
    assert(max >= 0)
    this.copy(maximumFailures = Some(max))

  def withDelay(delay: Delay) = this.copy(delay = delay)

object Retry:
  val forever = Retry(retryOnSuccess = true)
  val untilSuccess = Retry(retryOnSuccess = false)
  val untilFailure = Retry(retryOnSuccess = true).withMaximumFailures(0)

trait Delay:
  def delayFor(failuresCount: Int, lastDelay: Duration): Duration

object Delay:
  val none = constant(0.second)

  def constant(duration: Duration) = new Delay:
    def delayFor(failuresCount: Int, lastDelay: Duration): Duration = duration

  trait Jitter:
    def jitterDelay(last: Duration, maximum: Duration): Duration

  object Jitter:
    val none = new Jitter:
      def jitterDelay(last: Duration, maximum: Duration): Duration = maximum
    val equal = new Jitter:
      def jitterDelay(last: Duration, maximum: Duration): Duration =
        val base = maximum.toMillis / 2
        (base + Random.between(0, base)).millis
    def decorrelated(base: Duration, multiplier: Double = 3) = new Jitter:
      def jitterDelay(last: Duration, maximum: Duration): Duration =
        Random.between(base.toMillis, (last.toMillis * multiplier).toLong).millis

  def backoff(maximum: Duration, starting: Duration, multiplier: Double = 2, jitter: Jitter = Jitter.none) = new Delay:
    def delayFor(failuresCount: Int, lastDelay: Duration): Duration =
      jitter
        .jitterDelay(
          lastDelay,
          if failuresCount <= 1 then starting
          else (lastDelay.toMillis * multiplier).toLong.millis
        )
        .min(maximum)
