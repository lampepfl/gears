package gears.io.testkit

import gears.async.Future
import gears.async.io.IpAddress
import gears.async.io.IpSocketAddress
import gears.async.io.testkit.IpStreamFixture

import java.nio.charset.StandardCharsets

/** Public API IP stream helpers, requiring only IpStreams. */
trait SimpleIOIpStreamChecks extends IpStreamFixture {
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
