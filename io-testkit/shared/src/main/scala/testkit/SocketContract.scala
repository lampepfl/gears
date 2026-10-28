package gears.async.io
package testkit

import gears.async.io.internal.ReactorBackend

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeoutException

/** What a backend with IP sockets of both transports must do: `IpStreamContract` and `IpDatagramContract`, and the
  * checks that use both.
  */
trait SocketContract extends IpStreamContract with IpDatagramContract {
  val backend: ReactorBackend & Blocking & Pipes & IpSockets

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
      intercept[IllegalArgumentException](ops.connect(address, Slot[Connection]()))
      intercept[IllegalArgumentException](ops.listen(address, Slot[Listener]()))
      intercept[IllegalArgumentException](ops.bindDatagram(address, Slot[BoundDatagram]()))
      intercept[IllegalArgumentException](ops.connectDatagram(null, address, Slot[ConnectedDatagram]()))
    }
    intercept[IllegalArgumentException](
      ops.connectDatagram(IpAddress.IPv4("127.0.0.1", 0), IpAddress.IPv6("::1", 9), Slot[ConnectedDatagram]())
    )
  }

  test(
    "an IPv6 socket on :: also takes IPv4: a listener accepts an IPv4 client, and a datagram socket replies to one"
  ) {
    withReactor { r =>
      val (listener, port) = onFreePort(port => listen(r, IpAddress.IPv6("::", port)))
      try {
        val (connected, into) = startConnect(r, IpAddress.IPv4("127.0.0.1", port))
        val accepted = Slot[Connection]()
        val acceptedOutcome = Outcome()
        r.submit(backend.ops.accept(listener, accepted), acceptedOutcome)
        turnUntil(r)(connected.finished && acceptedOutcome.finished)
        assertEquals(connected.completed, 1, String.valueOf(connected.failure))
        close(r, into.clear())
        close(r, accepted.clear())
      } finally close(r, listener)

      val (server, serverPort) = onFreePort(port => bindDatagram(r, IpAddress.IPv6("::", port)))
      try {
        val client = bindDatagram(r, IpAddress.IPv4("127.0.0.1", 0))
        try {
          assertEquals(run(r, backend.ops.send(client, bytes("v4"), IpAddress.IPv4("127.0.0.1", serverPort))), null)
          val from = Slot[IpAddress]()
          assertEquals(run(r, backend.ops.receive(server, allocate(16), from)), null)
          val sender = from.clear()
          sender match {
            case IpAddress.IPv4(host, _) => assertEquals(host, "127.0.0.1", "an IPv4 sender, not IPv4-mapped")
            case other                   => fail(s"an IPv4 sender, not IPv4-mapped: $other")
          }
          assertEquals(run(r, backend.ops.send(server, bytes("back"), sender)), null)
          val buf = allocate(16)
          assertEquals(run(r, backend.ops.receive(client, buf, Slot[IpAddress]())), null)
          assertEquals(StandardCharsets.UTF_8.decode(buf).toString, "back")
        } finally close(r, client)
      } finally close(r, server)
    }
  }

  test("connect, listen, datagram and shutdownOutput are one-shot: submitted again, they throw IllegalStateException") {
    withReactor { r =>
      val into = Slot[Listener]()
      val (probe, port) = onFreePort(port => listen(r, IpAddress.IPv4("127.0.0.1", port)))
      close(r, probe)
      val listening = backend.ops.listen(IpAddress.IPv4("127.0.0.1", port), into)
      assertEquals(run(r, listening), null)
      val listener = into.clear()
      intercept[IllegalStateException](r.submit(listening, Outcome()))
      val connected = Slot[Connection]()
      val connecting = backend.ops.connect(IpAddress.IPv4("127.0.0.1", port), connected)
      val accepted = Slot[Connection]()
      r.submit(backend.ops.accept(listener, accepted), Outcome())
      assertEquals(run(r, connecting), null)
      intercept[IllegalStateException](r.submit(connecting, Outcome()))
      val client = connected.clear()
      val shutting = backend.ops.shutdownOutput(client)
      assertEquals(run(r, shutting), null)
      intercept[IllegalStateException](r.submit(shutting, Outcome()))
      val bound = Slot[BoundDatagram]()
      val binding = backend.ops.bindDatagram(IpAddress.IPv4("127.0.0.1", 0), bound)
      assertEquals(run(r, binding), null)
      intercept[IllegalStateException](r.submit(binding, Outcome()))
      close(r, bound.clear())
      close(r, client)
      turnUntil(r)(try {
        close(r, accepted.clear()); true
      } catch case _: IllegalStateException => false)
      close(r, listener)
    }
  }
}
