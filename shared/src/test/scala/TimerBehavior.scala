import language.experimental.captureChecking

import gears.async.AsyncOperations._
import gears.async._

import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.Try
import scala.util.{Failure, Success}

class TimerBehavior extends munit.FunSuite {
  import gears.async.default.given

  test("sleeping does sleep") {
    Async.blocking:
      val now1 = System.currentTimeMillis()
      sleep(200)
      val now2 = System.currentTimeMillis()
      assert(now2 - now1 > 150, now2 - now1)
  }

  test("timer does sleep") {
    Async.blocking:
      val timer = Timer(1.second)
      Future { timer.run() }
      assert(timer.src.awaitResult == timer.TimerEvent.Tick)
  }

  def `cancel future after timeout`[T](d: Duration, f: Future[T]^)(using Async, AsyncOperations): Try[T] =
    Async.group:
      f.link()
      val t = Future { sleep(d.toMillis) }
      Try:
        Async.select(
          t handle: _ =>
            throw TimeoutException(),
          f handle: v =>
            v.get
        )

  test("racing with a sleeping future") {
    var touched = false
    Async.blocking:
      val t = `cancel future after timeout`(
        250.millis,
        Future:
          sleep(1000)
          touched = true
      )
      assert(t.isFailure)
      assert(!touched)
      sleep(2000)
      assert(!touched)
  }
}
