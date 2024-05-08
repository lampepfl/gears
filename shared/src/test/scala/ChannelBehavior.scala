import language.experimental.captureChecking

import gears.async.AsyncOperations.*
import gears.async.default.given
import gears.async.{
  Async,
  BufferedChannel,
  ChannelClosedException,
  ChannelMultiplexer,
  Future,
  SyncChannel,
  Task,
  TaskSchedule,
  UnboundedChannel
}

import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.Stepper.UnboxingFloatStepper
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, Set}
import scala.concurrent.ExecutionContext
import scala.util.Random
import scala.util.{Failure, Success, Try}

import Future.zip

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

      f1.awaitResult
      f2.awaitResult
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

      f1.awaitResult
      f2.awaitResult
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

      f1.awaitResult
      f2.awaitResult
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

      f1.awaitResult
      f11.awaitResult
      f2.awaitResult
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

      f1.awaitResult
      f11.awaitResult
      f2.awaitResult
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
          val got = c.read().right.get
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
          case Left(_) => ()
          case _       => assert(false)
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
            sum += c.read().right.get

        f2.awaitResult
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

        f21.awaitResult
        f22.awaitResult
        while (gotCount.get() < 30000) {
          c.read()
          gotCount.incrementAndGet()
        }
        f11.awaitResult
        f12.awaitResult
        f13.awaitResult
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
          Async.race((10 * i until 10 * i + 10).map(idx => channels(idx).readSource.transformValuesWith(_.right.get))*)
        )*
      )
      var sum = 0
      for i <- 0 until 1000 do sum += race.awaitResult
      assertEquals(sum, (0 until 1000).sum)
  }

  test("unbounded channels with sync sending") {
    val ch = UnboundedChannel[Int]()
    for i <- 0 to 10 do ch.sendImmediately(i)
    Async.blocking:
      for i <- 0 to 10 do assertEquals(ch.read().right.get, i)
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
        (for i <- 0 until 1000 yield ch.sendSource(i))*
      )
      Future {
        while race.awaitResult.isRight do {
          timesSent += 1
        }
      }
      for i <- 0 until 10 do {
        ch.read().right.get
      }
      sleep(100)
      assertEquals(timesSent, 10)
  }

  test("race syntax") {
    Async.blocking:
      val a = SyncChannel[Int]()
      val b = SyncChannel[Int]()

      Future { a.send(0) }
      Future { assertEquals(b.read().right.get, 10) }
      var valuesSent = 0
      for i <- 1 to 2 do
        Async.select(
          a.readSource handle { case Right(v) =>
            assertEquals(v, 0)
          },
          b.sendSource(10) handle { case Right(_) =>
            valuesSent += 1
            assertEquals(valuesSent, 1)
          }
        )

      a.close()
      b.close()
      assert(Async.race(a.readSource, b.readSource).awaitResult.isLeft)
  }

  test("ChannelMultiplexer multiplexes - all subscribers read the same stream") {
    Async.blocking:
      val m = ChannelMultiplexer[Int]()
      val c = SyncChannel[Int]()
      m.addPublisher(c)

      val receivers = (1 to 3).map { _ =>
        val cr = SyncChannel[Try[Int]]()
        m.addSubscriber(cr)
        () =>
          (ac: Async) ?=>
            val l = ArrayBuffer[Int]()
            for i <- 1 to 4 do l += cr.read().right.get.get
            assertEquals(l, ArrayBuffer[Int](1, 2, 3, 4))
      }

      Future { m.run() }
      Future {
        for i <- 1 to 4 do c.send(i)
      }

      receivers.map(v => Future(v())).awaitAll
  }

  test("ChannelMultiplexer multiple readers and writers") {
    Async.blocking:
      val m = ChannelMultiplexer[Int]()
      val start = java.util.concurrent.CountDownLatch(5)

      val sendersCount = 3
      val sendersMessage = 4
      val receiversCount = 3

      val senders = (0 until sendersCount).map { idx =>
        val cc = SyncChannel[Int]()
        m.addPublisher(cc)
        () =>
          (ac: Async) ?=>
            for (i <- 0 until sendersMessage)
              cc.send(i)
            m.removePublisher(cc)
      }

      val receivers = (0 until receiversCount).map { idx =>
        val cr = SyncChannel[Try[Int]]()
        m.addSubscriber(cr)
        () =>
          (ac: Async) ?=>
            sleep(idx * 500)
            var sum = 0
            for (i <- 0 until sendersCount * sendersMessage) {
              sum += cr.read().right.get.get
            }
            assertEquals(sum, sendersMessage * (sendersMessage - 1) / 2 * sendersCount)
      }
      Future { m.run() }

      (senders ++ receivers)
        .map(v => Future(v()))
        .awaitAll
  }

  def getChannels = List(SyncChannel[Int](), BufferedChannel[Int](1024), UnboundedChannel[Int]())
}
