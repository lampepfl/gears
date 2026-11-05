package gears.async.io
package testkit

import gears.async.Async
import gears.async.AsyncOperations.sleep
import gears.async.Future
import gears.async.withTimeout

import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

/** Timers, deadlines and program execution on every backend. No optional capability or handle fixture is required. */
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

  test("a timer with a time limit no shorter than its time completes") {
    val outcome = running { io ?=>
      val completed =
        for limit <- List(20, 21, 1000)
        yield
          try {
            io.perform(io.backend.ops.timer(20), FiniteDuration(limit.toLong, "ms"))
            true
          } catch case _: TimeoutException => false
      completed.mkString(" ")
    }
    assertEquals(outcome, "true true true")
  }

  test("gears' sleep and withTimeout run on the backend's timers: the program's Io is its AsyncOperations") {
    val outcome = running { io ?=>
      val started = System.nanoTime()
      sleep(20)
      val slept = System.nanoTime() - started
      val timedOut =
        try {
          withTimeout(20.millis)(io.perform(io.backend.ops.timer(60_000)))
          false
        } catch case _: TimeoutException => true
      s"${slept >= 20_000_000L} $timedOut"
    }
    assertEquals(outcome, "true true")
  }

  test("a cancelled timer stops waiting, and may be performed again") {
    val outcome = running { io ?=>
      val timer = io.backend.ops.timer(60_000)
      val sleeping = Future(io.perform(timer))
      io.perform(io.backend.ops.timer(20)) // so that the long timer is waiting
      val started = System.nanoTime()
      sleeping.cancel()
      val cancelled = sleeping.awaitResult.failed.toOption.exists(_.isInstanceOf[CancellationException])
      val waited = System.nanoTime() - started
      intercept[TimeoutException](io.perform(timer, 20.millis)) // the same op can wait again after cancellation
      s"$cancelled ${waited < 1_000_000_000L}"
    }
    assertEquals(outcome, "true true")
  }

  test("a future waiting on a deadline timer is released when another moves its deadline earlier, with no cancel") {
    val outcome = running { io ?=>
      val timer = io.backend.ops.deadlineTimer(System.nanoTime() + 60_000_000_000L)
      val waiting = Future(io.perform(timer))
      io.perform(io.backend.ops.timer(20)) // so that the timer is waiting
      val started = System.nanoTime()
      timer.moveTo(System.nanoTime() + 1_000_000L)
      val completed = waiting.awaitResult.isSuccess
      val waited = System.nanoTime() - started
      timer.moveTo(System.nanoTime() + 2_000_000L) // and again, later than now
      io.perform(timer)
      s"$completed ${waited < 10_000_000_000L}"
    }
    assertEquals(outcome, "true true")
  }

  test("an op a future performs is cancelled when the group the future was made in ends, which waits for it") {
    val outcome = running { io ?=>
      var waiting: Future[Unit] | Null = null
      val started = System.nanoTime()
      Async.group {
        waiting = Future(io.perform(io.backend.ops.timer(60_000)))
      } // ends with the timer pending: the future is cancelled, and with it the op, before the group returns
      val waited = System.nanoTime() - started
      val cancelled = waiting.nn.awaitResult.failed.toOption.exists(_.isInstanceOf[CancellationException])
      s"$cancelled ${waited < 1_000_000_000L}"
    }
    assertEquals(outcome, "true true")
  }

  test("a failure of the program is rethrown") {
    intercept[IllegalStateException](running(throw new IllegalStateException("boom")))
  }
}
