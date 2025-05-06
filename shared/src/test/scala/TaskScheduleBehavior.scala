import language.experimental.captureChecking

import gears.async.default.given
import gears.async.{Async, Future, Task, TaskSchedule}

import scala.concurrent.ExecutionContext
import scala.util.Random
import scala.util.{Failure, Success, Try}

import Future.zip

class TaskScheduleBehavior extends munit.FunSuite {
  given ExecutionContext = ExecutionContext.global

  case class TimeMeasure(start: Long = System.currentTimeMillis()):
    inline def assertTimeLessThan(millis: Long)(using loc: munit.Location) =
      val `end` = System.currentTimeMillis()
      assert(`end` - start < millis, s"Expected less than ${millis}ms passed, got ${`end` - start}ms")
    inline def assertTimeAtLeast(millis: Long)(using loc: munit.Location) =
      val `end` = System.currentTimeMillis()
      assert(`end` - start >= millis, s"Expected at least ${millis}ms passed, got ${`end` - start}ms")

  test("Every 100ms, 3 times total schedule") {
    val m = TimeMeasure()
    Async.fromSync:
      var i = 0
      val f = Task {
        i += 1
      }.schedule(TaskSchedule.Every(100, 3)).start()
      f.awaitResult
      assertEquals(i, 3)
      m.assertTimeAtLeast(200)
      m.assertTimeLessThan(300)
  }

  test("Exponential backoff(2) 50ms, 5 times total schedule") {
    val m = TimeMeasure()
    Async.fromSync:
      var i = 0
      val f = Task {
        i += 1
      }.schedule(TaskSchedule.ExponentialBackoff(50, 2, 5)).start()
      f.awaitResult
      assertEquals(i, 5)
      m.assertTimeAtLeast(50 + 100 + 200 + 400)
      m.assertTimeLessThan(50 + 100 + 200 + 400 + 800)
  }

  test("Fibonacci backoff 10ms, 6 times total schedule") {
    val m = TimeMeasure()
    Async.fromSync:
      var i = 0
      val f = Task {
        i += 1
      }.schedule(TaskSchedule.FibonacciBackoff(10, 6)).start()
      f.awaitResult
      assertEquals(i, 6)
      m.assertTimeAtLeast(0 + 10 + 10 + 20 + 30 + 50)
      m.assertTimeLessThan(0 + 10 + 10 + 20 + 30 + 50 + 80)
  }

  test("UntilSuccess 150ms") {
    val m = TimeMeasure()
    Async.fromSync:
      var i = 0
      val t = Task {
        if (i < 4) {
          i += 1
          Failure(AssertionError())
        } else Success(i)
      }
      val ret = t.schedule(TaskSchedule.RepeatUntilSuccess(150)).start().awaitResult
      assertEquals(ret.get.get, 4)
      m.assertTimeAtLeast(4 * 150)
      m.assertTimeLessThan(5 * 150)
  }

  test("UntilFailure 150ms") {
    val m = TimeMeasure()
    val ex = AssertionError()
    Async.fromSync:
      var i = 0
      val t = Task {
        if (i < 4) {
          i += 1
          Success(i)
        } else Failure(ex)
      }
      val ret = t.schedule(TaskSchedule.RepeatUntilFailure(150)).start().awaitResult
      assertEquals(ret.get, Failure(ex))
      m.assertTimeAtLeast(4 * 150)
      m.assertTimeLessThan(5 * 150)
  }

}
