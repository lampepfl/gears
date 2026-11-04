package gears.io.testkit

import gears.async.Future
import gears.async.io.testkit.StreamFixture

import java.io.IOException
import java.nio.charset.StandardCharsets

/** Public API stream helpers, using any supplied connected stream fixture. */
trait SimpleIOChecks extends StreamFixture {
  test("SimpleIO over perform: result-returning stream reads") {
    val outcome = running { io ?=>
      val (in, out) = stream(io)
      try {
        val buf = arena.allocate(16)
        val writer = Future {
          SimpleIO.writeAll(out, arena.of("wrapped".getBytes(StandardCharsets.UTF_8)))
          io.perform(io.backend.ops.close(out))
        }
        val got = SimpleIO.read(in, buf)
        writer.await
        val atEnd = SimpleIO.read(in, buf)
        s"$got ${text(buf)} $atEnd"
      } finally io.perform(io.backend.ops.close(in))
    }
    assertEquals(outcome, "7 wrapped 0")
  }

  test("SimpleIO over perform: a stream of chunks consumed as a gears channel, with back-pressure") {
    val total = 1 << 20
    val received = running { io ?=>
      val pool = io.backend.pool(bufferSize = 4096, buffers = 4) // a backlog of two, one being sent, one being read
      val (in, out) = stream(io)
      try {
        val writer = Future {
          val buf = arena.allocate(8192)
          var sent = 0
          while sent < total do {
            buf.clear().limit(math.min(8192, total - sent))
            SimpleIO.writeAll(out, buf)
            sent += buf.limit()
          }
          io.perform(io.backend.ops.close(out))
        }
        val received = SimpleIO.drain(SimpleIO.chunks(in, pool, backlog = 2))
        writer.await
        received
      } finally {
        io.perform(io.backend.ops.close(in))
        pool.close()
      }
    }
    assertEquals(received, total.toLong)
  }

  test("SimpleIO over perform: a stream closed early releases its chunks, and one that fails rethrows") {
    val outcome = running { io ?=>
      val pool = io.backend.pool(bufferSize = 1024, buffers = 4)
      val (in, out) = stream(io)
      try {
        SimpleIO.writeAll(out, arena.of(("x" * 3000).getBytes(StandardCharsets.UTF_8)))
        val stream = SimpleIO.chunks(in, pool, backlog = 2)
        val first = stream.read().map { chunk =>
          val n = chunk.buffer.remaining()
          chunk.release()
          n
        }
        io.perform(io.backend.ops.timer(20)) // lets the reader fill the window and the backlog
        stream.close() // releases what was read ahead
        val freed = pool.available == pool.buffers
        io.perform(io.backend.ops.close(in)) // the next stream's read fails on the closed handle
        val failed =
          try {
            SimpleIO.drain(SimpleIO.chunks(in, pool, backlog = 2))
            false
          } catch case _: IOException => true
        s"${first.exists(_ > 0)} $freed $failed ${pool.available == pool.buffers}"
      } finally {
        io.perform(io.backend.ops.close(out))
        pool.close()
      }
    }
    assertEquals(outcome, "true true true true")
  }
}
