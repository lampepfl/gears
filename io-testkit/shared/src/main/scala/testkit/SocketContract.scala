package gears.async.io
package testkit

import gears.async.Async
import gears.async.Future

import java.nio.charset.StandardCharsets

/** What a backend with IP sockets of both transports must do: the checks that use both, beside `IpStreamContract` and
  * `IpDatagramContract`.
  */
trait SocketContract extends ContractSuite {
  val backend: Backend & IpSockets

  /** A listener on `address`, listening, from the `listen` op, whose failure is thrown. */
  private def ipListen(io: Io[backend.type], address: IpAddress)(using Async): backend.Listener = {
    val into = Slot[backend.Listener]()
    opening(io, into, io.backend.ops.listen(address, into))
  }

  /** A datagram socket bound to `address`, from the `bindDatagram` op, whose failure is thrown. */
  private def ipBind(io: Io[backend.type], address: IpAddress)(using Async): backend.BoundDatagram = {
    val into = Slot[backend.BoundDatagram]()
    opening(io, into, io.backend.ops.bindDatagram(address, into))
  }

  test("an address is checked as the op is built: numeric, of its case's family, with no scope, its port in range") {
    val ops = backend.ops
    val bad = List(
      IpAddress.IPv4("1.2.3", 80),
      IpAddress.IPv4("1.2.3.256", 80),
      IpAddress.IPv4("localhost", 80),
      IpAddress.IPv4("::1", 80),
      IpAddress.IPv6("127.0.0.1", 80),
      IpAddress.IPv6("fe80::1%en0", 80),
      IpAddress.IPv6("[::1]", 80),
      IpAddress.IPv4("127.0.0.1", 65536),
      IpAddress.IPv4("127.0.0.1", -1)
    )
    for address <- bad do {
      intercept[IllegalArgumentException](ops.connect(address, Slot[backend.Connection]()))
      intercept[IllegalArgumentException](ops.listen(address, Slot[backend.Listener]()))
      intercept[IllegalArgumentException](ops.bindDatagram(address, Slot[backend.BoundDatagram]()))
      intercept[IllegalArgumentException](ops.connectDatagram(null, address, Slot[backend.ConnectedDatagram]()))
    }
    intercept[IllegalArgumentException](
      ops.connectDatagram(IpAddress.IPv4("127.0.0.1", 0), IpAddress.IPv6("::1", 9), Slot[backend.ConnectedDatagram]())
    )
  }

  test(
    "an IPv6 socket on :: also takes IPv4: a listener accepts an IPv4 client, and a datagram socket replies to one"
  ) {
    running { io ?=>
      val (listener, port) = onFreePort(port => ipListen(io, IpAddress.IPv6("::", port)))
      try {
        val accepted = Slot[backend.Connection]()
        val accepting = Future(opening(io, accepted, io.backend.ops.accept(listener, accepted)))
        val into = Slot[backend.Connection]()
        val client = opening(io, into, io.backend.ops.connect(IpAddress.IPv4("127.0.0.1", port), into))
        close(io, client)
        close(io, accepting.await)
      } finally close(io, listener)

      val (server, serverPort) = onFreePort(port => ipBind(io, IpAddress.IPv6("::", port)))
      try {
        val client = ipBind(io, IpAddress.IPv4("127.0.0.1", 0))
        try {
          assertEquals(
            attempt(io, io.backend.ops.send(client, bytes("v4"), IpAddress.IPv4("127.0.0.1", serverPort))),
            null
          )
          val from = Slot[IpAddress]()
          assertEquals(attempt(io, io.backend.ops.receive(server, allocate(16), from)), null)
          val sender = from.clear()
          sender match {
            case IpAddress.IPv4(host, _) => assertEquals(host, "127.0.0.1", "an IPv4 sender, not IPv4-mapped")
            case other                   => fail(s"an IPv4 sender, not IPv4-mapped: $other")
          }
          assertEquals(attempt(io, io.backend.ops.send(server, bytes("back"), sender)), null)
          val buf = allocate(16)
          assertEquals(attempt(io, io.backend.ops.receive(client, buf, Slot[IpAddress]())), null)
          assertEquals(StandardCharsets.UTF_8.decode(buf).toString, "back")
        } finally close(io, client)
      } finally close(io, server)
    }
  }

  test("connect, listen, datagram and shutdownOutput are one-shot: performed again, they throw IllegalStateException") {
    running { io ?=>
      val into = Slot[backend.Listener]()
      val (probe, port) = onFreePort(port => ipListen(io, IpAddress.IPv4("127.0.0.1", port)))
      close(io, probe)
      val listening = io.backend.ops.listen(IpAddress.IPv4("127.0.0.1", port), into)
      assertEquals(attempt(io, listening), null)
      val listener = into.clear()
      intercept[IllegalStateException](io.perform(listening))
      val accepted = Slot[backend.Connection]()
      val accepting = Future(io.perform(io.backend.ops.accept(listener, accepted)))
      val connected = Slot[backend.Connection]()
      val connecting = io.backend.ops.connect(IpAddress.IPv4("127.0.0.1", port), connected)
      assertEquals(attempt(io, connecting), null)
      intercept[IllegalStateException](io.perform(connecting))
      val client = connected.clear()
      val shutting = io.backend.ops.shutdownOutput(client)
      assertEquals(attempt(io, shutting), null)
      intercept[IllegalStateException](io.perform(shutting))
      val bound = Slot[backend.BoundDatagram]()
      val binding = io.backend.ops.bindDatagram(IpAddress.IPv4("127.0.0.1", 0), bound)
      assertEquals(attempt(io, binding), null)
      intercept[IllegalStateException](io.perform(binding))
      close(io, bound.clear())
      close(io, client)
      accepting.await
      close(io, accepted.clear())
      close(io, listener)
    }
  }
}
