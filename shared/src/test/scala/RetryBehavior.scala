import gears.async.{Async, Future, Task, TaskSchedule, Retry, Delay}
import scala.concurrent.duration.*
import gears.async.default.given
import Future.{*:, zip}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import scala.util.Random

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

}
