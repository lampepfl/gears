import concurrent.{Async, Future}

import scala.concurrent.ExecutionContext
import scala.util.Success

class StabilityTests extends munit.FunSuite {
  given ExecutionContext = ExecutionContext.global

  test("Constant returns forever -- looks for race conditions") {
    Async.blocking:
      for (i <- 0 to 1000*1000*1000)
        if (0 == (i % 1000)) println(i)
        assertEquals(Future{10}.value, 10)
  }

  test("virtual thread memory usage") {
    Async.blocking:
      for (i <- 0 to 1000*1000)
        Future {
          Thread.sleep(60 * 60 * 1000)
        }
      Thread.sleep(60 * 60 * 1000)
  }
}
