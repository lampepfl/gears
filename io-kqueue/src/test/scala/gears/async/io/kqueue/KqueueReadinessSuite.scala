package gears.async.io
package kqueue

import gears.async.Async
import gears.async.AsyncOperations
import gears.async.AsyncSupport
import gears.async.Future

import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.TimeoutException
import scala.concurrent.duration.*
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.util.Failure

/** Reads and writes that find their pipe not ready: they wait for the worker's kqueue to report it, and stop waiting
  * when their time limit passes, they are cancelled, or the handle closes.
  */
class KqueueReadinessSuite extends munit.FunSuite {
  override def munitTimeout: Duration = 60.seconds

  private def onPool[T](workers: Int)(program: Io[KqueueOps.type] ?=> Async.Spawn ?=> T): T = {
    val pool = KqueueScheduler(workers)
    given poolSupport: (AsyncSupport { type Scheduler = pool.Scheduler }) = pool
    given poolScheduler: pool.Scheduler = pool
    try Async.blocking(Future(program(using pool)).await)
    finally pool.shutdown()
  }

  private def pipe()(using io: Io[KqueueOps.type], async: Async): (KqueueReadable, KqueueWritable) = {
    val read = Slot[KqueueReadable]()
    val write = Slot[KqueueWritable]()
    io.perform(KqueueOps.pipe(read, write))
    (read.clear(), write.clear())
  }

  private def bytes(text: String): ByteBuffer = ByteBuffer.wrap(text.getBytes("UTF-8"))

  private def text(buf: ByteBuffer): String = {
    val got = buf.duplicate()
    got.flip()
    val out = new Array[Byte](got.remaining())
    got.get(out)
    new String(out, "UTF-8")
  }

  for workers <- List(1, 4) do
    test(s"a read on an empty pipe waits for a write ($workers workers)") {
      onPool(workers) { io ?=>
        val (in, out) = pipe()
        val buf = ByteBuffer.allocate(64)
        val reader = Future(io.perform(KqueueOps.read(in, buf)))
        AsyncOperations.sleep(50)
        assert(reader.poll().isEmpty)
        io.perform(KqueueOps.write(out, bytes("hello")))
        reader.await
        assertEquals(text(buf), "hello")
        io.perform(KqueueOps.close(in))
        io.perform(KqueueOps.close(out))
      }
    }

    test(s"a waiting read ends, reading nothing, when the writer closes ($workers workers)") {
      onPool(workers) { io ?=>
        val (in, out) = pipe()
        val buf = ByteBuffer.allocate(64)
        val reader = Future(io.perform(KqueueOps.read(in, buf)))
        AsyncOperations.sleep(50)
        io.perform(KqueueOps.close(out))
        reader.await
        assertEquals(buf.position(), 0)
        io.perform(KqueueOps.close(in))
      }
    }

    test(s"a waiting read fails with a closed channel's exception when its handle closes ($workers workers)") {
      onPool(workers) { io ?=>
        val (in, out) = pipe()
        val reader = Future(io.perform(KqueueOps.read(in, ByteBuffer.allocate(64))))
        AsyncOperations.sleep(50)
        io.perform(KqueueOps.close(in))
        reader.awaitResult match
          case Failure(_: ClosedChannelException) => ()
          case other                              => fail(s"not a closed channel: $other")
        io.perform(KqueueOps.close(out))
      }
    }

    test(s"a cancelled read stops waiting, and its handle reads on ($workers workers)") {
      onPool(workers) { io ?=>
        val (in, out) = pipe()
        val reader = Future(io.perform(KqueueOps.read(in, ByteBuffer.allocate(64))))
        AsyncOperations.sleep(50)
        reader.cancel()
        reader.awaitResult match
          case Failure(_: CancellationException) => ()
          case other                             => fail(s"not cancelled: $other")
        io.perform(KqueueOps.write(out, bytes("later")))
        val buf = ByteBuffer.allocate(64)
        io.perform(KqueueOps.read(in, buf))
        assertEquals(text(buf), "later")
        io.perform(KqueueOps.close(in))
        io.perform(KqueueOps.close(out))
      }
    }

  test("a read that times out read nothing, and the same op reads once there is something") {
    onPool(2) { io ?=>
      val (in, out) = pipe()
      val buf = ByteBuffer.allocate(64)
      val read = KqueueOps.read(in, buf)
      intercept[TimeoutException](io.perform(read, 50.millis))
      assertEquals(buf.position(), 0)
      val reader = Future(io.perform(read, 10.seconds))
      AsyncOperations.sleep(50)
      io.perform(KqueueOps.write(out, bytes("again")))
      reader.await
      assertEquals(text(buf), "again")
      io.perform(KqueueOps.close(in))
      io.perform(KqueueOps.close(out))
    }
  }

  for workers <- List(1, 4) do test(s"a second read pending on a handle is refused, and the first reads on ($workers workers)") {
    onPool(workers) { io ?=>
      val (in, out) = pipe()
      val buf = ByteBuffer.allocate(64)
      val reader = Future(io.perform(KqueueOps.read(in, buf)))
      AsyncOperations.sleep(50)
      intercept[IllegalArgumentException](io.perform(KqueueOps.read(in, ByteBuffer.allocate(64))))
      io.perform(KqueueOps.write(out, bytes("first")))
      reader.await
      assertEquals(text(buf), "first")
      io.perform(KqueueOps.close(in))
      io.perform(KqueueOps.close(out))
    }
  }

  test("a write to a full pipe waits for a read to make room") {
    onPool(2) { io ?=>
      val (in, out) = pipe()
      val chunk = new Array[Byte](4096)
      // Fills the pipe: a write that can't complete at once times out, having written nothing.
      var written = 0L
      var full = false
      while !full do
        val buf = ByteBuffer.wrap(chunk)
        try
          io.perform(KqueueOps.write(out, buf), 50.millis)
          written += buf.position()
        catch case _: TimeoutException => full = true
      assert(written > 0)
      val pending = ByteBuffer.wrap(chunk)
      val writer = Future(io.perform(KqueueOps.write(out, pending)))
      AsyncOperations.sleep(50)
      assert(writer.poll().isEmpty)
      var drained = 0L
      while drained < written do
        val buf = ByteBuffer.allocate(65536)
        io.perform(KqueueOps.read(in, buf))
        drained += buf.position()
      writer.await
      assert(pending.position() > 0)
      io.perform(KqueueOps.close(in))
      io.perform(KqueueOps.close(out))
    }
  }

  test("reads that each wait, from whichever worker, are woken by the handle's one listener") {
    onPool(4) { io ?=>
      val (pingIn, pingOut) = pipe()
      val (pongIn, pongOut) = pipe()
      val rounds = 2000
      val echo = Future {
        val buf = ByteBuffer.allocate(1)
        var i = 0
        while i < rounds do
          buf.clear()
          io.perform(KqueueOps.read(pingIn, buf))
          buf.flip()
          io.perform(KqueueOps.write(pongOut, buf))
          i += 1
      }
      var i = 0
      while i < rounds do
        // Each round from a future of its own, which the pool gives to its workers in turn.
        val got = Future {
          io.perform(KqueueOps.write(pingOut, ByteBuffer.wrap(Array((i % 100).toByte))))
          val buf = ByteBuffer.allocate(1)
          io.perform(KqueueOps.read(pongIn, buf))
          buf.get(0)
        }.await
        assertEquals(got, (i % 100).toByte)
        i += 1
      echo.await
      List(pingIn, pingOut, pongIn, pongOut).foreach(handle => io.perform(KqueueOps.close(handle)))
    }
  }

  test("reads time out on whichever worker they wait from, and the handle still reads") {
    onPool(4) { io ?=>
      val (in, out) = pipe()
      var i = 0
      while i < 100 do
        val timedOut = Future {
          try { io.perform(KqueueOps.read(in, ByteBuffer.allocate(8)), 2.millis); false }
          catch case _: TimeoutException => true
        }.await
        assert(timedOut)
        i += 1
      val buf = ByteBuffer.allocate(8)
      val reader = Future(io.perform(KqueueOps.read(in, buf), 10.seconds))
      AsyncOperations.sleep(20)
      io.perform(KqueueOps.write(out, bytes("ok")))
      reader.await
      assertEquals(text(buf), "ok")
      io.perform(KqueueOps.close(in))
      io.perform(KqueueOps.close(out))
    }
  }

  test("a handle that stopped waiting leaves its listener, so a worker that is kept busy can't hold up its next wait") {
    onPool(2) { io ?=>
      val (in, out) = pipe()

      // A read that waits makes its worker the handle's listener.
      val listenerThread = new AtomicReference[String]("")
      val first = Future {
        listenerThread.set(Thread.currentThread().getName())
        io.perform(KqueueOps.read(in, ByteBuffer.allocate(1)))
      }
      AsyncOperations.sleep(50)
      io.perform(KqueueOps.write(out, bytes("a")))
      first.await

      // Then the handle is only read when it has something: each write is reported to the listener, to no waiter.
      var i = 0
      while i < 64 do
        io.perform(KqueueOps.write(out, bytes("b")))
        AsyncOperations.sleep(2)
        io.perform(KqueueOps.read(in, ByteBuffer.allocate(1)))
        i += 1

      // The program keeps the listener's worker busy, and a read on the other worker has to wait.
      val busyFor = 1500L
      val busy = new AtomicBoolean(false)
      val reading = new AtomicBoolean(false)
      val waited = new AtomicLong(-1L)
      val futures = List.fill(8)(Future {
        val onListener = Thread.currentThread().getName() == listenerThread.get()
        if onListener && busy.compareAndSet(false, true) then Thread.sleep(busyFor)
        else if !onListener && reading.compareAndSet(false, true) then
          while !busy.get() do Thread.sleep(1)
          val start = System.nanoTime()
          val writer = new Thread(() => {
            Thread.sleep(100)
            val byte = stackalloc[Byte]()
            unistd.write(out.fd, byte, 1.toCSize): Unit
          })
          writer.start()
          io.perform(KqueueOps.read(in, ByteBuffer.allocate(1)))
          waited.set((System.nanoTime() - start) / 1_000_000L)
      })
      futures.foreach(_.await)
      assert(busy.get() && waited.get() >= 0, "the futures did not reach both workers")
      assert(waited.get() < busyFor - 500, s"the read waited ${waited.get()}ms, for the busy worker")
      io.perform(KqueueOps.close(in))
      io.perform(KqueueOps.close(out))
    }
  }

  test("many reads and writes pass through a pipe in order") {
    onPool(4) { io ?=>
      val (in, out) = pipe()
      val total = 200_000
      val writer = Future {
        val buf = ByteBuffer.allocate(total)
        var i = 0
        while i < total do { buf.put((i % 251).toByte); i += 1 }
        buf.flip()
        val write = KqueueOps.write(out, buf)
        while buf.hasRemaining() do io.perform(write)
        io.perform(KqueueOps.close(out))
      }
      val got = ByteBuffer.allocate(total + 1)
      val read = KqueueOps.read(in, got)
      var ended = false
      while !ended do
        val before = got.position()
        io.perform(read)
        ended = got.position() == before
      writer.await
      assertEquals(got.position(), total)
      var i = 0
      while i < total do { assertEquals(got.get(i), (i % 251).toByte); i += 1 }
      io.perform(KqueueOps.close(in))
    }
  }
}
