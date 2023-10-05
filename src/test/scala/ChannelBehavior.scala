import gears.async.{Async, BufferedChannel, ChannelClosedException, ChannelMultiplexer, Future, SyncChannel, Task, TaskSchedule, alt, altC}
import Future.{*:, zip}

import java.util.concurrent.CancellationException
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import scala.util.Random
import scala.collection.mutable.{ArrayBuffer, Set}
import java.util.concurrent.atomic.AtomicInteger

class ChannelBehavior extends munit.FunSuite {
  given ExecutionContext = ExecutionContext.global

  test("sending is blocking in SyncChannel") {
    Async.blocking:
      val c = SyncChannel[Int]()
      var touched = false
      val f1 = Future:
        c.send(10)
        assertEquals(touched, true)

      Async.current.sleep(500)
      touched = true
      val f2 = Future:
        c.read()

      f1.result
      f2.result
  }

  test("sending is nonblocking in empty BufferedChannel") {
    Async.blocking:
      val c = BufferedChannel[Int](1)
      var touched = false
      val f1 = Future:
        c.send(10)
        assertEquals(touched, false)

      Async.current.sleep(500)
      touched = true
      val f2 = Future:
        c.read()

      f1.result
      f2.result
  }

  test("sending is blocking in full BufferedChannel") {
    Async.blocking:
      val c = BufferedChannel[Int](3)
      var touched = false
      val f1 = Future:
        c.send(10)
        assertEquals(touched, false)
        c.send(10)
        assertEquals(touched, false)
        c.send(10)
        assertEquals(touched, true)

      Async.current.sleep(500)
      touched = true
      val f2 = Future:
        c.read()

      f1.result
      f2.result
  }

  test("read blocks until value is available in SyncChannel") {
    Async.blocking:
      val c = SyncChannel[Int]()
      var touched = false
      val f1 = Future:
        Async.current.sleep(1000)
        c.send(10)

      val f11 = Future:
        Async.current.sleep(500)
        touched = true

      val f2 = Future:
        c.read()
        assertEquals(touched, true)

      f1.result
      f11.result
      f2.result
  }

  test("read blocks until value is available in BufferedChannel") {
    Async.blocking:
      val c = BufferedChannel[Int](3)
      var touched = false
      val f1 = Future:
        Async.current.sleep(1000)
        c.send(10)

      val f11 = Future:
        Async.current.sleep(500)
        touched = true

      val f2 = Future:
        c.read()
        assertEquals(touched, true)

      f1.result
      f11.result
      f2.result
  }

  test("values arrive in order") {
    Async.blocking:
      val c1 = SyncChannel[Int]()
      val c2 = SyncChannel[Int]()
      for (c <- List(c1, c2)) {
        val f1 = Future:
          for (i <- 0 to 1000)
            c.send(i)

        val f2 = Future:
          for (i <- 2000 to 3000)
            c.send(i)

        val f3 = Future:
          for (i <- 4000 to 5000)
            c.send(i)


        val f4 = Future:
          var i1 = 1
          var i2 = 2000
          var i3 = 4000

          for (i <- 1 to (3 * 1001)) {
            val got = c.read().get
            if (i1 == got) {
              i1 += 1
            } else if (i2 == got) {
              i2 += 1
            } else if (i3 == got) {
              i3 += 1
            } else assert(false)
          }

          assertEquals(i1, 1000)
          assertEquals(i2, 3000)
          assertEquals(i3, 5000)
      }
  }

  test("reading a closed channel returns Failure(ChannelClosedException)") {
    Async.blocking:
      val c1 = SyncChannel[Int]()
      val c2 = SyncChannel[Int]()
      c1.close()
      c2.close()
      c1.read() match {
        case Failure(_: ChannelClosedException) => ()
        case _ => assert(false)
      }
      c2.read() match {
        case Failure(_: ChannelClosedException) => ()
        case _ => assert(false)
      }
  }

  test("writing to a closed channel throws ChannelClosedException") {
    Async.blocking:
      val c1 = SyncChannel[Int]()
      val c2 = SyncChannel[Int]()
      c1.close()
      c2.close()
      var thrown1 = false
      var thrown2 = true
      try {
        c1.send(1)
      } catch {
        case _: ChannelClosedException => thrown1 = true
      }
      try {
        c2.send(1)
      } catch {
        case _: ChannelClosedException => thrown2 = true
      }
      assertEquals(thrown1, true)
      assertEquals(thrown2, true)
  }

  test("send a lot of values via a channel and check their sum") {
    val c1 = SyncChannel[Int]()
    val c2 = BufferedChannel[Int](1024)
    for (c <- List(c1, c2)) {
      var sum = 0
      Async.blocking:
        val f1 = Future:
          for (i <- 1 to 10000)
            c.send(i)

        val f2 = Future:
          for (i <- 1 to 10000)
            sum += c.read().get

        f2.result
        assertEquals(sum, 50005000)
    }
  }

  test("multiple writers, multiple readers") {
    val c1 = BufferedChannel[Int](713)
    val c2 = SyncChannel[Int]()
    for (c <- List(c1, c2)) {
      Async.blocking:
        val f11 = Future:
          for (i <- 1 to 10000)
            c.send(i)

        val f12 = Future:
          for (i <- 1 to 10000)
            c.send(i)

        val f13 = Future:
          for (i <- 1 to 10000)
            c.send(i)

        val gotCount = AtomicInteger(0)

        val f21 = Future:
          Async.current.sleep(2000)
          while (gotCount.get() <= 30000 - 2) {
            c.read()
            gotCount.incrementAndGet()
            Async.current.sleep(1)
          }

        val f22 = Future:
          Async.current.sleep(1000)
          while (gotCount.get() <= 30000 - 2) {
            c.read()
            gotCount.incrementAndGet()
          }

        f21.result
        f22.result
        while (gotCount.get() < 30000) {
          c.read()
          gotCount.incrementAndGet()
        }
        f11.result
        f12.result
        f13.result
    }
  }

  test("ChannelMultiplexer multiplexes - all subscribers read the same stream") {
    Async.blocking:
      val m = ChannelMultiplexer[Int]()
      val c = SyncChannel[Int]()
      m.addPublisher(c)

      val start = java.util.concurrent.CountDownLatch(2)

      val f1 = Future:
        start.await()
        c.send(1)
        c.send(2)
        c.send(3)
        c.send(4)

      val f2 = Future:
        val cr = SyncChannel[Try[Int]]()
        m.addSubscriber(cr)
        start.countDown()
        val l = ArrayBuffer[Int]()
        l += cr.read().get.get
        l += cr.read().get.get
        l += cr.read().get.get
        l += cr.read().get.get
        assertEquals(l, ArrayBuffer[Int](1,2,3,4))

      val f3 = Future:
        val cr = BufferedChannel[Try[Int]]()
        m.addSubscriber(cr)
        start.countDown()
        val l = ArrayBuffer[Int]()
        l += cr.read().get.get
        l += cr.read().get.get
        l += cr.read().get.get
        l += cr.read().get.get
        assertEquals(l, ArrayBuffer[Int](1, 2, 3, 4))

      f2.result
      f3.result
  }

  test("ChannelMultiplexer multiple readers and writers".ignore) {
    Async.blocking:
      val m = ChannelMultiplexer[Int]()

      val f11 = Future:
        val cc = SyncChannel[Int]()
        m.addPublisher(cc)
        Async.current.sleep(200)
        for (i <- 0 to 3)
          cc.send(i)
        m.removePublisher(cc)

      val f12 = Future:
        val cc = SyncChannel[Int]()
        m.addPublisher(cc)
        Async.current.sleep(200)
        for (i <- 10 to 13)
          cc.send(i)
        m.removePublisher(cc)

      val f13 = Future:
        val cc = SyncChannel[Int]()
        m.addPublisher(cc)
        for (i <- 20 to 23)
          cc.send(i)
        m.removePublisher(cc)

      val f21 = Future:
        val cr = SyncChannel[Try[Int]]()
        m.addSubscriber(cr)
        Async.current.sleep(200)
        val l = ArrayBuffer[Int]()
        Async.current.sleep(1000)
        for (i <- 1 to 12) {
          l += cr.read().get.get
        }

      val f22 = Future:
        val cr = SyncChannel[Try[Int]]()
        m.addSubscriber(cr)
        Async.current.sleep(1500)
        val l = ArrayBuffer[Int]()
        for (i <- 1 to 12) {
          l += cr.read().get.get
        }

      f21.result
      f22.result
      f11.result
      f12.result
      f13.result
  }
}