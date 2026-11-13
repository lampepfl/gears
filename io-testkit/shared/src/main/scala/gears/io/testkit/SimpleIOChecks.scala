package gears.io.testkit

import gears.async.Future
import gears.async.io.Backend
import gears.async.io.IpAddress
import gears.async.io.IpSocketAddress
import gears.async.io.Resolving
import gears.async.io.testkit.ContractSuite
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
        var got = 0
        var read = SimpleIO.read(in, buf)
        while read > 0 do {
          got += read
          read = SimpleIO.read(in, buf)
        }
        writer.await
        val atEnd = read
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
        val writer = Future(SimpleIO.writeAll(out, arena.of(("x" * 3000).getBytes(StandardCharsets.UTF_8))))
        val stream = SimpleIO.chunks(in, pool, backlog = 2)
        val first = stream.read().map { chunk =>
          val n = chunk.buffer.remaining()
          chunk.release()
          n
        }
        io.perform(io.backend.ops.timer(20)) // lets the reader fill the window and the backlog
        stream.close() // releases what was read ahead
        writer.cancel()
        writer.awaitResult
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

/** Public API IP stream helpers, requiring only IpStreams. */
trait SimpleIOIpStreamChecks extends ContractSuite {
  val backend: Backend { val ops: gears.async.io.IpStreams }

  test("a graceful close delivers the whole reply to a client that sent more than the server read") {
    val replySize = 256 * 1024
    val received = running { io ?=>
      val listener = SimpleIO.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0))
      val address = io.backend.ops.localAddress(listener).asInstanceOf[IpSocketAddress]
      try {
        val server = Future {
          val client = SimpleIO.accept(listener)
          val scratch = arena.allocate(4096)
          scratch.limit(4)
          SimpleIO.read(client, scratch) // a little of the request: the rest is still unread when it replies
          SimpleIO.writeAll(client, arena.allocate(replySize))
          SimpleIO.closeGracefully(client, scratch) // closing outright would reset, and could cut the reply short
        }
        val socket = SimpleIO.connect(address)
        try {
          SimpleIO.writeAll(socket, arena.of(("x" * 65536).getBytes(StandardCharsets.UTF_8)))
          SimpleIO.shutdownOutput(socket)
          val buf = arena.allocate(65536)
          var total = 0L
          var got = 1
          while got > 0 do {
            buf.clear()
            got = SimpleIO.read(socket, buf)
            total += got
          }
          server.await
          total
        } finally io.perform(io.backend.ops.close(socket))
      } finally io.perform(io.backend.ops.close(listener))
    }
    assertEquals(received, replySize.toLong)
  }
}

/** Public API resolution helpers, requiring only Resolving. */
trait SimpleIOResolveChecks extends ContractSuite {
  val backend: Backend { val ops: Resolving }

  test("SimpleIO resolves localhost") {
    val resolved = running { io ?=> SimpleIO.resolve("localhost") }
    assert(resolved.nonEmpty)
  }
}
