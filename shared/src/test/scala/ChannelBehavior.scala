import gears.async.{
  Async,
  BufferedChannel,
  ChannelClosedException,
  ChannelMultiplexer,
  Future,
  SyncChannel,
  Task,
  TaskSchedule,
  UnboundedChannel,
  alt,
  altC
}
import gears.async.default.given
import gears.async.AsyncOperations.*
import gears.async.BaseChannel
import Future.{*:, zip}

import java.util.concurrent.CancellationException
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import scala.util.Random
import scala.collection.mutable.{ArrayBuffer, Set}
import java.util.concurrent.atomic.AtomicInteger
import java.nio.ByteBuffer
import scala.collection.Stepper.UnboxingFloatStepper

class ChannelBehavior extends munit.FunSuite {

  given ExecutionContext = ExecutionContext.global

  test("sending is blocking in SyncChannel") {
    Async.blocking:
      val c = SyncChannel[Int]()
      var touched = false
      val f1 = Future:
        c.send(10)
        assertEquals(touched, true)

      sleep(500)
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

      sleep(500)
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

      sleep(500)
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
        sleep(1000)
        c.send(10)

      val f11 = Future:
        sleep(500)
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
        sleep(1000)
        c.send(10)

      val f11 = Future:
        sleep(500)
        touched = true

      val f2 = Future:
        c.read()
        assertEquals(touched, true)

      f1.result
      f11.result
      f2.result
  }

  test("values arrive in order") {
    for c <- getChannels do
      Async.blocking {
        val f1 = Future:
          for (i <- 0 to 1000)
            c.send(i)

        val f2 = Future:
          for (i <- 2000 to 3000)
            c.send(i)

        val f3 = Future:
          for (i <- 4000 to 5000)
            c.send(i)

        var i1 = 0
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

        assertEquals(i1, 1001)
        assertEquals(i2, 3001)
        assertEquals(i3, 5001)
      }
  }

  test("reading a closed channel returns Failure(ChannelClosedException)") {
    Async.blocking:
      val channels = getChannels
      channels.foreach(_.close())
      for c <- channels do
        c.read() match {
          case Failure(_: ChannelClosedException) => ()
          case _                                  => assert(false)
        }
  }

  test("writing to a closed channel throws ChannelClosedException") {
    Async.blocking:
      val channels = getChannels
      channels.foreach(_.close())
      for c <- channels do
        var thrown = false
        try {
          c.send(1)
        } catch {
          case _: ChannelClosedException => thrown = true
        }
        assertEquals(thrown, true)
  }

  test("send a lot of values via a channel and check their sum") {
    for (c <- getChannels) {
      var sum = 0L
      Async.blocking:
        val f1 = Future:
          for (i <- 1 to 10000)
            c.send(i)

        val f2 = Future:
          for (i <- 1 to 10000)
            sum += c.read().get

        f2.result
        assertEquals(sum, 50005000L)
    }
  }

  test("multiple writers, multiple readers") {
    for (c <- getChannels) {
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
          sleep(2000)
          while (gotCount.get() <= 30000 - 2) {
            c.read()
            gotCount.incrementAndGet()
            sleep(1)
          }

        val f22 = Future:
          sleep(1000)
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

  test("race reads") {
    Async.blocking:
      val channels = (0 until 1000).map(_ => SyncChannel[Int]()).toArray
      val sends = channels.toIterator.zipWithIndex.foreach { case (ch, idx) =>
        Future { ch.send(idx) }
      }
      val race = Async.race(
        (0 until 100).map(i =>
          Async.race((10 * i until 10 * i + 10).map(idx => channels(idx).canRead.map(_.mustRead))*)
        )*
      )
      var sum = 0
      for i <- 0 until 1000 do sum += Async.await(race)
      assertEquals(sum, (0 until 1000).sum)
  }

  test("unbounded channels with sync sending") {
    val ch = UnboundedChannel[Int]()
    for i <- 0 to 10 do ch.sendImmediately(i)
    Async.blocking:
      for i <- 0 to 10 do assertEquals(ch.read().get, i)
    ch.close()
    try {
      ch.sendImmediately(0)
      assert(false)
    } catch {
      case _: ChannelClosedException => ()
    }
  }

  test("race sends") {
    Async.blocking:
      val ch = SyncChannel[Int]()
      var timesSent = 0
      val race = Async.race(
        (for i <- 0 until 1000 yield ch.canSend(i))*
      )
      Future {
        while Async.await(race) != ch.Closed do {
          timesSent += 1
        }
      }
      for i <- 0 until 10 do {
        ch.read().get
      }
      sleep(100)
      assertEquals(timesSent, 10)
  }

  test("race syntax") {
    Async.blocking:
      val a = SyncChannel[Int]()
      val b = SyncChannel[Int]()

      Future { a.send(0) }
      Future { assertEquals(b.read().get, 10) }
      var valuesSent = 0
      for i <- 1 to 2 do
        Async.race(a.canRead, b.canSend(10)).await match
          case a.Read(v) => assertEquals(v, 0)
          case b.Sent =>
            valuesSent += 1
            assertEquals(valuesSent, 1)
          case r => assert(false, s"Should not happen, got $r")

      a.close()
      b.close()
      Async.race(a.canRead, b.canRead).await match
        case a.Closed | b.Closed => ()
        case r                   => assert(false, s"Should not happen, got $r")
  }

  test("ChannelMultiplexer multiplexes - all subscribers read the same stream".ignore) {
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

      def mkFuture = Future:
        val cr = SyncChannel[Try[Int]]()
        m.addSubscriber(cr)
        start.countDown()
        val l = ArrayBuffer[Int]()
        l += cr.read().get.get
        l += cr.read().get.get
        l += cr.read().get.get
        l += cr.read().get.get
        assertEquals(l, ArrayBuffer[Int](1, 2, 3, 4))

      val (f2, f3) = (mkFuture, mkFuture)

      f2.result
      f3.result
  }

  test("ChannelMultiplexer multiple readers and writers".ignore) {
    Async.blocking:
      val m = ChannelMultiplexer[Int]()

      val f11 = Future:
        val cc = SyncChannel[Int]()
        m.addPublisher(cc)
        sleep(200)
        for (i <- 0 to 3)
          cc.send(i)
        m.removePublisher(cc)

      val f12 = Future:
        val cc = SyncChannel[Int]()
        m.addPublisher(cc)
        sleep(200)
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
        sleep(200)
        val l = ArrayBuffer[Int]()
        sleep(1000)
        for (i <- 1 to 12) {
          l += cr.read().get.get
        }

      val f22 = Future:
        val cr = SyncChannel[Try[Int]]()
        m.addSubscriber(cr)
        sleep(1500)
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

  def getChannels = List(SyncChannel[Int](), BufferedChannel[Int](1024), UnboundedChannel[Int]())
}
