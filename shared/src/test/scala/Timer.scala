import gears.async._
import gears.async.AsyncOperations._
import scala.concurrent.duration._
import scala.util.{Success, Failure}
import java.util.concurrent.TimeoutException
import java.util.concurrent.CancellationException

class TimerTest extends munit.FunSuite {
  import gears.async.default.given

  test("TimerSleep1Second") {
    Async.blocking:
      println("start of 1 second")
      val timer = Timer(1.second)
      Future { timer.start() }
      assert(Async.await(timer.src) == timer.TimerEvent.Tick)
      println("end of 1 second")
  }

  def timeoutCancellableFuture[T](d: Duration, f: Future[T])(using Async, AsyncOperations): Future[T] =
    val t = Future { sleep(d.toMillis) }
    Future:
      val g = Async.await(Async.either(t, f))
      g match
        case Left(_) =>
          f.cancel()
          throw TimeoutException()
        case Right(v) =>
          t.cancel()
          v.get

  test("testTimeoutFuture") {
    var touched = false
    Async.blocking:
      val t = timeoutCancellableFuture(
        250.millis,
        Future:
          sleep(1000)
          touched = true
      )
      Async.await(t)
      assert(!touched)
      sleep(2000)
      assert(!touched)
  }
}
