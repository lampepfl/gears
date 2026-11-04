package gears.async.io
package testkit

import gears.async.AsyncOperations.sleep
import gears.async.Future

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*

/** Cancellation and deadlines in programs using Blocking. */
trait BlockingFuturesContract extends ContractSuite {
  val backend: Backend & Blocking

  test("cancelling a blocking op waits for its acknowledgement before returning borrowed resources") {
    running { io ?=>
      val entered = new java.util.concurrent.atomic.AtomicBoolean
      val stopped = new java.util.concurrent.atomic.AtomicBoolean
      val release = new java.util.concurrent.CountDownLatch(1)
      val waiting = Future {
        io.perform(io.backend.ops.blocking { () =>
          entered.set(true)
          var released = false
          while !released do
            try {
              release.await()
              released = true
            } catch case _: InterruptedException => () // cleanup cannot finish until the test releases it
          stopped.set(true)
        })
      }
      try {
        while !entered.get() do sleep(1)
        waiting.cancel()
        sleep(10)
        assert(waiting.poll().isEmpty, "cancellation returned before the blocker released its resources")
      } finally release.countDown()
      val failure = waiting.awaitResult.failed.get
      assert(failure.isInstanceOf[CancellationException])
      assert(stopped.get())
    }
  }

  test("a blocking op past its time limit is interrupted, and perform throws TimeoutException") {
    val outcome = running { io ?=>
      val returned = new java.util.concurrent.atomic.AtomicBoolean
      val op = io.backend.ops.blocking { () =>
        try Thread.sleep(10_000)
        catch case _: InterruptedException => ()
        returned.set(true)
      }
      val started = System.nanoTime()
      val timedOut =
        try {
          io.perform(op, FiniteDuration(20, "ms"))
          false
        } catch case _: TimeoutException => true
      val waited = System.nanoTime() - started
      s"$timedOut ${returned.get()} ${waited >= 20_000_000L && waited < 5_000_000_000L}"
    }
    assertEquals(outcome, "true true true")
  }
}
