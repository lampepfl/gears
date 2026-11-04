package gears.async.io
package testkit

import gears.async.Future

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

/** Timers and deadlines available on every backend. No optional capability or handle fixture is required. */
trait OpsContract extends ContractSuite {

  test("a timer completes after its delay") {
    running { io ?=>
      val started = System.nanoTime()
      io.perform(io.backend.ops.timer(20))
      assert(System.nanoTime() - started >= 20_000_000L, "the timer fired early")
    }
  }

  test("timers performed together complete in deadline order") {
    val fired = running { io ?=>
      val fired = scala.collection.mutable.ArrayBuffer.empty[Int]
      val timers = for delay <- List(90, 30, 60) yield Future {
        io.perform(io.backend.ops.timer(delay))
        fired.synchronized(fired += delay): Unit
      }
      timers.foreach(_.await)
      fired.synchronized(fired.toList)
    }
    assertEquals(fired, List(30, 60, 90))
  }

  test("a timer of a duration finer than a millisecond completes no earlier than it") {
    running { io ?=>
      val started = System.nanoTime()
      io.perform(io.backend.ops.timer(Duration(1500, java.util.concurrent.TimeUnit.MICROSECONDS)))
      assert(System.nanoTime() - started >= 1_500_000L, "the timer fired early")
    }
  }

  test("a deadline timer moved later completes at the new deadline, with no cancel, its first deadline passing") {
    running { io ?=>
      val start = System.nanoTime()
      val timer = io.backend.ops.deadlineTimer(start + 20_000_000L)
      val waiting = Future(io.perform(timer))
      timer.moveTo(start + 80_000_000L)
      timer.moveTo(start + 120_000_000L) // moved again, as QUIC's timers are for almost every packet
      waiting.await
      assert(System.nanoTime() - start >= 120_000_000L, "it fired at a deadline it had left")
      assertEquals(timer.deadline, start + 120_000_000L)
    }
  }

  test("a deadline timer completes at once for a deadline passed, and performed again at its deadline as it stands") {
    running { io ?=>
      val timer = io.backend.ops.deadlineTimer(System.nanoTime() - 1_000_000L)
      io.perform(timer)
      val start = System.nanoTime()
      timer.moveTo(start + 30_000_000L) // not pending: the next performance starts from it
      io.perform(timer)
      assert(System.nanoTime() - start >= 30_000_000L)
    }
  }

  test("a deadline timer's time limit fails it if the limit comes first, the deadline moved past it or not") {
    running { io ?=>
      val timer = io.backend.ops.deadlineTimer(System.nanoTime() + 10_000_000L)
      val waiting = Future(io.perform(timer, 40.millis))
      timer.moveTo(System.nanoTime() + 60_000_000_000L)
      val failure = failureOf(waiting)
      assert(failure.isInstanceOf[TimeoutException], String.valueOf(failure))
    }
  }

  test("a timer with a shorter time limit fails with TimeoutException when the limit is up") {
    running { io ?=>
      val started = System.nanoTime()
      intercept[TimeoutException](io.perform(io.backend.ops.timer(60_000), 20.millis))
      assert(System.nanoTime() - started < 30_000_000_000L)
      io.perform(io.backend.ops.timer(1), 5.seconds) // a timer again, within its limit
    }
  }
}
