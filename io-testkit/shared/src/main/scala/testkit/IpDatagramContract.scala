package gears.async.io
package testkit

import gears.async.Async
import gears.async.Future

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*
import scala.util.Failure

/** What a backend with IP datagram sockets must do, with or without IP stream sockets: checked through `perform`, as
  * programs over the backend (see `ContractSuite`).
  */
trait IpDatagramContract extends ContractSuite {
  val backend: Backend & IpDatagrams

  /** A datagram socket bound to `local`, from the `bindDatagram` op, whose failure is thrown. */
  protected def bindDatagram(io: Io[backend.type], local: IpAddress)(using Async): backend.BoundDatagram = {
    val into = Slot[backend.BoundDatagram]()
    opening(io, into, backend.ops.bindDatagram(local, into))
  }

  /** A datagram socket connected to `remote`, from the `connectDatagram` op, whose failure is thrown. */
  protected def connectDatagram(io: Io[backend.type], local: IpAddress | Null, remote: IpAddress)(using
      Async
  ): backend.ConnectedDatagram = {
    val into = Slot[backend.ConnectedDatagram]()
    opening(io, into, backend.ops.connectDatagram(local, remote, into))
  }

  /** The packet in `buf`, received and flipped, as text. */
  private def packet(buf: java.nio.ByteBuffer): String = StandardCharsets.UTF_8.decode(buf).toString

  test("an IPv4 datagram goes to a bound socket, which learns its sender, and its reply to the connected client") {
    running { io ?=>
      val (server, port) = onFreePort(port => bindDatagram(io, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val client = connectDatagram(io, null, IpAddress.IPv4("127.0.0.1", port))
        try {
          val buf = allocate(64)
          val from = Slot[IpAddress]()
          val received = Future(io.perform(backend.ops.receive(server, buf, from)))
          io.perform(backend.ops.send(client, bytes("datagram")))
          received.await
          assertEquals(packet(buf), "datagram")
          val sender = from.clear()
          sender match {
            case IpAddress.IPv4(host, senderPort) =>
              assertEquals(host, "127.0.0.1")
              assert(senderPort > 0)
            case other => fail(s"unexpected sender $other")
          }
          io.perform(backend.ops.send(server, bytes("reply"), sender))
          val reply = allocate(64)
          io.perform(backend.ops.receive(client, reply))
          assertEquals(packet(reply), "reply")
        } finally close(io, client)
      } finally close(io, server)
    }
  }

  test("datagram sends: never empty when connected, empty ones from a bound socket, and no lost packets") {
    running { io ?=>
      val (server, port) = onFreePort(port => bindDatagram(io, IpAddress.IPv4("127.0.0.1", port)))
      val serverAddress = IpAddress.IPv4("127.0.0.1", port)
      try {
        val connected = connectDatagram(io, null, serverAddress)
        val unconnected = bindDatagram(io, IpAddress.IPv4("127.0.0.1", 0))
        try {
          def refused(failure: Throwable | Null)(using munit.Location): Unit =
            assert(failure.isInstanceOf[IllegalArgumentException], String.valueOf(failure))

          /** The next packet the server receives, as text. */
          def received()(using munit.Location): String = {
            val buf = allocate(16)
            io.perform(backend.ops.receive(server, buf, Slot[IpAddress]()))
            packet(buf)
          }
          val empty = allocate(4)
          empty.limit(0)
          refused(attempt(io, backend.ops.send(connected, empty)))
          io.perform(backend.ops.send(connected, bytes("to-peer")))
          assertEquals(received(), "to-peer")
          io.perform(backend.ops.send(unconnected, empty, serverAddress))
          assertEquals(received(), "", "an empty packet from a socket that is not connected")
          io.perform(backend.ops.send(connected, bytes("kept")))
          val none = allocate(4)
          none.limit(0)
          refused(attempt(io, backend.ops.receive(server, none.slice(), Slot[IpAddress]())))
          assertEquals(received(), "kept", "the refused receive took no packet")
        } finally {
          close(io, connected)
          close(io, unconnected)
        }
      } finally close(io, server)
    }
  }

  test("a bound socket sends to peers that have not sent first from its own port, on its own port 0 or not") {
    running { io ?=>
      val (first, firstPort) = onFreePort(port => bindDatagram(io, IpAddress.IPv4("127.0.0.1", port)))
      val (second, secondPort) = onFreePort(port => bindDatagram(io, IpAddress.IPv4("127.0.0.1", port)))
      try {
        def senderOf(to: backend.BoundDatagram)(using munit.Location): Int = {
          val from = Slot[IpAddress]()
          io.perform(backend.ops.receive(to, allocate(16), from))
          from.clear() match {
            case IpAddress.IPv4(_, port) => port
            case other                   => fail(s"unexpected sender $other")
          }
        }
        val (fixed, fixedPort) = onFreePort(port => bindDatagram(io, IpAddress.IPv4("127.0.0.1", port)))
        try {
          io.perform(backend.ops.send(fixed, bytes("one"), IpAddress.IPv4("127.0.0.1", firstPort)))
          assertEquals(senderOf(first), fixedPort)
          io.perform(backend.ops.send(fixed, bytes("two"), IpAddress.IPv4("127.0.0.1", secondPort)))
          assertEquals(senderOf(second), fixedPort)
        } finally close(io, fixed)
        val any = bindDatagram(io, IpAddress.IPv4("127.0.0.1", 0))
        try {
          io.perform(backend.ops.send(any, bytes("one"), IpAddress.IPv4("127.0.0.1", firstPort)))
          val port = senderOf(first)
          io.perform(backend.ops.send(any, bytes("two"), IpAddress.IPv4("127.0.0.1", secondPort)))
          assertEquals(senderOf(second), port, "one port for every peer")
        } finally close(io, any)
      } finally {
        close(io, first)
        close(io, second)
      }
    }
  }

  test("a packet lost on a socket that is not connected fails no send, to its peer or another") {
    running { io ?=>
      val (server, port) = onFreePort(port => bindDatagram(io, IpAddress.IPv4("127.0.0.1", port)))
      try {
        // A port nothing listens on, which loopback answers with port unreachable.
        val (gone, gonePort) = onFreePort(port => bindDatagram(io, IpAddress.IPv4("127.0.0.1", port)))
        close(io, gone)
        val nowhere = IpAddress.IPv4("127.0.0.1", gonePort)
        val unconnected = bindDatagram(io, IpAddress.IPv4("127.0.0.1", 0))
        try {
          for _ <- 1 to 3 do {
            io.perform(backend.ops.send(unconnected, bytes("lost"), nowhere))
            io.perform(backend.ops.timer(20)) // for the unreachable to come back
          }
          io.perform(backend.ops.send(unconnected, bytes("after"), IpAddress.IPv4("127.0.0.1", port)))
          val buf = allocate(16)
          io.perform(backend.ops.receive(server, buf, Slot[IpAddress]()))
          assertEquals(packet(buf), "after")
          io.perform(backend.ops.send(unconnected, bytes("again"), nowhere))
        } finally close(io, unconnected)
      } finally close(io, server)
    }
  }

  test(
    "over IPv6 a datagram's sender is written as inet_ntop does, whatever the spelling its peer was connected by"
  ) {
    running { io ?=>
      val (server, port) = onFreePort(port => bindDatagram(io, IpAddress.IPv6("::1", port)))
      try {
        val client = connectDatagram(io, null, IpAddress.IPv6("0:0:0:0:0:0:0:1", port))
        try {
          io.perform(backend.ops.send(client, bytes("v6")))
          val buf = allocate(16)
          val from = Slot[IpAddress]()
          io.perform(backend.ops.receive(server, buf, from))
          assertEquals(packet(buf), "v6")
          from.clear() match {
            case IpAddress.IPv6(host, _) => assertEquals(host, "::1")
            case other                   => fail(s"an IPv6 sender: $other")
          }
        } finally close(io, client)
      } finally close(io, server)
    }
  }

  test("a packet larger than the receive buffer fills it, the rest dropped, and the next packet arrives whole") {
    running { io ?=>
      val (server, port) = onFreePort(port => bindDatagram(io, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val client = connectDatagram(io, null, IpAddress.IPv4("127.0.0.1", port))
        try {
          io.perform(backend.ops.send(client, bytes("0123456789abcdef")))
          io.perform(backend.ops.send(client, bytes("next")))
          def received(capacity: Int)(using munit.Location): String = {
            val buf = allocate(capacity)
            io.perform(backend.ops.receive(server, buf, Slot[IpAddress]()))
            packet(buf)
          }
          assertEquals(received(4), "0123", "the bytes that fitted, the rest dropped")
          assertEquals(received(16), "next", "the next packet, whole")
        } finally close(io, client)
      } finally close(io, server)
    }
  }

  test("datagrams not received are dropped once the socket's buffer is full, not queued without bound") {
    running { io ?=>
      val (server, port) = onFreePort(port => bindDatagram(io, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val client = connectDatagram(io, null, IpAddress.IPv4("127.0.0.1", port))
        try {
          val sent = 2000 // of 8 KiB: 16 MiB, beyond any socket's receive buffer
          val outgoing = allocate(8192)
          for _ <- 1 to sent do {
            outgoing.clear()
            io.perform(backend.ops.send(client, outgoing))
          }
          val buf = allocate(8192)
          var received = 0
          var drained = false
          while !drained do {
            buf.clear()
            try {
              io.perform(backend.ops.receive(server, buf, Slot[IpAddress]()), 200.millis)
              received += 1
            } catch case _: TimeoutException => drained = true
          }
          assert(received > 0 && received < sent, s"$received of $sent received")
          io.perform(backend.ops.send(client, bytes("after")))
          buf.clear()
          io.perform(backend.ops.receive(server, buf, Slot[IpAddress]()))
          assertEquals(packet(buf), "after", "the socket goes on once drained")
        } finally close(io, client)
      } finally close(io, server)
    }
  }

  test("a datagram socket's op completes once it is bound, and fails, leaving its slot empty, on an address in use") {
    running { io ?=>
      val (server, port) = onFreePort(port => bindDatagram(io, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val into = Slot[backend.BoundDatagram]()
        val failure = attempt(io, backend.ops.bindDatagram(IpAddress.IPv4("127.0.0.1", port), into))
        assert(failure.isInstanceOf[IOException], String.valueOf(failure))
        intercept[IllegalStateException](into.clear())
        close(io, connectDatagram(io, null, IpAddress.IPv4("127.0.0.1", port)))
      } finally close(io, server)
    }
  }

  test("datagram sockets know their addresses: a bound one on port 0 its port, a connected one its own and its peer") {
    running { io ?=>
      val bound = bindDatagram(io, IpAddress.IPv4("127.0.0.1", 0))
      try {
        val local = backend.ops.localAddress(bound)
        local match {
          case IpAddress.IPv4("127.0.0.1", port) => assert(port > 0, local)
          case other                             => fail(s"a bound socket on port 0's address: $other")
        }
        val client = connectDatagram(io, null, local)
        try {
          assertEquals(backend.ops.peerAddress(client), local)
          val clientAddress = backend.ops.localAddress(client)
          io.perform(backend.ops.send(client, bytes("who")))
          val from = Slot[IpAddress]()
          io.perform(backend.ops.receive(bound, allocate(16), from))
          assertEquals(from.clear(), clientAddress, "its sender is the address it reports")
        } finally close(io, client)
      } finally close(io, bound)
      intercept[IOException](backend.ops.localAddress(bound))
    }
  }

  test("a datagram socket refuses a stream socket's options, and takes a buffer size where the backend has it") {
    intercept[IllegalArgumentException](
      backend.ops
        .bindDatagram(IpAddress.IPv4("127.0.0.1", 0), SocketOptions.default.set(SocketOption.NoDelay, true), Slot())
    )
    if backend.ops.supports(SocketOption.ReceiveBuffer, SocketOption.Kind.BoundDatagram) then
      running { io ?=>
        val bound = boundWith(io, SocketOptions.default.set(SocketOption.ReceiveBuffer, 65536))
        try {
          assert(backend.ops.option(bound, SocketOption.ReceiveBuffer) >= 65536)
          intercept[IllegalArgumentException](backend.ops.option(bound, SocketOption.NoDelay))
        } finally close(io, bound)
      }
    else
      intercept[IllegalArgumentException](
        backend.ops.bindDatagram(
          IpAddress.IPv4("127.0.0.1", 0),
          SocketOptions.default.set(SocketOption.ReceiveBuffer, 65536),
          Slot()
        )
      )
  }

  /** A bound socket opening with `options` on loopback, port 0. */
  private def boundWith(io: Io[backend.type], options: SocketOptions)(using Async): backend.BoundDatagram = {
    val into = Slot[backend.BoundDatagram]()
    opening(io, into, backend.ops.bindDatagram(IpAddress.IPv4("127.0.0.1", 0), options, into))
  }

  /** The loopback interface's MTU, if the platform tells it. */
  private def loopbackMtu: Int =
    scala.jdk.CollectionConverters
      .EnumerationHasAsScala(java.net.NetworkInterface.getNetworkInterfaces)
      .asScala
      .find(_.isLoopback)
      .map(_.getMTU)
      .getOrElse(-1)

  test("DontFragment given as a datagram socket opens is its own, read back, and changed where the backend can") {
    assume(
      backend.ops.supports(SocketOption.DontFragment, SocketOption.Kind.BoundDatagram),
      "this backend has no don't-fragment option"
    )
    running { io ?=>
      val plain = boundWith(io, SocketOptions.default)
      val dontFragment = boundWith(io, SocketOptions.default.set(SocketOption.DontFragment, true))
      try {
        assertEquals(backend.ops.option(plain, SocketOption.DontFragment), false, "the system's, off")
        assertEquals(backend.ops.option(dontFragment, SocketOption.DontFragment), true)
        if backend.ops.canChange(SocketOption.DontFragment, SocketOption.Kind.BoundDatagram) then {
          backend.ops.setOption(plain, SocketOption.DontFragment, true)
          assertEquals(backend.ops.option(plain, SocketOption.DontFragment), true)
        } else intercept[IllegalArgumentException](backend.ops.setOption(plain, SocketOption.DontFragment, true))
      } finally {
        close(io, plain)
        close(io, dontFragment)
      }
    }
  }

  test("with DontFragment a datagram larger than the path's MTU fails to send, where without it it goes fragmented") {
    assume(
      backend.ops.supports(SocketOption.DontFragment, SocketOption.Kind.BoundDatagram) && backend.ops
        .supports(SocketOption.SendBuffer, SocketOption.Kind.BoundDatagram)
    )
    val mtu = loopbackMtu
    assume(mtu > 0 && mtu + 1000 <= 65507, s"loopback's MTU, $mtu, leaves no room for a datagram larger than it")
    val size = mtu + 1000
    val room = SocketOptions.default.set(SocketOption.SendBuffer, 2 * size) // past the system's largest datagram
    running { io ?=>
      val receiver = boundWith(io, SocketOptions.default)
      val to = backend.ops.localAddress(receiver)
      val fragmenting = boundWith(io, room)
      val whole = boundWith(io, room.set(SocketOption.DontFragment, true))
      try {
        assertEquals(attempt(io, backend.ops.send(fragmenting, allocate(size), to)), null, "sent fragmented")
        val failure = attempt(io, backend.ops.send(whole, allocate(size), to))
        assert(failure.isInstanceOf[IOException], s"a datagram of $size bytes, MTU $mtu: $failure")
      } finally {
        close(io, receiver)
        close(io, fragmenting)
        close(io, whole)
      }
    }
  }

  test("ReusePort lets bound datagram sockets that set it share a port, refusing one that does not") {
    assume(
      backend.ops.supports(SocketOption.ReusePort, SocketOption.Kind.BoundDatagram),
      "this backend has no reuse-port option"
    )
    val reuse = SocketOptions.default.set(SocketOption.ReusePort, true)
    running { io ?=>
      val first = boundWith(io, reuse)
      try {
        assertEquals(backend.ops.option(first, SocketOption.ReusePort), true)
        val port = backend.ops.localAddress(first)
        val into = Slot[backend.BoundDatagram]()
        val second = opening(io, into, backend.ops.bindDatagram(port, reuse, into))
        try {
          val refused = Slot[backend.BoundDatagram]()
          val failure = attempt(io, backend.ops.bindDatagram(port, refused))
          assert(failure.isInstanceOf[IOException], s"one without it: $failure")
          intercept[IllegalArgumentException](backend.ops.setOption(second, SocketOption.ReusePort, false))
        } finally close(io, second)
      } finally close(io, first)
    }
  }

  test("a connected datagram socket takes no ReusePort, and no backend changes it on an open socket") {
    if backend.ops.supports(SocketOption.ReusePort, SocketOption.Kind.BoundDatagram) then
      intercept[IllegalArgumentException](
        backend.ops.connectDatagram(
          null,
          IpAddress.IPv4("127.0.0.1", 9),
          SocketOptions.default.set(SocketOption.ReusePort, true),
          Slot()
        )
      )
    assert(!backend.ops.canChange(SocketOption.ReusePort, SocketOption.Kind.BoundDatagram))
  }

  test(
    "a datagram sent from several buffers is one packet, from a bound socket or a connected one, never empty there"
  ) {
    running { io ?=>
      val receiver = bindDatagram(io, IpAddress.IPv4("127.0.0.1", 0))
      val to = backend.ops.localAddress(receiver)
      val bound = bindDatagram(io, IpAddress.IPv4("127.0.0.1", 0))
      val connected = connectDatagram(io, null, to)
      try {
        def received()(using munit.Location): String = {
          val buf = allocate(64)
          io.perform(backend.ops.receive(receiver, buf, Slot[IpAddress]()))
          packet(buf)
        }
        io.perform(backend.ops.send(bound, Array(bytes("one "), bytes("packet")), to))
        assertEquals(received(), "one packet")
        io.perform(backend.ops.send(connected, Array(bytes("two "), bytes(""), bytes("parts"))))
        assertEquals(received(), "two parts")
        val empty = allocate(1)
        empty.limit(0)
        val failure = attempt(io, backend.ops.send(connected, Array(empty, empty.duplicate())))
        assert(failure.isInstanceOf[IllegalArgumentException], String.valueOf(failure))
      } finally {
        close(io, receiver)
        close(io, bound)
        close(io, connected)
      }
    }
  }

  test(
    "receives waiting at once on a datagram socket take turns, from any future, each its own packet; one cancelled, " +
      "or timed out, waiting gives up its place"
  ) {
    running { io ?=>
      val server = bindDatagram(io, IpAddress.IPv4("127.0.0.1", 0))
      val client = connectDatagram(io, null, backend.ops.localAddress(server))
      try {
        // Not a tuple: matching one with a `ByteBuffer` in it has the compiler look into JDK 27's sealed buffers.
        final class Receiving(timeout: FiniteDuration | Null) {
          val buf = allocate(16)
          private val op = backend.ops.receive(server, buf, Slot[IpAddress]())
          val future = Future(if timeout == null then io.perform(op) else io.perform(op, timeout))
          io.perform(backend.ops.timer(20)) // so that each begins waiting before the next, in this order
        }
        val first = Receiving(null)
        val leaving = Receiving(null)
        val late = Receiving(50.millis)
        val second = Receiving(null)
        assert(List(first, leaving, second).forall(_.future.poll().isEmpty), "they wait")
        leaving.future.cancel()
        leaving.future.awaitResult match {
          case Failure(_: CancellationException) => ()
          case other                             => fail(s"cancelled: $other")
        }
        late.future.awaitResult match {
          case Failure(_: TimeoutException) => ()
          case other                        => fail(s"timed out: $other")
        }
        for text <- List("one", "two") do io.perform(backend.ops.send(client, bytes(text)))
        first.future.await
        second.future.await
        assertEquals((packet(first.buf), packet(second.buf)), ("one", "two"))
      } finally {
        close(io, client)
        close(io, server)
      }
    }
  }

  test("sends performed at once on a datagram socket, from several futures, take turns, each its whole packet") {
    running { io ?=>
      val receiver = bindDatagram(io, IpAddress.IPv4("127.0.0.1", 0))
      val to = backend.ops.localAddress(receiver)
      val sender = bindDatagram(io, IpAddress.IPv4("127.0.0.1", 0))
      try {
        val packets = (1 to 6).map(i => s"packet $i")
        val sends = packets.map(text => Future(io.perform(backend.ops.send(sender, bytes(text), to))))
        sends.foreach(_.await)
        val received = packets.map { _ =>
          val buf = allocate(16)
          io.perform(backend.ops.receive(receiver, buf, Slot[IpAddress]()))
          packet(buf)
        }
        // Futures performing at once have no order among them: each packet arrives whole, once.
        assertEquals(received.sorted, packets.sorted)
      } finally {
        close(io, receiver)
        close(io, sender)
      }
    }
  }

  test("a datagram is sent from and received into any buffer, on the heap or not") {
    running { io ?=>
      val receiver = bindDatagram(io, IpAddress.IPv4("127.0.0.1", 0))
      val to = backend.ops.localAddress(receiver)
      val sender = connectDatagram(io, null, to)
      try {
        def heap(text: String) = java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8))
        def received()(using munit.Location): String = {
          val buf = java.nio.ByteBuffer.allocate(64)
          io.perform(backend.ops.receive(receiver, buf, Slot[IpAddress]()))
          packet(buf)
        }
        io.perform(backend.ops.send(sender, heap("on the heap")))
        assertEquals(received(), "on the heap")
        io.perform(backend.ops.send(sender, heap("read-only").asReadOnlyBuffer()))
        assertEquals(received(), "read-only")
        io.perform(backend.ops.send(sender, Array(heap("mixed "), bytes("buffers"))))
        assertEquals(received(), "mixed buffers")
      } finally {
        close(io, receiver)
        close(io, sender)
      }
    }
  }
}
