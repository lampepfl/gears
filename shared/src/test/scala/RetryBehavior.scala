import gears.async.default.given
import gears.async.{Async, Future, Retry, Task, TaskSchedule}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.Random
import scala.util.{Failure, Success, Try}

import FiniteDuration as Duration
import Future.zip
import Retry.Delay

class RetryBehavior extends munit.FunSuite {
  test("Exponential backoff(2) 50ms, 5 times total schedule"):
    val start = System.currentTimeMillis()
    Async.blocking:
      var i = 0
      Retry.untilSuccess.withDelay(Delay.backoff(1.second, 50.millis)):
        i += 1
        if i < 5 then throw Exception("try again!")
    val end = System.currentTimeMillis()
    assert(end - start >= 50 + 100 + 200 + 400)
    assert(end - start < 50 + 100 + 200 + 400 + 800)

  test("UntilSuccess 150ms"):
    val start = System.currentTimeMillis()
    Async.blocking:
      var i = 0
      val ret = Retry.untilSuccess.withDelay(Delay.constant(150.millis)):
        if (i < 4) then
          i += 1
          throw AssertionError()
        else i
      assertEquals(ret, 4)
    val end = System.currentTimeMillis()
    assert(end - start >= 4 * 150)
    assert(end - start < 5 * 150)

  test("UntilFailure 150ms") {
    val start = System.currentTimeMillis()
    val ex = AssertionError()
    Async.blocking:
      var i = 0
      val ret = Try(Retry.untilFailure.withDelay(Delay.constant(150.millis)):
        if (i < 4) then
          i += 1
          i
        else throw ex
      )
      assertEquals(ret, Failure(ex))
    val end = System.currentTimeMillis()
    assert(end - start >= 4 * 150)
    assert(end - start < 5 * 150)
  }

  test("delay policies") {
    // start with wave1.length of failures, one success, and then wave2.length of failures
    def expectDurations(policy: Delay, wave1: Seq[Duration], success: Duration, wave2: Seq[Duration]) =
      var lastDelay: Duration = 0.second
      for (len, i) <- wave1.iterator.zipWithIndex do
        assertEquals(policy.delayFor(i + 1, lastDelay), len, clue = s"$policy $len $i")
        lastDelay = len
      assertEquals(policy.delayFor(0, lastDelay), success)
      lastDelay = success
      for (len, i) <- wave2.iterator.zipWithIndex do
        assertEquals(policy.delayFor(i + 1, lastDelay), len)
        lastDelay = len

    expectDurations(
      Delay.none,
      Seq(0.second, 0.second),
      0.second,
      Seq(0.second, 0.second)
    )
    expectDurations(
      Delay.constant(1.second),
      Seq(1.second, 1.second),
      1.second,
      Seq(1.second, 1.second)
    )

    expectDurations(
      Delay.backoff(1.minute, 1.second, multiplier = 5),
      Seq(1.second, 5.seconds, 25.seconds, 1.minute),
      1.second,
      Seq(1.second, 5.seconds, 25.seconds, 1.minute)
    )

    val decor = Delay.deccorelated(1.minute, 1.second, multiplier = 5)
    def decorLoop(i: Int, last: Duration, max: Duration): Unit =
      if last == max then assertEquals(decor.delayFor(i, max), max)
      else
        val delay = decor.delayFor(i, last)
        if i > 1 then assert(last <= delay)
        assert(delay <= max)
        decorLoop(i + 1, delay, max)
    decorLoop(1, 0.second, 1.minute)
    decorLoop(0, 5.second, 1.minute)
  }
}
