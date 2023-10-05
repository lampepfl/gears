import gears.async.{Async, Future, Task, TaskSchedule, alt}
import Future.{*:, zip}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import scala.util.Random

class TaskScheduleBehavior extends munit.FunSuite {
  given ExecutionContext = ExecutionContext.global

  test("Every 100ms, 3 times total schedule") {
    val start = System.currentTimeMillis()
    Async.blocking:
      var i = 0
      val f = Task {
        i += 1
      }.schedule(TaskSchedule.Every(100, 3)).run
      f.result
      assertEquals(i, 3)
    val end = System.currentTimeMillis()
    assert(end - start >= 200)
    assert(end - start < 300)
  }

  test("Exponential backoff(2) 50ms, 5 times total schedule") {
    val start = System.currentTimeMillis()
    Async.blocking:
      var i = 0
      val f = Task {
        i += 1
      }.schedule(TaskSchedule.ExponentialBackoff(50, 2, 5)).run
      f.result
      assertEquals(i, 5)
    val end = System.currentTimeMillis()
    assert(end - start >= 50+100+200+400)
    assert(end - start < 50+100+200+400+800)
  }

  test("Fibonacci backoff 10ms, 6 times total schedule") {
    val start = System.currentTimeMillis()
    Async.blocking:
      var i = 0
      val f = Task {
        i += 1
      }.schedule(TaskSchedule.FibonacciBackoff(10, 6)).run
      f.result
      assertEquals(i, 6)
    val end = System.currentTimeMillis()
    assert(end - start >= 0 + 10 + 10 + 20 + 30 + 50)
    assert(end - start < 0 + 10 + 10 + 20 + 30 + 50 + 80)
  }

  test("UntilSuccess 150ms") {
    val start = System.currentTimeMillis()
    Async.blocking:
      var i = 0
      val t = Task {
        if (i < 4) {
          i += 1
          Failure(AssertionError())
        } else Success(i)
      }
      val ret = t.schedule(TaskSchedule.RepeatUntilSuccess(150)).run.result
      assertEquals(ret.get.get, 4)
    val end = System.currentTimeMillis()
    assert(end - start >= 4 * 150)
    assert(end - start < 5 * 150)
  }

  test("UntilFailure 150ms") {
    val start = System.currentTimeMillis()
    val ex = AssertionError()
    Async.blocking:
      var i = 0
      val t = Task {
        if (i < 4) {
          i += 1
          Success(i)
        } else Failure(ex)
      }
      val ret = t.schedule(TaskSchedule.RepeatUntilFailure(150)).run.result
      assertEquals(ret.get, Failure(ex))
    val end = System.currentTimeMillis()
    assert(end - start >= 4 * 150)
    assert(end - start < 5 * 150)
  }

}