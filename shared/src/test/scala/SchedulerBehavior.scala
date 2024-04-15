import gears.async.AsyncOperations.*
import gears.async.Future.Promise
import gears.async.default.given
import gears.async.{Async, Future, Listener}

import scala.util.Success

import concurrent.duration.DurationInt

class SchedulerBehavior extends munit.FunSuite {
  test("schedule cancellation works") {
    Async.blocking:
      var bodyRan = false
      val cancellable = Async.current.scheduler.schedule(1.seconds, () => bodyRan = true)

      // cancel immediately
      cancellable.cancel()

      sleep(1000)
      assert(!bodyRan)
  }

  test("schedule cancellation doesn't abort inner code") {
    Async.blocking:
      var bodyRan = false
      val fut = Promise[Unit]()
      val cancellable = Async.current.scheduler.schedule(
        50.milliseconds,
        () =>
          fut.complete(Success(()))
          Async.blocking:
            sleep(500)
            bodyRan = true
      )

      // cancel after body started running
      fut.await
      cancellable.cancel()

      sleep(1000)

      assert(bodyRan)
  }

  test("execute works") {
    Async.blocking:
      val fut = Promise[Int]()

      Async.current.scheduler.execute: () =>
        fut.complete(Success(10))

      assertEquals(fut.await, 10)
  }
}
