package gears.async.io
package testkit

import gears.async.Async
import gears.async.Future

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*

/** What every backend's ops must do, as `Ops` describes them, checked through `perform` alone, so that code written
  * against the interface runs the same on any backend. A backend's suite extends this, and the contracts of the
  * capabilities its backend has (`SocketContract`, `FileContract`, `ResolveContract`, `UnixStreamContract`,
  * `UnixDatagramContract`, `FifoContract`), with the backend. Each check is a program (`running`), whose futures
  * perform ops together where a check needs one pending while another is performed, and which uses handles only through
  * ops. The streams here are pipes, the simplest a backend has.
  */
trait OpsContract extends ContractSuite {

  /** The backend under test: one with blocking ops and pipes, which these checks also cover. */
  val backend: Backend & Pipes & Blocking

  /** Opens a pipe: its read end and its write end. */
  protected def pipe(io: Io[backend.type])(using Async): (Readable, Writable) = {
    val read = Slot[Readable]()
    val write = Slot[Writable]()
    io.perform(io.backend.ops.pipe(read, write))
    (read.clear(), write.clear())
  }

  /** Gives a future just started the time to perform its op and wait in it. */
  protected def settle(io: Io[backend.type])(using Async): Unit = io.perform(io.backend.ops.timer(30))

  /** The failure `future` ended with, or null if it completed. */
  protected def failureOf(future: Future[?])(using Async): Throwable | Null =
    future.awaitResult.failed.toOption.orNull

  /** Performs `read` until it completes without adding bytes, returning what `buf` then holds. */
  protected def readToEnd(io: Io[backend.type], read: io.Op, buf: ByteBuffer)(using Async): String = {
    var atEnd = false
    while !atEnd do {
      val before = buf.position()
      io.perform(read)
      atEnd = buf.position() == before
    }
    text(buf)
  }

  // ---- Timers

  test("a timer completes after its delay") {
    running { io ?=>
      val started = System.nanoTime()
      io.perform(io.backend.ops.timer(20))
      assert(System.nanoTime() - started >= 20_000_000L, "the timer fired early")
    }
  }

  test("timers performed together complete in deadline order") {
    val fired = running { io ?=>
      val fired = scala.collection.mutable.ArrayBuffer.empty[Int]
      val timers = for delay <- List(90, 30, 60) yield Future {
        io.perform(io.backend.ops.timer(delay))
        fired.synchronized(fired += delay): Unit
      }
      timers.foreach(_.await)
      fired.synchronized(fired.toList)
    }
    assertEquals(fired, List(30, 60, 90))
  }

  test("a timer of a duration finer than a millisecond completes no earlier than it") {
    running { io ?=>
      val started = System.nanoTime()
      io.perform(io.backend.ops.timer(Duration(1500, java.util.concurrent.TimeUnit.MICROSECONDS)))
      assert(System.nanoTime() - started >= 1_500_000L, "the timer fired early")
    }
  }

  test("a deadline timer moved later completes at the new deadline, with no cancel, its first deadline passing") {
    running { io ?=>
      val start = System.nanoTime()
      val timer = io.backend.ops.deadlineTimer(start + 20_000_000L)
      val waiting = Future(io.perform(timer))
      timer.moveTo(start + 80_000_000L)
      timer.moveTo(start + 120_000_000L) // moved again, as QUIC's timers are for almost every packet
      waiting.await
      assert(System.nanoTime() - start >= 120_000_000L, "it fired at a deadline it had left")
      assertEquals(timer.deadline, start + 120_000_000L)
    }
  }

  test("a deadline timer moved earlier from another thread releases the future waiting on it") {
    running { io ?=>
      val timer = io.backend.ops.deadlineTimer(System.nanoTime() + 60_000_000_000L)
      val mover = new Thread(() => {
        Thread.sleep(50)
        timer.moveTo(System.nanoTime())
      })
      val started = System.nanoTime()
      mover.start()
      io.perform(timer) // waits until the move releases it
      mover.join()
      assert(System.nanoTime() - started < 10_000_000_000L, "it waited for its first deadline")
    }
  }

  test("coalesced earlier moves observe the latest deadline and can release the timer again later") {
    running { io ?=>
      val start = System.nanoTime()
      val timer = io.backend.ops.deadlineTimer(start + 60_000_000_000L)
      val waiting = Future(io.perform(timer))
      def moveBatch(finalDeadline: Long): Unit = {
        val mover = new Thread(() => {
          for i <- 1 to 1000 do timer.moveTo(start + 60_000_000_000L - i)
          timer.moveTo(finalDeadline)
        })
        mover.start()
        mover.join(5000)
        assert(!mover.isAlive)
      }
      moveBatch(start + 30_000_000_000L)
      settle(io)
      assert(waiting.poll().isEmpty, "it completed at a deadline it had left")
      moveBatch(System.nanoTime() - 1)
      waiting.await
    }
  }

  test("a deadline timer completes at once for a deadline passed, and performed again at its deadline as it stands") {
    running { io ?=>
      val timer = io.backend.ops.deadlineTimer(System.nanoTime() - 1_000_000L)
      io.perform(timer)
      val start = System.nanoTime()
      timer.moveTo(start + 30_000_000L) // not pending: the next performance starts from it
      io.perform(timer)
      assert(System.nanoTime() - start >= 30_000_000L)
    }
  }

  test("a deadline timer's time limit fails it if the limit comes first, the deadline moved past it or not") {
    running { io ?=>
      val timer = io.backend.ops.deadlineTimer(System.nanoTime() + 10_000_000L)
      val waiting = Future(io.perform(timer, 40.millis))
      timer.moveTo(System.nanoTime() + 60_000_000_000L)
      val failure = failureOf(waiting)
      assert(failure.isInstanceOf[TimeoutException], String.valueOf(failure))
    }
  }

  test("a timer with a shorter time limit fails with TimeoutException when the limit is up") {
    running { io ?=>
      val started = System.nanoTime()
      intercept[TimeoutException](io.perform(io.backend.ops.timer(60_000), 20.millis))
      assert(System.nanoTime() - started < 30_000_000_000L)
      io.perform(io.backend.ops.timer(1), 5.seconds) // a timer again, within its limit
    }
  }

  // ---- Closed handles

  test("an op on a closed handle fails, and what reuses the handle's I/O object gets nothing of it") {
    running { io ?=>
      val (in, out) = pipe(io)
      val built = io.backend.ops.write(out, bytes("stale")) // built before the close, performed after
      close(io, in)
      close(io, out)
      val again = attempt(io, io.backend.ops.close(out))
      assert(again.isInstanceOf[ClosedChannelException], s"closing it again: $again")
      val (in2, out2) = pipe(io) // on a backend with descriptors, most likely the same two numbers
      try {
        val early = attempt(io, built)
        assert(early.isInstanceOf[ClosedChannelException], s"an op built before the close: $early")
        val late = attempt(io, io.backend.ops.write(out, bytes("stale")))
        assert(late.isInstanceOf[ClosedChannelException], s"an op built after the close: $late")
        io.perform(io.backend.ops.write(out2, bytes("new")))
        val buf = allocate(16)
        io.perform(io.backend.ops.read(in2, buf))
        assertEquals(text(buf), "new")
      } finally {
        close(io, in2)
        close(io, out2)
      }
    }
  }

  test("an op on a closed handle fails") {
    running { io ?=>
      val (in, out) = pipe(io)
      close(io, out)
      close(io, in)
      val failure = attempt(io, io.backend.ops.read(in, allocate(16)))
      assert(failure.isInstanceOf[ClosedChannelException], String.valueOf(failure))
    }
  }

  // ---- Reads and writes

  test("a read waits for data written later, then completes with it") {
    running { io ?=>
      val (in, out) = pipe(io)
      try {
        val buf = allocate(64)
        val reading = Future(io.perform(io.backend.ops.read(in, buf)))
        settle(io)
        assert(reading.poll().isEmpty, "the read completed with nothing written")
        io.perform(io.backend.ops.write(out, bytes("hello")))
        reading.await
        assertEquals(text(buf), "hello")
      } finally {
        close(io, in)
        close(io, out)
      }
    }
  }

  test("a read at the end of the stream completes without adding bytes") {
    running { io ?=>
      val (in, out) = pipe(io)
      try {
        close(io, out)
        val buf = allocate(16)
        io.perform(io.backend.ops.read(in, buf))
        assertEquals(buf.position(), 0)
      } finally close(io, in)
    }
  }

  test("a read into a buffer with no room fails at once, reading nothing, whether or not bytes are waiting") {
    running { io ?=>
      val (in, out) = pipe(io)
      try {
        val full = allocate(4)
        full.position(full.limit())
        val read = io.backend.ops.read(in, full)
        def refused(): Unit = {
          val failure = attempt(io, read)
          assert(failure.isInstanceOf[IllegalArgumentException], String.valueOf(failure))
        }
        refused()
        io.perform(io.backend.ops.write(out, bytes("hello")))
        refused()
        assertEquals(full.position(), full.limit())
        val buf = allocate(16)
        io.perform(io.backend.ops.read(in, buf))
        assertEquals(text(buf), "hello", "the refused reads took none of the bytes")
      } finally {
        close(io, in)
        close(io, out)
      }
    }
  }

  test("a write with nothing remaining completes, writing nothing, even with the reader gone, and fails once closed") {
    running { io ?=>
      val (in, out) = pipe(io)
      val empty = allocate(4)
      empty.limit(0)
      val write = io.backend.ops.write(out, empty)
      try {
        close(io, in) // a write of bytes would fail now; one of nothing touches nothing
        io.perform(write)
        io.perform(write)
      } finally close(io, out)
      val failure = attempt(io, write)
      assert(failure.isInstanceOf[IOException], s"a closed handle fails first: $failure")
    }
  }

  test("a write to a pipe whose reader has closed fails with an IOException, not a signal that ends the process") {
    running { io ?=>
      val (in, out) = pipe(io)
      close(io, in)
      try {
        val failure = attempt(io, io.backend.ops.write(out, bytes("nobody reads this")))
        assert(failure.isInstanceOf[IOException], String.valueOf(failure))
      } finally close(io, out)
    }
  }

  test("a pipe's reads end after the bytes written before its writer closed") {
    running { io ?=>
      val (in, out) = pipe(io)
      try {
        io.perform(io.backend.ops.write(out, bytes("then the end")))
        close(io, out)
        val buf = allocate(64)
        assertEquals(readToEnd(io, io.backend.ops.read(in, buf), buf), "then the end")
      } finally close(io, in)
    }
  }

  test("writes fill a pipe, wait while it is full, and go on as it drains") {
    running { io ?=>
      val (in, out) = pipe(io)
      try {
        val size = 1 << 20
        val source = allocate(size)
        while source.hasRemaining do source.put((source.position() % 251).toByte)
        source.flip()
        val sink = allocate(size)
        val write = io.backend.ops.write(out, source)
        val read = io.backend.ops.read(in, sink)
        val writer = Future(while source.hasRemaining do io.perform(write))
        val reader = Future(while sink.hasRemaining do io.perform(read))
        writer.await
        reader.await
        sink.flip()
        var i = 0
        while i < size do {
          if sink.get(i) != (i % 251).toByte then fail(s"byte $i is wrong")
          i += 1
        }
      } finally {
        close(io, in)
        close(io, out)
      }
    }
  }

  // ---- Cancelling, and time limits

  test("cancelling the future performing a pending read stops it, and the op may then be performed again") {
    running { io ?=>
      val (in, out) = pipe(io)
      try {
        val buf = allocate(16)
        val read = io.backend.ops.read(in, buf)
        val first = Future(io.perform(read))
        settle(io)
        first.cancel()
        val failure = failureOf(first)
        assert(failure.isInstanceOf[CancellationException], String.valueOf(failure))
        assertEquals(buf.position(), 0)
        val second = Future(io.perform(read))
        io.perform(io.backend.ops.write(out, bytes("again")))
        second.await
        assertEquals(text(buf), "again")
      } finally {
        close(io, in)
        close(io, out)
      }
    }
  }

  test("a read with a time limit fails with TimeoutException when nothing comes, and may then be performed again") {
    running { io ?=>
      val (in, out) = pipe(io)
      try {
        val buf = allocate(16)
        val read = io.backend.ops.read(in, buf)
        val started = System.nanoTime()
        intercept[TimeoutException](io.perform(read, 20.millis))
        assert(System.nanoTime() - started >= 20_000_000L, "the read timed out early")
        assertEquals(buf.position(), 0) // nothing was read
        val second = Future(io.perform(read, 5.seconds))
        io.perform(io.backend.ops.write(out, bytes("in time")))
        second.await
        assertEquals(text(buf), "in time")
        // Its time limit went with it: nothing more comes of the read once it is done.
        io.perform(io.backend.ops.timer(30))
        assertEquals(text(buf), "in time")
      } finally {
        close(io, in)
        close(io, out)
      }
    }
  }

  // ---- Blocking ops

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

  // ---- Slots and one-shot ops

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

      val read = Slot[Readable]()
      val write = Slot[Writable]()
      io.perform(io.backend.ops.pipe(read, write))
      val in = read.clear()
      val refused = attempt(io, io.backend.ops.pipe(read, write)) // `write` still holds the first pipe's end
      assert(refused.isInstanceOf[IllegalArgumentException], String.valueOf(refused))
      intercept[IllegalStateException](read.clear()) // nothing opened
      close(io, in)
      close(io, write.clear())
    }
  }

  test("an op that opens or closes is one-shot: performed again, it throws IllegalStateException") {
    running { io ?=>
      val read = Slot[Readable]()
      val write = Slot[Writable]()
      val opening = io.backend.ops.pipe(read, write)
      io.perform(opening)
      val (in, out) = (read.clear(), write.clear())
      intercept[IllegalStateException](io.perform(opening))
      val closing = io.backend.ops.close(in)
      io.perform(closing)
      intercept[IllegalStateException](io.perform(closing))
      close(io, out)
    }
  }

  test("a one-shot op performed by two futures at once opens once, the other refused") {
    running { io ?=>
      for _ <- 1 to 100 do {
        val read = Slot[Readable]()
        val write = Slot[Writable]()
        val opening = io.backend.ops.pipe(read, write)
        val start = new CountDownLatch(1)
        val both = List.fill(2)(Future {
          start.await()
          io.perform(opening)
        })
        start.countDown()
        val failures = both.map(failureOf)
        assertEquals(failures.count(_ == null), 1, failures.mkString(", "))
        assert(failures.exists(_.isInstanceOf[IllegalStateException]), failures.mkString(", "))
        close(io, read.clear())
        close(io, write.clear())
      }
    }
  }

  test("a repeatable op is performed again by another future") {
    running { io ?=>
      val (in, out) = pipe(io)
      try {
        val buf = allocate(16)
        val read = io.backend.ops.read(in, buf)
        io.perform(io.backend.ops.write(out, bytes("a")))
        Future(io.perform(read)).await
        val second = Future(io.perform(read))
        settle(io)
        io.perform(io.backend.ops.write(out, bytes("b")))
        second.await
        assertEquals(text(buf), "ab")
      } finally {
        close(io, in)
        close(io, out)
      }
    }
  }

  test("a second read pending on a handle, or a second write, fails with IllegalArgumentException") {
    running { io ?=>
      val (in, out) = pipe(io)
      try {
        val first = Future(io.perform(io.backend.ops.read(in, allocate(16))))
        settle(io)
        val second = attempt(io, io.backend.ops.read(in, allocate(16)))
        assert(second.isInstanceOf[IllegalArgumentException], s"a second read: $second")
        val fromAnother = failureOf(Future(io.perform(io.backend.ops.read(in, allocate(16)))))
        assert(fromAnother.isInstanceOf[IllegalArgumentException], s"a second read from another future: $fromAnother")
        assert(first.poll().isEmpty, "the first read is still pending")
        io.perform(io.backend.ops.write(out, bytes("x")))
        first.await
        val again = Future(io.perform(io.backend.ops.read(in, allocate(16)))) // once the first has finished
        settle(io)
        io.perform(io.backend.ops.write(out, bytes("y")))
        again.await

        // Writes likewise: a pipe filled, so that one write waits.
        val filler = allocate(1 << 20)
        val writing = Future(while filler.hasRemaining do io.perform(io.backend.ops.write(out, filler)))
        settle(io)
        val secondWrite = attempt(io, io.backend.ops.write(out, bytes("z")))
        assert(secondWrite.isInstanceOf[IllegalArgumentException], s"a second write: $secondWrite")
        writing.cancel()
        writing.awaitResult
      } finally {
        close(io, in)
        close(io, out)
      }
    }
  }

  // ---- Closing as an op

  test("closing a handle fails the op pending on it in another future") {
    running { io ?=>
      val (in, out) = pipe(io)
      try {
        val reading = Future(io.perform(io.backend.ops.read(in, allocate(16))))
        settle(io) // the read is waiting
        close(io, in)
        val failure = failureOf(reading)
        assert(failure.isInstanceOf[ClosedChannelException], String.valueOf(failure))
      } finally close(io, out)
    }
  }

  test("a close op completes once the handle is closed, and fails the op pending on it") {
    running { io ?=>
      val (in, out) = pipe(io)
      val reading = Future(io.perform(io.backend.ops.read(in, allocate(16))))
      settle(io)
      io.perform(io.backend.ops.close(in))
      val failure = failureOf(reading)
      assert(failure.isInstanceOf[ClosedChannelException], String.valueOf(failure))
      val again = attempt(io, io.backend.ops.close(in))
      assert(again.isInstanceOf[ClosedChannelException], s"closing a closed handle fails: $again")
      io.perform(io.backend.ops.close(out))
    }
  }

  test("closing a handle another future has written to ends its peer's stream") {
    running { io ?=>
      val (in, out) = pipe(io)
      try {
        Future(io.perform(io.backend.ops.write(out, bytes("x")))).await
        Future(close(io, out)).await
        val buf = allocate(16)
        assertEquals(readToEnd(io, io.backend.ops.read(in, buf), buf), "x")
      } finally close(io, in)
    }
  }

  // ---- Reads into the backend's own buffers

  /** The bytes a pooled read received, as text, consuming them. */
  private def textOf(received: Received): String = {
    val buf = received.buffer
    val out = new Array[Byte](buf.remaining())
    buf.get(out)
    new String(out, StandardCharsets.UTF_8)
  }

  test("a pooled read fills a buffer of the pool's, which release gives back") {
    val pool = backend.pool(bufferSize = 64, buffers = 2)
    try
      running { io ?=>
        val (in, out) = pipe(io)
        try {
          val into = Slot[Received]()
          val reading = Future(io.perform(io.backend.ops.read(in, pool, into)))
          settle(io)
          assert(reading.poll().isEmpty, "the read completed with nothing written")
          io.perform(io.backend.ops.write(out, bytes("pooled")))
          reading.await
          val received = into.clear()
          assertEquals(received.buffer.position(), 0)
          assertEquals(textOf(received), "pooled")
          assertEquals(pool.available, 1)
          received.release()
          assertEquals(pool.available, 2)
        } finally {
          close(io, in)
          close(io, out)
        }
      }
    finally pool.close()
  }

  test("pooled reads preserve full buffers and the trailing partial buffer across reuse") {
    val pool = backend.pool(bufferSize = 8, buffers = 1)
    try
      running { io ?=>
        val (in, out) = pipe(io)
        val into = Slot[Received]()
        val read = io.backend.ops.read(in, pool, into)
        try {
          io.perform(io.backend.ops.write(out, bytes("abcdefghijklmnopqrs")))
          for expected <- List("abcdefgh", "ijklmnop", "qrs") do {
            io.perform(read)
            val received = into.clear()
            try assertEquals(textOf(received), expected)
            finally received.release()
          }
          assertEquals(pool.available, 1)
        } finally {
          close(io, in)
          close(io, out)
        }
      }
    finally pool.close()
  }

  test("a pooled read at the end of the stream completes with an empty buffer") {
    val pool = backend.pool(bufferSize = 16, buffers = 1)
    try
      running { io ?=>
        val (in, out) = pipe(io)
        try {
          close(io, out)
          val into = Slot[Received]()
          io.perform(io.backend.ops.read(in, pool, into))
          val received = into.clear()
          assertEquals(received.buffer.remaining(), 0)
          received.release()
        } finally close(io, in)
      }
    finally pool.close()
  }

  test("a pooled read fails when the pool has no buffer free, and goes on once one is released") {
    val pool = backend.pool(bufferSize = 16, buffers = 1)
    try
      running { io ?=>
        val (in, out) = pipe(io)
        try {
          io.perform(io.backend.ops.write(out, bytes("one")))
          val first = Slot[Received]()
          io.perform(io.backend.ops.read(in, pool, first))
          val held = first.clear()
          assertEquals(pool.available, 0)
          io.perform(io.backend.ops.write(out, bytes("two")))
          val second = Slot[Received]()
          val refused = attempt(io, io.backend.ops.read(in, pool, second))
          assert(refused.isInstanceOf[IOException], String.valueOf(refused))
          held.release()
          io.perform(io.backend.ops.read(in, pool, second))
          val received = second.clear()
          assertEquals(textOf(received), "two")
          received.release()
        } finally {
          close(io, in)
          close(io, out)
        }
      }
    finally pool.close()
  }

  /** A pooled read of what `out` is sent, `text`, from `in`: what it received, which the caller releases. */
  private def receivedOf(io: Io[backend.type], in: Readable, out: Writable, pool: BufferPool, text: String)(using
      Async
  ): Received = {
    io.perform(io.backend.ops.write(out, bytes(text)))
    val into = Slot[Received]()
    io.perform(io.backend.ops.read(in, pool, into))
    into.clear()
  }

  test("releasing a Received twice throws IllegalStateException, leaving the pool as it was") {
    val pool = backend.pool(bufferSize = 16, buffers = 2)
    try
      running { io ?=>
        val (in, out) = pipe(io)
        try {
          val received = receivedOf(io, in, out, pool, "once")
          received.release()
          intercept[IllegalStateException](received.release())
          assertEquals(pool.available, 2)
          val next = receivedOf(io, in, out, pool, "next") // the free list is whole
          val other = receivedOf(io, in, out, pool, "other")
          assertEquals(pool.available, 0)
          next.release()
          other.release()
        } finally {
          close(io, in)
          close(io, out)
        }
      }
    finally pool.close()
  }

  test("closing a pool with a buffer held fails, leaving it open, for the buffer to be released and the close done") {
    val pool = backend.pool(bufferSize = 16, buffers = 1)
    try
      running { io ?=>
        val (in, out) = pipe(io)
        try {
          val held = receivedOf(io, in, out, pool, "held")
          intercept[IllegalArgumentException](pool.close())
          assertEquals(textOf(held), "held")
          held.release()
          assertEquals(pool.available, 1, "the release reached the pool")
          receivedOf(io, in, out, pool, "again").release() // and the pool still lends it
          pool.close()
        } finally {
          close(io, in)
          close(io, out)
        }
      }
    finally pool.close()
  }

  // ---- Vectored ops, and any buffer

  /** The bytes of `bufs` read so far, in order, as text. */
  private def readText(bufs: Array[ByteBuffer]): String =
    bufs.map { b =>
      val copy = b.duplicate()
      copy.flip()
      StandardCharsets.UTF_8.decode(copy).toString
    }.mkString

  test("a scattering read fills its buffers in order, each before the next, completing once bytes have come") {
    running { io ?=>
      val (in, out) = pipe(io)
      try {
        io.perform(io.backend.ops.write(out, bytes("hello world")))
        val bufs = Array(allocate(4), allocate(4), allocate(8))
        io.perform(io.backend.ops.read(in, bufs))
        assertEquals(bufs.map(_.position()).toList, List(4, 4, 3))
        assertEquals(readText(bufs), "hello world")
      } finally {
        close(io, in)
        close(io, out)
      }
    }
  }

  test("a scattering read refuses buffers none of which has room, or none, and reads nothing at the end") {
    running { io ?=>
      val (in, out) = pipe(io)
      try {
        intercept[IllegalArgumentException](io.backend.ops.read(in, Array.empty[ByteBuffer]))
        val full = allocate(4)
        full.position(4)
        val refused = attempt(io, io.backend.ops.read(in, Array(full, full.duplicate())))
        assert(refused.isInstanceOf[IllegalArgumentException], String.valueOf(refused))
        close(io, out)
        val bufs = Array(allocate(4), allocate(4))
        io.perform(io.backend.ops.read(in, bufs))
        assertEquals(bufs.map(_.position()).toList, List(0, 0), "the end of the stream adds nothing")
      } finally close(io, in)
    }
  }

  test("a gathering write drains its buffers in order, and with nothing remaining completes at once") {
    running { io ?=>
      val (in, out) = pipe(io)
      try {
        val parts = Array(bytes("ab"), bytes(""), bytes("cdef"))
        io.perform(io.backend.ops.write(out, parts))
        assert(parts.forall(!_.hasRemaining()), "all written: a pipe takes six bytes at once")
        io.perform(io.backend.ops.write(out, parts)) // nothing remaining
        val buf = allocate(16)
        io.perform(io.backend.ops.read(in, buf))
        assertEquals(text(buf), "abcdef")
      } finally {
        close(io, in)
        close(io, out)
      }
    }
  }

  test("any buffer is read into and written from, on the heap or not, single or several; a read-only one is written") {
    running { io ?=>
      val (in, out) = pipe(io)
      try {
        def heap(text: String) = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8))
        def received(buf: ByteBuffer) = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8)
        val direct = ByteBuffer.allocateDirect(4)
        direct.put("dir|".getBytes(StandardCharsets.UTF_8)).flip()
        io.perform(io.backend.ops.write(out, heap("heap|")))
        io.perform(io.backend.ops.write(out, direct))
        io.perform(io.backend.ops.write(out, heap("ro|").asReadOnlyBuffer()))
        io.perform(io.backend.ops.write(out, Array(heap("a"), bytes("b"), heap("c|"))))
        val into = ByteBuffer.allocate(64)
        var got = ""
        while !got.endsWith("c|") do {
          io.perform(io.backend.ops.read(in, into))
          got = received(into)
        }
        assertEquals(got, "heap|dir|ro|abc|")
        io.perform(io.backend.ops.write(out, bytes("scattered")))
        val first = ByteBuffer.allocate(4)
        val second = allocate(16)
        var total = 0
        while total < 9 do {
          io.perform(io.backend.ops.read(in, Array(first, second)))
          total = first.position() + second.position()
        }
        assertEquals(received(first) + text(second), "scattered")
        intercept[IllegalArgumentException](io.backend.ops.read(in, ByteBuffer.allocate(8).asReadOnlyBuffer()))
      } finally {
        close(io, in)
        close(io, out)
      }
    }
  }
}
