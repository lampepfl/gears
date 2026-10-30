package gears.io.higherlevel

import gears.async.Async
import gears.async.Future
import gears.async.io.IpAddress
import gears.async.io.testkit.ProgramSuite

import java.io.IOException
import java.nio.charset.StandardCharsets

/** Checks of the higher level, run over every backend by the demos' suites: what the lowest level is for. */
trait HigherLevelChecks extends ProgramSuite {

  test("a higher level over perform: result-returning reads, accept and resolve") {
    val outcome = running { io ?=>
      val (in, out) = pipe(io)
      try {
        val buf = arena.allocate(16)
        val writer = Future {
          HigherLevel.writeAll(out, arena.of("wrapped".getBytes(StandardCharsets.UTF_8)))
          io.perform(io.backend.ops.close(out))
        }
        val got = HigherLevel.read(in, buf)
        writer.await
        val atEnd = HigherLevel.read(in, buf)
        val resolved = HigherLevel.resolve("localhost").nonEmpty
        s"$got ${text(buf)} $atEnd $resolved"
      } finally io.perform(io.backend.ops.close(in))
    }
    assertEquals(outcome, "7 wrapped 0 true")
  }

  test("a graceful close delivers the whole reply to a client that sent more than the server read") {
    val port = freePort()
    val replySize = 256 * 1024
    val received = running { io ?=>
      val address = IpAddress.IPv4("127.0.0.1", port)
      val listener = HigherLevel.listen(address)
      try {
        val server = Future {
          val client = HigherLevel.accept(listener)
          val scratch = arena.allocate(4096)
          scratch.limit(4)
          HigherLevel.read(client, scratch) // a little of the request: the rest is still unread when it replies
          HigherLevel.writeAll(client, arena.allocate(replySize))
          HigherLevel.closeGracefully(client, scratch) // closing outright would reset, and could cut the reply short
        }
        val socket = HigherLevel.connect(address)
        try {
          HigherLevel.writeAll(socket, arena.of(("x" * 65536).getBytes(StandardCharsets.UTF_8)))
          HigherLevel.shutdownOutput(socket)
          val buf = arena.allocate(65536)
          var total = 0L
          var got = 1
          while got > 0 do {
            buf.clear()
            got = HigherLevel.read(socket, buf)
            total += got
          }
          server.await
          total
        } finally io.perform(io.backend.ops.close(socket))
      } finally io.perform(io.backend.ops.close(listener))
    }
    assertEquals(received, replySize.toLong)
  }

  test("a higher level over perform: a stream of chunks consumed as a gears channel, with back-pressure") {
    val total = 1 << 20
    val received = running { io ?=>
      val pool = io.backend.pool(bufferSize = 4096, buffers = 4) // a backlog of two, one being sent, one being read
      val (in, out) = pipe(io)
      try {
        val writer = Future {
          val buf = arena.allocate(8192)
          var sent = 0
          while sent < total do {
            buf.clear().limit(math.min(8192, total - sent))
            HigherLevel.writeAll(out, buf)
            sent += buf.limit()
          }
          io.perform(io.backend.ops.close(out))
        }
        val received = HigherLevel.drain(HigherLevel.chunks(in, pool, backlog = 2))
        writer.await
        received
      } finally {
        io.perform(io.backend.ops.close(in))
        pool.close()
      }
    }
    assertEquals(received, total.toLong)
  }

  test("a higher level over perform: a stream closed early releases its chunks, and one that fails rethrows") {
    val outcome = running { io ?=>
      val pool = io.backend.pool(bufferSize = 1024, buffers = 4)
      val (in, out) = pipe(io)
      try {
        HigherLevel.writeAll(out, arena.of(("x" * 3000).getBytes(StandardCharsets.UTF_8)))
        val stream = HigherLevel.chunks(in, pool, backlog = 2)
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
            HigherLevel.drain(HigherLevel.chunks(in, pool, backlog = 2))
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
