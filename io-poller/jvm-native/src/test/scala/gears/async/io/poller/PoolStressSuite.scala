package gears.async.io
package poller

import gears.async.Async
import gears.async.Future

import java.util.concurrent.CancellationException
import scala.concurrent.duration.*

/** Timers that complete as soon as they are scheduled, so that completion races the performer's wait for it, and
  * submissions race each worker going to sleep.
  */
class PoolStressSuite extends munit.FunSuite {
  override def munitTimeout: Duration = 60.seconds

  test("a run stops its workers before it returns") {
    given PollerPlatform.backend.type = PollerPlatform.backend
    val worker = Io.run { io ?=>
      io.perform(io.ops.timer(1))
      Thread.currentThread()
    }
    assert(!worker.isAlive, "a worker outlived its run")
  }

  test("a run stops its workers when the program fails") {
    given PollerPlatform.backend.type = PollerPlatform.backend
    var worker: Thread | Null = null
    intercept[IllegalStateException](Io.run { io ?=>
      worker = Thread.currentThread()
      throw new IllegalStateException("boom")
    })
    assert(!worker.nn.isAlive, "a worker outlived its run")
  }

  test("a run ending with many timers pending cancels them, and shuts down without waiting for them") {
    given PollerPlatform.backend.type = PollerPlatform.backend
    val workers = java.util.concurrent.ConcurrentHashMap.newKeySet[Thread]()
    val scheduledRan = java.util.concurrent.atomic.AtomicBoolean(false)
    val started = System.nanoTime()
    val waiters = Io.run { io ?=>
      summon[Async].scheduler.schedule(60.seconds, () => scheduledRan.set(true)): Unit
      val waiters = for _ <- 1 to 1000 yield Future {
        workers.add(Thread.currentThread())
        io.perform(io.ops.timer(60_000))
      }
      io.perform(io.ops.timer(50)) // so that they are waiting
      waiters
    }
    val elapsed = System.nanoTime() - started
    // Half the timers' minute, not a few seconds: each future cancelled makes several exceptions, and on Scala Native
    // one costs its stack trace, 50 to 160 us on a quiet machine and some 8 ms a future on a CI machine running the
    // other suites, where the thousand took 7.8 s.
    assert(elapsed < 30_000_000_000L, s"it took ${elapsed / 1_000_000}ms, as if it waited for the timers")
    assert(waiters.forall(_.poll().exists(_.failed.toOption.exists(_.isInstanceOf[CancellationException]))))
    assert(workers.size > 0)
    workers.forEach(worker => assert(!worker.isAlive, "a worker outlived its run"))
    assert(!scheduledRan.get())
  }

  test("a scheduler that has shut down refuses work") {
    val pool = PollerPlatform.scheduler(1, shareable.Starvation.printWarning)
    pool.shutdown()
    pool.shutdown() // nothing left to do
    intercept[IllegalStateException](pool.execute(() => ()))
    intercept[IllegalStateException](pool.schedule(1.milli, () => ()))
  }

  test("timers shorter than a millisecond complete, the worker sleeping for less than one") {
    given PollerPlatform.backend.type = PollerPlatform.backend
    Io.run { io ?=>
      for round <- 1 to 300 do {
        val futures = for _ <- 1 to 4 yield Future(io.perform(io.ops.timer(300.micros)))
        futures.foreach(_.await)
      }
    }
  }
}
