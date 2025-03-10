import gears.async.Async
import gears.async.AsyncOperations
import gears.async.AsyncOperations.*
import gears.async.AsyncSupport
import gears.async.Future
import gears.async.Future.MutableCollector
import gears.async.Task
import gears.async.Timer
import gears.async.default.given
import gears.async.uninterruptible

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

class StressTest extends munit.FunSuite:
  test("survives a stress test that hammers on creating futures") {
    val total = 200_000L
    Seq[Long](1, 2, 4, 16, 10000).foreach: parallelism =>
      val k = AtomicInteger(0)
      def compute(using Async) =
        k.incrementAndGet()
      Async.blocking:
        val collector = MutableCollector((1L to parallelism).map(_ => Future { compute })*)
        var sum = 0L
        for i <- parallelism + 1 to total do
          sum += collector.results.read().right.get.await
          collector += Future { compute }
        for i <- 1L to parallelism do sum += collector.results.read().right.get.await
        assertEquals(sum, total * (total + 1) / 2)
  }

  test("survives a stress test that hammers on suspending") {
    val total = 100_000L
    val parallelism = 5000L
    Async.blocking:
      val sleepy =
        val timer = Timer(1.second)
        Future { timer.run() }
        timer.src
      val k = AtomicInteger(0)
      def compute(using Async) =
        sleepy.awaitResult
        k.incrementAndGet()
      val collector = MutableCollector((1L to parallelism).map(_ => Future { compute })*)
      var sum = 0L
      for i <- parallelism + 1 to total do
        sum += collector.results.read().right.get.await
        collector += Future { compute }
      for i <- 1L to parallelism do sum += collector.results.read().right.get.await
      assertEquals(sum, total * (total + 1) / 2)
  }

  test("1 million concurrent tasks") {
    val count = 1_000_000
    def task(i: Int) = Task {
      AsyncOperations.sleep(100L)
      i * 2
    }
    Async.blocking {
      val start = System.currentTimeMillis()
      val res = 1.to(count).map(i => task(i).start()).awaitAll
      val end = System.currentTimeMillis()
      assert(end - start > 100)
      assert(res.size == count)
      assert(res.sum == (1 + count) * count)
    }
  }
