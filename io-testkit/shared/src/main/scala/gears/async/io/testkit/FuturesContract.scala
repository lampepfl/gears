package gears.async.io
package testkit

import gears.async.Async
import gears.async.AsyncOperations.sleep
import gears.async.Future
import gears.async.withTimeout

import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

/** Programs using only the timers and execution semantics of Backend. */
trait FuturesContract extends ContractSuite {

  test("a program waits on a timer, and run returns what the program returns") {
    val waited = running { io ?=>
      val started = System.nanoTime()
      io.perform(io.backend.ops.timer(20))
      System.nanoTime() - started
    }
    assert(waited >= 20_000_000L, s"waited only ${waited / 1000} µs")
  }

  test("a timer with a shorter time limit throws TimeoutException from perform") {
    val outcome = running { io ?=>
      val started = System.nanoTime()
      val timedOut =
        try {
          io.perform(io.backend.ops.timer(60_000), FiniteDuration(20, "ms"))
          false
        } catch case _: TimeoutException => true
      val waited = System.nanoTime() - started
      s"$timedOut ${waited >= 20_000_000L && waited < 30_000_000_000L}"
    }
    assertEquals(outcome, "true true")
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
      io.perform(io.backend.ops.timer(1)) // the op again, now for a moment
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
