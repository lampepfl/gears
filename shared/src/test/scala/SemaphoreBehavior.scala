import gears.async.Async
import gears.async.AsyncOperations.sleep
import gears.async.Future
import gears.async.Semaphore
import gears.async.default.given
import gears.async.withTimeoutOption

import java.util.concurrent.atomic.AtomicInteger

import concurrent.duration.DurationInt

class SemaphoreBehavior extends munit.FunSuite {

  test("single threaded semaphore") {
    Async.blocking:
      val sem = Semaphore(2)
      sem.acquire().release()
      sem.acquire()
      sem.acquire()
  }

  test("single threaded semaphore blocked") {
    Async.blocking:
      val sem = Semaphore(2)
      val guard = sem.acquire()
      sem.acquire()
      val res = withTimeoutOption(100.millis)(sem.acquire())
      assertEquals(res, None)
      guard.release()
      sem.acquire()
  }

  test("binary semaphore") {
    Async.blocking:
      val sem = Semaphore(1)
      var count = 0

      Seq
        .fill(100)(Future {
          for i <- 0 until 1_000 do
            scala.util.Using(sem.acquire()): _ =>
              count += 1
        })
        .awaitAll
      assertEquals(count, 100_000)
  }

  test("no release high-numbered semaphore") {
    val futs = Async.blocking:
      val sem = Semaphore(100)
      val count = AtomicInteger()

      val futs = Seq.fill(1_000)(Future {
        sem.acquire()
        count.incrementAndGet()
      })

      while count.get() < 100 do Thread.`yield`()
      sleep(100)
      assertEquals(count.get(), 100)
      futs
    val (succ, fail) = futs.partition(f => f.poll().get.isSuccess)
    assertEquals(succ.size, 100)
    assertEquals(fail.size, 900)
  }

}
