package gears.async.io
package testkit

import gears.async.Async
import gears.async.Future

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

/** Read, write, buffer-pool and close semantics over a supplied connected byte-stream fixture. */
trait StreamContract extends StreamFixture {

  /** The bytes of `bufs` read so far, in order, as text. */
  private def readText(bufs: Array[ByteBuffer]): String =
    bufs.map { b =>
      val copy = b.duplicate()
      copy.flip()
      StandardCharsets.UTF_8.decode(copy).toString
    }.mkString

  /** A pooled read of what `out` is sent, `text`, from `in`: what it received, which the caller releases. */
  private def receivedOf(io: Io[backend.type], in: Readable, out: Writable, pool: BufferPool, text: String)(using
      Async
  ): Received = {
    io.perform(io.backend.ops.write(out, bytes(text)))
    val into = Slot[Received]()
    io.perform(io.backend.ops.read(in, pool, into))
    into.clear()
  }

  /** The bytes a pooled read received, as text, consuming them. */
  private def textOf(received: Received): String = {
    val buf = received.buffer
    val out = new Array[Byte](buf.remaining())
    buf.get(out)
    new String(out, StandardCharsets.UTF_8)
  }

  test("an op on a closed handle fails, and what reuses the handle's I/O object gets nothing of it") {
    running { io ?=>
      val (in, out) = stream(io)
      val built = io.backend.ops.write(out, bytes("stale")) // built before the close, performed after
      close(io, in)
      close(io, out)
      val again = attempt(io, io.backend.ops.close(out))
      assert(again.isInstanceOf[ClosedChannelException], s"closing it again: $again")
      val (in2, out2) = stream(io) // on a backend with descriptors, most likely the same two numbers
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
      val (in, out) = stream(io)
      close(io, out)
      close(io, in)
      val failure = attempt(io, io.backend.ops.read(in, allocate(16)))
      assert(failure.isInstanceOf[ClosedChannelException], String.valueOf(failure))
    }
  }

  test("a read waits for data written later, then completes with it") {
    running { io ?=>
      val (in, out) = stream(io)
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
      val (in, out) = stream(io)
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
      val (in, out) = stream(io)
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
      val (in, out) = stream(io)
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

  test("cancelling the future performing a pending read stops it, and the op may then be performed again") {
    running { io ?=>
      val (in, out) = stream(io)
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
      val (in, out) = stream(io)
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

  test("a repeatable op is performed again by another future") {
    running { io ?=>
      val (in, out) = stream(io)
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

  test("closing a handle fails the op pending on it in another future") {
    running { io ?=>
      val (in, out) = stream(io)
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
      val (in, out) = stream(io)
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
      val (in, out) = stream(io)
      try {
        Future(io.perform(io.backend.ops.write(out, bytes("x")))).await
        Future(close(io, out)).await
        val buf = allocate(16)
        assertEquals(readToEnd(io, io.backend.ops.read(in, buf), buf), "x")
      } finally close(io, in)
    }
  }

  test("a pooled read fills a buffer of the pool's, which release gives back") {
    val pool = backend.pool(bufferSize = 64, buffers = 2)
    try
      running { io ?=>
        val (in, out) = stream(io)
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
        val (in, out) = stream(io)
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
        val (in, out) = stream(io)
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
        val (in, out) = stream(io)
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

  test("releasing a Received twice throws IllegalStateException, leaving the pool as it was") {
    val pool = backend.pool(bufferSize = 16, buffers = 2)
    try
      running { io ?=>
        val (in, out) = stream(io)
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
        val (in, out) = stream(io)
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

  test("a scattering read fills its buffers in order, each before the next, completing once bytes have come") {
    running { io ?=>
      val (in, out) = stream(io)
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
      val (in, out) = stream(io)
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
      val (in, out) = stream(io)
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
      val (in, out) = stream(io)
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
