package gears.async.io
package testkit

import gears.async.Async
import gears.async.Future

import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*

/** Concurrent programs over a supplied stream fixture, without assuming how handles are opened. */
trait StreamFuturesContract extends StreamFixture {

  test("continuously ready reads let sibling tasks and timers progress") {
    running { io ?=>
      val (in, out) = stream(io)
      io.perform(io.backend.ops.close(out)) // EOF stays immediately ready on every read.
      val progressed = new java.util.concurrent.atomic.AtomicBoolean
      try {
        val busy = Future {
          val buffer = arena.allocate(1)
          val read = io.backend.ops.read(in, buffer)
          var iterations = 0
          var observed = false
          while iterations < 10000 do {
            buffer.clear()
            io.perform(read)
            observed ||= progressed.get()
            iterations += 1
          }
          observed
        }
        val sibling = Future {
          io.perform(io.backend.ops.timer(0))
          progressed.set(true)
        }
        // MUnit's by-name assert holds a monitor: never suspend inside it, since a native continuation may migrate.
        val observedProgress = busy.await
        assert(observedProgress, "immediate readiness starved a sibling's timer")
        sibling.await
      } finally io.perform(io.backend.ops.close(in))
    }
  }

  test("a read unlinks from a scope with a child after its buffer changes") {
    running { io ?=>
      val (in, out) = stream(io)
      try {
        // The child and its parent's pending read are distinct group members.
        val writer = Future {
          io.perform(io.backend.ops.timer(10))
          io.perform(io.backend.ops.write(out, arena.of(Array[Byte](42))))
        }
        val buffer = arena.allocate(8)
        io.perform(io.backend.ops.read(in, buffer))
        writer.await
        assertEquals(buffer.position(), 1)
      } finally {
        io.perform(io.backend.ops.close(in))
        io.perform(io.backend.ops.close(out))
      }
    } // Joining the scope must finish even when the read operation's structural hash changed.
  }

  test("two futures stream bytes through connected handles") {
    val total = 1 << 20
    val received = running { io ?=>
      val (in, out) = stream(io)
      try {
        val writer = Future {
          val buf = arena.allocate(8192)
          val write = io.backend.ops.write(out, buf)
          var sent = 0
          while sent < total do {
            buf.clear().limit(math.min(8192, total - sent))
            while buf.hasRemaining do io.perform(write)
            sent += buf.limit()
          }
          io.perform(io.backend.ops.close(out))
        }
        val reader = Future {
          val buf = arena.allocate(8192)
          val read = io.backend.ops.read(in, buf)
          var received = 0
          var atEnd = false
          while !atEnd do {
            buf.clear()
            io.perform(read)
            if buf.position() == 0 then atEnd = true else received += buf.position()
          }
          received
        }
        writer.await
        reader.await
      } finally io.perform(io.backend.ops.close(in))
    }
    assertEquals(received, total)
  }

  test("a read performed while another future's read waits on the handle fails with IllegalArgumentException") {
    val outcome = running { io ?=>
      val (in, out) = stream(io)
      try {
        val buf = arena.allocate(16)
        val waiting = Future(io.perform(io.backend.ops.read(in, buf)))
        io.perform(io.backend.ops.timer(20)) // so that the first read is pending
        val second =
          try {
            io.perform(io.backend.ops.read(in, arena.allocate(16)))
            "performed"
          } catch case _: IllegalArgumentException => "refused"
        io.perform(io.backend.ops.write(out, arena.of("first".getBytes(StandardCharsets.UTF_8))))
        waiting.await
        s"$second ${text(buf)}"
      } finally {
        io.perform(io.backend.ops.close(in))
        io.perform(io.backend.ops.close(out))
      }
    }
    assertEquals(outcome, "refused first")
  }

  test("cancelling a future cancels the op it waits on, which may then be performed again") {
    val outcome = running { io ?=>
      val (in, out) = stream(io)
      try {
        val buf = arena.allocate(16)
        val read = io.backend.ops.read(in, buf)
        val waiting = Future(io.perform(read))
        io.perform(io.backend.ops.timer(20)) // so that the read is pending
        waiting.cancel()
        val cancelled = waiting.awaitResult.failed.toOption.exists(_.isInstanceOf[CancellationException])
        io.perform(io.backend.ops.write(out, arena.of("again".getBytes(StandardCharsets.UTF_8))))
        io.perform(read)
        s"$cancelled ${text(buf)}"
      } finally {
        io.perform(io.backend.ops.close(in))
        io.perform(io.backend.ops.close(out))
      }
    }
    assertEquals(outcome, "true again")
  }

  test("a read into a buffer with no room fails at once, reading nothing, on the supplied stream") {

    /** Reads into a full buffer before and after `send` writes, then reads what was sent; the time limits turn a read
      * that never returns into a failure.
      */
    def check(io: Io[backend.type], in: Readable, send: String => Unit)(using Async): String = {
      val full = arena.allocate(4)
      full.position(full.limit())
      val read = io.backend.ops.read(in, full)
      def refused(): Boolean =
        try {
          io.perform(read, FiniteDuration(2_000, "ms"))
          false
        } catch case _: IllegalArgumentException => true
      val before = refused()
      send("hello")
      val after = refused()
      val buf = arena.allocate(16)
      val readSome = io.backend.ops.read(in, buf)
      while buf.position() < 5 do io.perform(readSome, FiniteDuration(2_000, "ms"))
      s"$before $after ${full.position() == full.limit()} ${text(buf)}"
    }
    def writeAll(io: Io[backend.type], out: Writable)(message: String)(using Async): Unit = {
      val buf = arena.of(message.getBytes(StandardCharsets.UTF_8))
      val write = io.backend.ops.write(out, buf)
      while buf.hasRemaining do io.perform(write)
    }
    val outcome = running { io ?=>
      val (in, out) = stream(io)
      try check(io, in, writeAll(io, out))
      finally {
        close(io, in)
        close(io, out)
      }
    }
    assertEquals(outcome, "true true true hello")
  }

  test("performing a read with a time limit throws TimeoutException, and the read may then be performed again") {
    val outcome = running { io ?=>
      val (in, out) = stream(io)
      try {
        val buf = arena.allocate(16)
        val read = io.backend.ops.read(in, buf)
        val started = System.nanoTime()
        val timedOut =
          try {
            io.perform(read, FiniteDuration(20, "ms"))
            false
          } catch case _: TimeoutException => true
        val waited = System.nanoTime() - started
        io.perform(io.backend.ops.write(out, arena.of("in time".getBytes(StandardCharsets.UTF_8))))
        io.perform(read, FiniteDuration(5_000, "ms"))
        s"$timedOut ${waited >= 20_000_000L} ${buf.position()} ${text(buf)}"
      } finally {
        io.perform(io.backend.ops.close(in))
        io.perform(io.backend.ops.close(out))
      }
    }
    assertEquals(outcome, "true true 7 in time")
  }

  test("an op waiting on a handle that is closed fails") {
    val failure = running { io ?=>
      val (in, out) = stream(io)
      try {
        val waiting = Future(io.perform(io.backend.ops.read(in, arena.allocate(4))))
        io.perform(io.backend.ops.timer(20))
        io.perform(io.backend.ops.close(in))
        waiting.awaitResult.failed.toOption.orNull
      } finally io.perform(io.backend.ops.close(out))
    }
    assert(failure.isInstanceOf[java.nio.channels.ClosedChannelException], String.valueOf(failure))
  }

  test("an op that fails fails perform") {
    val failure = running { io ?=>
      val (in, out) = stream(io)
      io.perform(io.backend.ops.close(in))
      io.perform(io.backend.ops.close(out))
      try {
        io.perform(io.backend.ops.read(in, arena.allocate(4)))
        null
      } catch case t: Throwable => t
    }
    assert(failure.isInstanceOf[IOException], String.valueOf(failure))
  }

  test("an op a future performs races in select, and cancelling the future stops it, the stream left usable") {
    val outcome = running { io ?=>
      val (in, out) = stream(io)
      try {
        val buf = arena.allocate(16)
        val read = Future(io.perform(io.backend.ops.read(in, buf))) // pending: nothing is written
        val timer = Future(io.perform(io.backend.ops.timer(20)))
        val first = Async.select(timer.handle(_ => "timer"), read.handle(_ => "read"))
        read.cancel()
        val cancelled = read.awaitResult.failed.toOption.exists(_.isInstanceOf[CancellationException])
        // The cancelled read took nothing: what is written now is read in full.
        io.perform(io.backend.ops.write(out, arena.of("after".getBytes(StandardCharsets.UTF_8))))
        val again = arena.allocate(16)
        io.perform(io.backend.ops.read(in, again))
        s"$first $cancelled ${text(again)}"
      } finally {
        io.perform(io.backend.ops.close(in))
        io.perform(io.backend.ops.close(out))
      }
    }
    assertEquals(outcome, "timer true after")
  }
}
