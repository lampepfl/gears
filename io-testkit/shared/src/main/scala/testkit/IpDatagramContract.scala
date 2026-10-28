package gears.async.io
package testkit

import gears.async.io.internal.ReactorBackend

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeoutException

/** What a backend with IP datagram sockets must do (see `ReactorContract`), with or without IP stream sockets. */
trait IpDatagramContract extends ReactorContract {
  val backend: ReactorBackend & Blocking & Pipes & IpDatagrams

  /** A datagram socket bound to `local`, from the `bindDatagram` op, whose failure is thrown. */
  protected def bindDatagram(r: R, local: IpAddress)(using munit.Location): BoundDatagram = {
    val into = Slot[BoundDatagram]()
    opened(r, into, backend.ops.bindDatagram(local, into))
  }

  /** A datagram socket connected to `remote`, from the `connectDatagram` op, whose failure is thrown. */
  protected def connectDatagram(r: R, local: IpAddress | Null, remote: IpAddress)(using
      munit.Location
  ): ConnectedDatagram = {
    val into = Slot[ConnectedDatagram]()
    opened(r, into, backend.ops.connectDatagram(local, remote, into))
  }

  test("an IPv4 datagram goes to a bound socket, which learns its sender, and its reply to the connected client") {
    withReactor { r =>
      val (server, port) = onFreePort(port => bindDatagram(r, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val client = connectDatagram(r, null, IpAddress.IPv4("127.0.0.1", port))
        try {
          val buf = allocate(64)
          val from = Slot[IpAddress]()
          val received = Outcome()
          r.submit(backend.ops.receive(server, buf, from), received)
          val sent = Outcome()
          r.submit(backend.ops.send(client, bytes("datagram")), sent)
          turnUntil(r)(received.finished && sent.finished)
          assertEquals(received.completed, 1, String.valueOf(received.failure))
          val copy = new Array[Byte](buf.remaining())
          buf.get(copy)
          assertEquals(new String(copy, StandardCharsets.UTF_8), "datagram")
          val sender = from.clear()
          sender match {
            case IpAddress.IPv4(host, senderPort) =>
              assertEquals(host, "127.0.0.1")
              assert(senderPort > 0)
            case other => fail(s"unexpected sender $other")
          }
          assertEquals(run(r, backend.ops.send(server, bytes("reply"), sender)), null)
          val reply = allocate(64)
          assertEquals(run(r, backend.ops.receive(client, reply)), null)
          assertEquals(StandardCharsets.UTF_8.decode(reply).toString, "reply")
        } finally close(r, client)
      } finally close(r, server)
    }
  }

  test("datagram sends: never empty when connected, empty ones from a bound socket, and no lost packets") {
    withReactor { r =>
      val (server, port) = onFreePort(port => bindDatagram(r, IpAddress.IPv4("127.0.0.1", port)))
      val serverAddress = IpAddress.IPv4("127.0.0.1", port)
      try {
        val connected = connectDatagram(r, null, serverAddress)
        val unconnected = bindDatagram(r, IpAddress.IPv4("127.0.0.1", 0))
        try {
          def refused(failure: Throwable | Null)(using munit.Location): Unit =
            assert(failure.isInstanceOf[IllegalArgumentException], String.valueOf(failure))

          /** The next packet the server receives, as text. */
          def received()(using munit.Location): String = {
            val buf = allocate(16)
            assertEquals(run(r, backend.ops.receive(server, buf, Slot[IpAddress]())), null)
            val copy = new Array[Byte](buf.remaining())
            buf.get(copy)
            new String(copy, StandardCharsets.UTF_8)
          }
          val empty = allocate(4)
          empty.limit(0)
          refused(run(r, backend.ops.send(connected, empty)))
          assertEquals(run(r, backend.ops.send(connected, bytes("to-peer"))), null)
          assertEquals(received(), "to-peer")
          assertEquals(run(r, backend.ops.send(unconnected, empty, serverAddress)), null)
          assertEquals(received(), "", "an empty packet from a socket that is not connected")
          assertEquals(run(r, backend.ops.send(connected, bytes("kept"))), null)
          val none = allocate(4)
          none.limit(0)
          refused(run(r, backend.ops.receive(server, none.slice(), Slot[IpAddress]())))
          assertEquals(received(), "kept", "the refused receive took no packet")
        } finally {
          close(r, connected)
          close(r, unconnected)
        }
      } finally close(r, server)
    }
  }

  test("a bound socket sends to peers that have not sent first from its own port, on its own port 0 or not") {
    withReactor { r =>
      val (first, firstPort) = onFreePort(port => bindDatagram(r, IpAddress.IPv4("127.0.0.1", port)))
      val (second, secondPort) = onFreePort(port => bindDatagram(r, IpAddress.IPv4("127.0.0.1", port)))
      try {
        def senderOf(to: BoundDatagram)(using munit.Location): Int = {
          val buf = allocate(16)
          val from = Slot[IpAddress]()
          assertEquals(run(r, backend.ops.receive(to, buf, from)), null)
          from.clear() match {
            case IpAddress.IPv4(_, port) => port
            case other                   => fail(s"unexpected sender $other")
          }
        }
        val (fixed, fixedPort) = onFreePort(port => bindDatagram(r, IpAddress.IPv4("127.0.0.1", port)))
        try {
          assertEquals(run(r, backend.ops.send(fixed, bytes("one"), IpAddress.IPv4("127.0.0.1", firstPort))), null)
          assertEquals(senderOf(first), fixedPort)
          assertEquals(run(r, backend.ops.send(fixed, bytes("two"), IpAddress.IPv4("127.0.0.1", secondPort))), null)
          assertEquals(senderOf(second), fixedPort)
        } finally close(r, fixed)
        val any = bindDatagram(r, IpAddress.IPv4("127.0.0.1", 0))
        try {
          assertEquals(run(r, backend.ops.send(any, bytes("one"), IpAddress.IPv4("127.0.0.1", firstPort))), null)
          val port = senderOf(first)
          assertEquals(run(r, backend.ops.send(any, bytes("two"), IpAddress.IPv4("127.0.0.1", secondPort))), null)
          assertEquals(senderOf(second), port, "one port for every peer")
        } finally close(r, any)
      } finally {
        close(r, first)
        close(r, second)
      }
    }
  }

  test("a packet lost on a socket that is not connected fails no send, to its peer or another") {
    withReactor { r =>
      val (server, port) = onFreePort(port => bindDatagram(r, IpAddress.IPv4("127.0.0.1", port)))
      try {
        // A port nothing listens on, which loopback answers with port unreachable.
        val (gone, gonePort) = onFreePort(port => bindDatagram(r, IpAddress.IPv4("127.0.0.1", port)))
        close(r, gone)
        val nowhere = IpAddress.IPv4("127.0.0.1", gonePort)
        val unconnected = bindDatagram(r, IpAddress.IPv4("127.0.0.1", 0))
        try {
          for _ <- 1 to 3 do {
            assertEquals(run(r, backend.ops.send(unconnected, bytes("lost"), nowhere)), null)
            assertEquals(run(r, backend.ops.timer(20)), null) // for the unreachable to come back
          }
          assertEquals(run(r, backend.ops.send(unconnected, bytes("after"), IpAddress.IPv4("127.0.0.1", port))), null)
          val buf = allocate(16)
          assertEquals(run(r, backend.ops.receive(server, buf, Slot[IpAddress]())), null)
          assertEquals(StandardCharsets.UTF_8.decode(buf).toString, "after")
          assertEquals(run(r, backend.ops.send(unconnected, bytes("again"), nowhere)), null)
        } finally close(r, unconnected)
      } finally close(r, server)
    }
  }

  test(
    "over IPv6 a datagram's sender is written as inet_ntop does, whatever the spelling its peer was connected by"
  ) {
    withReactor { r =>
      val (server, port) = onFreePort(port => bindDatagram(r, IpAddress.IPv6("::1", port)))
      try {
        val client = connectDatagram(r, null, IpAddress.IPv6("0:0:0:0:0:0:0:1", port))
        try {
          assertEquals(run(r, backend.ops.send(client, bytes("v6"))), null)
          val buf = allocate(16)
          val from = Slot[IpAddress]()
          assertEquals(run(r, backend.ops.receive(server, buf, from)), null)
          assertEquals(StandardCharsets.UTF_8.decode(buf).toString, "v6")
          from.clear() match {
            case IpAddress.IPv6(host, _) => assertEquals(host, "::1")
            case other                   => fail(s"an IPv6 sender: $other")
          }
        } finally close(r, client)
      } finally close(r, server)
    }
  }

  test("a packet larger than the receive buffer fills it, the rest dropped, and the next packet arrives whole") {
    withReactor { r =>
      val (server, port) = onFreePort(port => bindDatagram(r, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val client = connectDatagram(r, null, IpAddress.IPv4("127.0.0.1", port))
        try {
          assertEquals(run(r, backend.ops.send(client, bytes("0123456789abcdef"))), null)
          assertEquals(run(r, backend.ops.send(client, bytes("next"))), null)
          def received(capacity: Int)(using munit.Location): String = {
            val buf = allocate(capacity)
            assertEquals(run(r, backend.ops.receive(server, buf, Slot[IpAddress]())), null)
            val copy = new Array[Byte](buf.remaining())
            buf.get(copy)
            new String(copy, StandardCharsets.UTF_8)
          }
          assertEquals(received(4), "0123", "the bytes that fitted, the rest dropped")
          assertEquals(received(16), "next", "the next packet, whole")
        } finally close(r, client)
      } finally close(r, server)
    }
  }

  test("datagrams not received are dropped once the socket's buffer is full, not queued without bound") {
    withReactor { r =>
      val (server, port) = onFreePort(port => bindDatagram(r, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val client = connectDatagram(r, null, IpAddress.IPv4("127.0.0.1", port))
        try {
          val sent = 2000 // of 8 KiB: 16 MiB, beyond any socket's receive buffer
          val packet = allocate(8192)
          for _ <- 1 to sent do {
            packet.clear()
            assertEquals(run(r, backend.ops.send(client, packet)), null)
          }
          val buf = allocate(8192)
          var received = 0
          var drained = false
          while !drained do {
            buf.clear()
            val outcome = Outcome()
            r.submit(backend.ops.receive(server, buf, Slot[IpAddress]()), outcome, timeoutMillis = 200)
            turnUntil(r)(outcome.finished)
            if outcome.failure != null then {
              assert(outcome.failure.isInstanceOf[TimeoutException], String.valueOf(outcome.failure))
              drained = true
            } else received += 1
          }
          assert(received > 0 && received < sent, s"$received of $sent received")
          assertEquals(run(r, backend.ops.send(client, bytes("after"))), null)
          buf.clear()
          assertEquals(run(r, backend.ops.receive(server, buf, Slot[IpAddress]())), null)
          assertEquals(StandardCharsets.UTF_8.decode(buf).toString, "after", "the socket goes on once drained")
        } finally close(r, client)
      } finally close(r, server)
    }
  }

  test("a datagram socket's op completes once it is bound, and fails, leaving its slot empty, on an address in use") {
    withReactor { r =>
      val (server, port) = onFreePort(port => bindDatagram(r, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val into = Slot[BoundDatagram]()
        val failure = run(r, backend.ops.bindDatagram(IpAddress.IPv4("127.0.0.1", port), into))
        assert(failure.isInstanceOf[IOException], String.valueOf(failure))
        intercept[IllegalStateException](into.clear())
        val client = connectDatagram(r, null, IpAddress.IPv4("127.0.0.1", port))
        close(r, client)
      } finally close(r, server)
    }
  }

  /** The bytes a pooled read received, as text, consuming them. */
  test("datagram sockets know their addresses: a bound one on port 0 its port, a connected one its own and its peer") {
    withReactor { r =>
      val bound = bindDatagram(r, IpAddress.IPv4("127.0.0.1", 0))
      try {
        val local = backend.ops.localAddress(bound)
        local match {
          case IpAddress.IPv4("127.0.0.1", port) => assert(port > 0, local)
          case other                             => fail(s"a bound socket on port 0's address: $other")
        }
        val client = connectDatagram(r, null, local)
        try {
          assertEquals(backend.ops.peerAddress(client), local)
          val clientAddress = backend.ops.localAddress(client)
          assertEquals(run(r, backend.ops.send(client, bytes("who"))), null)
          val from = Slot[IpAddress]()
          assertEquals(run(r, backend.ops.receive(bound, allocate(16), from)), null)
          assertEquals(from.clear(), clientAddress, "its sender is the address it reports")
        } finally close(r, client)
      } finally close(r, bound)
      intercept[IOException](backend.ops.localAddress(bound))
    }
  }

  test("a datagram socket refuses a stream socket's options, and takes a buffer size where the backend has it") {
    intercept[IllegalArgumentException](
      backend.ops
        .bindDatagram(IpAddress.IPv4("127.0.0.1", 0), SocketOptions.default.set(SocketOption.NoDelay, true), Slot())
    )
    if backend.ops.supports(SocketOption.ReceiveBuffer, SocketOption.Kind.BoundDatagram) then
      withReactor { r =>
        val into = Slot[BoundDatagram]()
        val options = SocketOptions.default.set(SocketOption.ReceiveBuffer, 65536)
        val bound = opened(r, into, backend.ops.bindDatagram(IpAddress.IPv4("127.0.0.1", 0), options, into))
        try {
          assert(backend.ops.option(bound, SocketOption.ReceiveBuffer) >= 65536)
          intercept[IllegalArgumentException](backend.ops.option(bound, SocketOption.NoDelay))
        } finally close(r, bound)
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
  private def boundWith(r: R, options: SocketOptions)(using munit.Location): BoundDatagram = {
    val into = Slot[BoundDatagram]()
    opened(r, into, backend.ops.bindDatagram(IpAddress.IPv4("127.0.0.1", 0), options, into))
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
    withReactor { r =>
      val plain = boundWith(r, SocketOptions.default)
      val dontFragment = boundWith(r, SocketOptions.default.set(SocketOption.DontFragment, true))
      try {
        assertEquals(backend.ops.option(plain, SocketOption.DontFragment), false, "the system's, off")
        assertEquals(backend.ops.option(dontFragment, SocketOption.DontFragment), true)
        if backend.ops.canChange(SocketOption.DontFragment, SocketOption.Kind.BoundDatagram) then {
          backend.ops.setOption(plain, SocketOption.DontFragment, true)
          assertEquals(backend.ops.option(plain, SocketOption.DontFragment), true)
        } else intercept[IllegalArgumentException](backend.ops.setOption(plain, SocketOption.DontFragment, true))
      } finally {
        close(r, plain)
        close(r, dontFragment)
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
    withReactor { r =>
      val receiver = boundWith(r, SocketOptions.default)
      val to = backend.ops.localAddress(receiver)
      val fragmenting = boundWith(r, room)
      val whole = boundWith(r, room.set(SocketOption.DontFragment, true))
      try {
        assertEquals(run(r, backend.ops.send(fragmenting, allocate(size), to)), null, "sent fragmented")
        val failure = run(r, backend.ops.send(whole, allocate(size), to))
        assert(failure.isInstanceOf[java.io.IOException], s"a datagram of $size bytes, MTU $mtu: $failure")
      } finally {
        close(r, receiver)
        close(r, fragmenting)
        close(r, whole)
      }
    }
  }

  test("ReusePort lets bound datagram sockets that set it share a port, refusing one that does not") {
    assume(
      backend.ops.supports(SocketOption.ReusePort, SocketOption.Kind.BoundDatagram),
      "this backend has no reuse-port option"
    )
    val reuse = SocketOptions.default.set(SocketOption.ReusePort, true)
    withReactor { r =>
      val first = boundWith(r, reuse)
      try {
        assertEquals(backend.ops.option(first, SocketOption.ReusePort), true)
        val port = backend.ops.localAddress(first)
        val into = Slot[BoundDatagram]()
        val second = opened(r, into, backend.ops.bindDatagram(port, reuse, into))
        try {
          val refused = Slot[BoundDatagram]()
          assert(run(r, backend.ops.bindDatagram(port, refused)).isInstanceOf[java.io.IOException], "one without it")
          intercept[IllegalArgumentException](backend.ops.setOption(second, SocketOption.ReusePort, false))
        } finally close(r, second)
      } finally close(r, first)
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
    withReactor { r =>
      val receiver = bindDatagram(r, IpAddress.IPv4("127.0.0.1", 0))
      val to = backend.ops.localAddress(receiver)
      val bound = bindDatagram(r, IpAddress.IPv4("127.0.0.1", 0))
      val connected = connectDatagram(r, null, to)
      try {
        def received()(using munit.Location): String = {
          val buf = allocate(64)
          assertEquals(run(r, backend.ops.receive(receiver, buf, Slot[IpAddress]())), null)
          StandardCharsets.UTF_8.decode(buf).toString
        }
        assertEquals(run(r, backend.ops.send(bound, Array(bytes("one "), bytes("packet")), to)), null)
        assertEquals(received(), "one packet")
        assertEquals(run(r, backend.ops.send(connected, Array(bytes("two "), bytes(""), bytes("parts")))), null)
        assertEquals(received(), "two parts")
        val empty = allocate(1)
        empty.limit(0)
        assert(
          run(r, backend.ops.send(connected, Array(empty, empty.duplicate()))).isInstanceOf[IllegalArgumentException]
        )
      } finally {
        close(r, receiver)
        close(r, bound)
        close(r, connected)
      }
    }
  }

  test(
    "receives pending at once on a datagram socket take turns, on any reactor, each its own packet; one cancelled, " +
      "or timed out, waiting gives up its place"
  ) {
    withReactor { a =>
      withReactor { b =>
        val server = bindDatagram(a, IpAddress.IPv4("127.0.0.1", 0))
        val client = connectDatagram(a, null, backend.ops.localAddress(server))
        try {
          // Not a tuple: matching one with a `ByteBuffer` in it has the compiler look into JDK 27's sealed buffers.
          final class Receiving(r: R, timeoutMillis: Int) {
            val buf = allocate(16)
            val op = backend.ops.receive(server, buf, Slot[IpAddress]())
            val outcome = Outcome()
            r.submit(op, outcome, timeoutMillis)
            r.runOnce(block = false)
          }
          val first = Receiving(a, -1)
          val leaving = Receiving(b, -1)
          val late = Receiving(a, 30)
          val second = Receiving(b, -1)
          assert(List(first, leaving, late, second).forall(!_.outcome.finished), "all four wait")
          assert(b.cancel(leaving.op), "a receive waiting its turn is pending, and cancelled")
          turnUntil(a, b)(leaving.outcome.finished && late.outcome.finished)
          assertEquals(leaving.outcome.cancelled, 1)
          assert(late.outcome.failure.isInstanceOf[TimeoutException], s"timed out: ${late.outcome.failure}")
          for packet <- List("one", "two") do assertEquals(run(a, backend.ops.send(client, bytes(packet))), null)
          turnUntil(a, b)(first.outcome.finished && second.outcome.finished)
          assertEquals(first.outcome.completed, 1, String.valueOf(first.outcome.failure))
          assertEquals(second.outcome.completed, 1, String.valueOf(second.outcome.failure))
          def received(at: Receiving): String = StandardCharsets.UTF_8.decode(at.buf).toString
          assertEquals((received(first), received(second)), ("one", "two"))
        } finally {
          close(a, client)
          close(a, server)
        }
      }
    }
  }

  test("sends submitted at once on a datagram socket, on any reactor, take turns, each its whole packet, in order") {
    withReactor { a =>
      withReactor { b =>
        val receiver = bindDatagram(a, IpAddress.IPv4("127.0.0.1", 0))
        val to = backend.ops.localAddress(receiver)
        val sender = bindDatagram(a, IpAddress.IPv4("127.0.0.1", 0))
        try {
          val packets = (1 to 6).map(i => s"packet $i")
          val sent = packets.zipWithIndex.map { (packet, i) =>
            val outcome = Outcome()
            (if i % 2 == 0 then a else b).submit(backend.ops.send(sender, bytes(packet), to), outcome)
            outcome
          }
          turnUntil(a, b)(sent.forall(_.finished))
          for outcome <- sent do assertEquals(outcome.completed, 1, String.valueOf(outcome.failure))
          val received = packets.map { _ =>
            val buf = allocate(16)
            assertEquals(run(a, backend.ops.receive(receiver, buf, Slot[IpAddress]())), null)
            StandardCharsets.UTF_8.decode(buf).toString
          }
          assertEquals(received, packets)
        } finally {
          close(a, receiver)
          close(a, sender)
        }
      }
    }
  }

  test("a datagram is sent from and received into any buffer, on the heap or not") {
    withReactor { r =>
      val receiver = bindDatagram(r, IpAddress.IPv4("127.0.0.1", 0))
      val to = backend.ops.localAddress(receiver)
      val sender = connectDatagram(r, null, to)
      try {
        def heap(text: String) = java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8))
        def received()(using munit.Location): String = {
          val buf = java.nio.ByteBuffer.allocate(64)
          assertEquals(run(r, backend.ops.receive(receiver, buf, Slot[IpAddress]())), null)
          StandardCharsets.UTF_8.decode(buf).toString
        }
        assertEquals(run(r, backend.ops.send(sender, heap("on the heap"))), null)
        assertEquals(received(), "on the heap")
        assertEquals(run(r, backend.ops.send(sender, heap("read-only").asReadOnlyBuffer())), null)
        assertEquals(received(), "read-only")
        assertEquals(run(r, backend.ops.send(sender, Array(heap("mixed "), bytes("buffers")))), null)
        assertEquals(received(), "mixed buffers")
      } finally {
        close(r, receiver)
        close(r, sender)
      }
    }
  }
}
