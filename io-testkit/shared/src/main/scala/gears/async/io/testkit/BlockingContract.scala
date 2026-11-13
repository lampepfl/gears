package gears.async.io
package testkit

import gears.async.AsyncOperations.sleep
import gears.async.Future

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*

/** Blocking operations, requiring only the Blocking capability. */
trait BlockingContract extends ContractSuite {
  val backend: Backend { val ops: Blocking }

  test("a blocking op past its time limit is interrupted, and fails once it has returned") {
    running { io ?=>
      val returned = new AtomicBoolean
      val op = io.backend.ops.blocking { () =>
        try Thread.sleep(10_000)
        catch case _: InterruptedException => ()
        returned.set(true)
      }
      intercept[TimeoutException](io.perform(op, 20.millis))
      assert(returned.get(), "perform returned before the body did")
    }
  }

  test("an op that is not Repeatable cannot be performed twice") {
    running { io ?=>
      val op = io.backend.ops.blocking(() => ())
      io.perform(op)
      intercept[IllegalStateException](io.perform(op))
    }
  }

  test("a blocking op writes its value into its slot") {
    running { io ?=>
      val into = Slot[String]()
      io.perform(io.backend.ops.blocking(() => "value", into))
      assertEquals(into.clear(), "value")
    }
  }

  test("a blocking op's failure fails the op") {
    running { io ?=>
      val failure = attempt(io, io.backend.ops.blocking(() => throw new IllegalArgumentException("boom")))
      assert(failure.isInstanceOf[IllegalArgumentException], String.valueOf(failure))
    }
  }

  test("cancelling a running blocking op interrupts it, and perform returns once it has") {
    running { io ?=>
      val started = new CountDownLatch(1)
      val returned = new AtomicBoolean
      val op = io.backend.ops.blocking { () =>
        started.countDown()
        try Thread.sleep(10_000)
        catch case _: InterruptedException => ()
        returned.set(true)
      }
      val performing = Future(io.perform(op))
      started.await()
      performing.cancel()
      val failure = failureOf(performing)
      assert(failure.isInstanceOf[CancellationException], String.valueOf(failure))
      assert(returned.get(), "perform returned before the body did")
    }
  }

  test("a cancel that comes once a blocking op has written its value is too late: the op completes with it") {
    running { io ?=>
      val returning = new CountDownLatch(1)
      val into = Slot[String]()
      val op = io.backend.ops.blocking(
        () => {
          returning.countDown()
          "value"
        },
        into
      )
      val performing = Future(io.perform(op))
      returning.await()
      Thread.sleep(50) // for the value to be written once the body returns
      performing.cancel()
      assertEquals(failureOf(performing), null)
      assertEquals(into.clear(), "value")
    }
  }

  test("a blocking op with a time limit that finishes in time completes, and nothing of its limit fires later") {
    running { io ?=>
      val ran = new java.util.concurrent.atomic.AtomicInteger
      for _ <- 1 to 3 do io.perform(io.backend.ops.blocking(() => ran.incrementAndGet(): Unit), 50.millis)
      io.perform(io.backend.ops.timer(80)) // past every limit
      assertEquals(ran.get(), 3)
    }
  }

  test("an op performed with a slot not yet cleared fails with IllegalArgumentException, doing nothing") {
    running { io ?=>
      val into = Slot[String]()
      io.perform(io.backend.ops.blocking(() => "first", into))
      val ran = new AtomicBoolean
      val failure = attempt(
        io,
        io.backend.ops.blocking(
          () => {
            ran.set(true)
            "second"
          },
          into
        )
      )
      assert(failure.isInstanceOf[IllegalArgumentException], String.valueOf(failure))
      assert(!ran.get(), "the body ran")
      assertEquals(into.clear(), "first")

    }
  }

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
}
