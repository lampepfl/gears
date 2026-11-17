package gears.async.io
package nio

import gears.async.Async
import gears.async.Future

import scala.concurrent.duration.*

/** Where each of the two backends runs a program's futures, and that both wake them from their workers. */
class NioSchedulingSuite extends munit.FunSuite {
  override def munitTimeout: Duration = 60.seconds

  private def carrierName(): String = VirtualCarriers.currentCarrier().getName()

  private def isWorker(name: String): Boolean = name.startsWith("WorkpoolSchedulerWorker-")

  test("on the JDK's threads a future is a virtual thread that no worker carries, before and after it waits") {
    given NioBackend.type = NioBackend
    Io.run { io ?=>
      val seen = Future {
        assert(Thread.currentThread().isVirtual())
        val before = carrierName()
        io.perform(io.ops.timer(5))
        (before, carrierName())
      }.await
      assert(!isWorker(seen._1), seen)
      assert(!isWorker(seen._2), seen)
    }
  }

  test("on the pool a future is a virtual thread that a worker carries, before and after it waits") {
    given NioPoolBackend.type = NioPoolBackend
    Io.run { io ?=>
      val seen = Future {
        assert(Thread.currentThread().isVirtual())
        val before = carrierName()
        io.perform(io.ops.timer(5))
        (before, carrierName())
      }.await
      assert(isWorker(seen._1), seen)
      assert(isWorker(seen._2), seen)
    }
  }

  test("on the pool a future woken by a worker's selector goes on on that worker") {
    given NioPoolBackend.type = NioPoolBackend
    NioPoolBackend.runOn(4, shareable.Starvation.printWarning) { io ?=>
      val ops = io.ops
      val read = Slot[ops.Readable]()
      val write = Slot[ops.Writable]()
      io.perform(ops.pipe(read, write))
      val (in, out) = (read.clear(), write.clear())
      val reading = Future {
        val buf = java.nio.ByteBuffer.allocateDirect(8)
        val waitedOn = carrierName() // the worker that listens for the pipe from here on
        val woken = for _ <- 1 to 20 yield {
          buf.clear()
          io.perform(ops.read(in, buf))
          carrierName()
        }
        (waitedOn, woken.toSet)
      }
      val buf = java.nio.ByteBuffer.allocateDirect(1)
      for _ <- 1 to 20 do
        io.perform(ops.timer(2)) // so that the read waits each time
        buf.clear()
        io.perform(ops.write(out, buf))
      val (waitedOn, woken) = reading.await
      assertEquals(woken, Set(waitedOn))
      io.perform(ops.close(in))
      io.perform(ops.close(out))
    }
  }

  test("on the pool, futures that only compute and yield share the workers with those that wait") {
    given NioPoolBackend.type = NioPoolBackend
    NioPoolBackend.runOn(2, shareable.Starvation.printWarning) { io ?=>
      @volatile var stop = false
      val spinning = (1 to 4).map(_ => Future { while !stop do io.`yield`() })
      val started = System.nanoTime()
      for _ <- 1 to 20 do io.perform(io.ops.timer(1))
      val took = (System.nanoTime() - started) / 1_000_000L
      stop = true
      spinning.foreach(_.await)
      assert(took < 2000, s"20 timers of 1 ms took $took ms beside futures that yield")
    }
  }

  private val both: List[(String, Backend { type Interface = NioOps.type })] =
    List("on the JDK's threads" -> NioBackend, "on the pool" -> NioPoolBackend)

  for (where, backend) <- both do {
    test(s"$where, what a run opened and left open, of every kind, is closed as the run ends") {
      val ops = NioOps
      val path = java.nio.file.Files.createTempFile("gears-run-end", ".txt")
      val left = Io.run(using backend) { io ?=>
        def opening[K <: AnyRef](build: Slot[K] => ops.Op)(using Async): K = {
          val into = Slot[K]()
          io.perform(build(into))
          into.clear()
        }
        val listener = opening[ops.Listener](ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), _))
        val address = ops.localAddress(listener).asInstanceOf[IpSocketAddress]
        val connecting = Future(opening[ops.Connection](ops.connect(address, _)))
        val accepted = opening[ops.Connection](ops.accept(listener, _))
        val connected = connecting.await
        val datagram = opening[ops.BoundDatagram](ops.bindDatagram(IpSocketAddress(IpAddress.IPv4Loopback, 0), _))
        val file = opening[ops.File](ops.open(path.toString, false, _))
        val read = Slot[ops.Readable]()
        val write = Slot[ops.Writable]()
        io.perform(ops.pipe(read, write))
        val (in, out) = (read.clear(), write.clear())
        // Waited on, so that a selector has them, and their closes are finished by its worker.
        val reading = Future(io.perform(ops.read(in, java.nio.ByteBuffer.allocateDirect(8))))
        val accepting = Future(io.perform(ops.accept(listener, Slot[ops.Connection]())))
        io.perform(ops.timer(20))
        List[ops.Handle](listener, accepted, connected, datagram, file, in, out)
      }
      try for handle <- left do assertEquals(handle.getState, shareable.HandleState.ClosedState, handle.toString)
      finally java.nio.file.Files.deleteIfExists(path): Unit
    }

    test(s"$where, a handle is refused by a run other than the one that opened it") {
      val ops = NioOps
      Io.run(using backend) { io ?=>
        val read = Slot[ops.Readable]()
        val write = Slot[ops.Writable]()
        io.perform(ops.pipe(read, write))
        val (in, out) = (read.clear(), write.clear())
        val buf = java.nio.ByteBuffer.allocateDirect(8)
        val foreign =
          List[(String, ops.Op)]("read" -> ops.read(in, buf), "write" -> ops.write(out, buf), "close" -> ops.close(out))
        @volatile var refusals: List[(String, Throwable | Null)] = Nil
        val other = new Thread(() =>
          refusals = Io.run(using backend) { theirs ?=>
            foreign.map((name, op) =>
              name -> (try { theirs.perform(op); null }
              catch case failed: Exception => failed)
            )
          }
        )
        other.start()
        io.perform(ops.blocking(() => other.join())) // off the workers and the carriers
        for (name, failure) <- refusals do
          assert(failure.isInstanceOf[IllegalArgumentException], s"$name by another run: $failure")
        assertEquals(refusals.size, foreign.size)
        io.perform(ops.close(in))
        io.perform(ops.close(out))
      }
    }

    test(s"$where, a closed listener's port is free once its close op has completed, though a selector had it") {
      val ops = NioOps
      Io.run(using backend) { io ?=>
        for _ <- 1 to 50 do {
          val into = Slot[ops.Listener]()
          io.perform(ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), into))
          val listener = into.clear()
          val port = ops.localAddress(listener).asInstanceOf[IpSocketAddress].port
          val accepting = Future(io.perform(ops.accept(listener, Slot[ops.Connection]())))
          io.perform(ops.timer(1)) // so that the accept waits, and a selector has the listener
          io.perform(ops.close(listener))
          accepting.awaitResult
          io.perform(ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, port), into))
          io.perform(ops.close(into.clear()))
        }
      }
    }
  }

  private def spin(nanos: Long): Unit = {
    val end = System.nanoTime() + nanos
    while System.nanoTime() < end do Thread.onSpinWait()
  }

  private def reportsDuring(body: shareable.Starvation.Handler => Unit): List[shareable.Starvation.Report] = {
    val reports = new java.util.concurrent.ConcurrentLinkedQueue[shareable.Starvation.Report]
    body(report => reports.add(report): Unit)
    reports.toArray(Array.empty[shareable.Starvation.Report]).toList
  }

  test("on the pool, a future that computes without yielding while another waits behind it is reported, and relieved") {
    val reports = reportsDuring { handler =>
      NioPoolBackend.runOn(4, handler) { io ?=>
        val carrier = VirtualCarriers.currentCarrier()
        val started = new java.util.concurrent.CountDownLatch(1)
        val spawned = Future { // on this worker, behind the program
          started.countDown()
          VirtualCarriers.currentCarrier()
        }
        spin(400_000_000L)
        assertEquals(started.getCount(), 0L, "the future behind the one computing never ran")
        assert(spawned.await ne carrier)
      }
    }
    assertEquals(reports.map(_.movedTasks), List(1), reports.map(_.message).mkString("\n"))
  }

  test("on the pool, a future that sleeps or waits on a lock leaves its worker, and nothing is reported") {
    val reports = reportsDuring { handler =>
      NioPoolBackend.runOn(1, handler) { io ?=>
        val ran = Future(1)
        Thread.sleep(300) // a virtual thread's sleep parks it, off its carrier
        assertEquals(ran.await, 1)
      }
    }
    assertEquals(reports.map(_.message), Nil)
  }

  test("on the JDK's threads, a future that computes without yielding keeps no worker, and nothing is reported") {
    val reports = reportsDuring { handler =>
      NioBackend.runOn(1, handler) { io ?=>
        val ticking = Future { for _ <- 1 to 10 do io.perform(io.ops.timer(10)) }
        spin(300_000_000L)
        ticking.await
      }
    }
    assertEquals(reports.map(_.message), Nil)
  }
}
