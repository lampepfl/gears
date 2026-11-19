package gears.async.io
package node

import gears.async.Future
import gears.async.io.node.host.Addresses
import gears.async.io.testkit.IpStreamFixture

import java.io.IOException
import scala.scalajs.js

/** Checks of this backend's own edges, beyond the contracts: what it does when an op is refused as it starts or as what
  * it waited for comes, where Node has already handed the backend something that must not then be lost.
  */
class NodeEdgeSuite extends IpStreamFixture with NodeSuite {
  val backend: NodeBackend.type = NodeBackend
  private val loopback = IpSocketAddress(IpAddress.IPv4Loopback, 0)

  test("ops refused at once, performed without end, leave the event loop its turns") {
    running { io ?=>
      val (in, out) = stream(io)
      close(io, in)
      close(io, out)
      var turned = false
      js.timers.setTimeout(0) { turned = true }
      val read = io.ops.read(in, allocate(8))
      var refused = 0
      while !turned && refused < 1_000_000 do {
        assert(attempt(io, read).isInstanceOf[ClosedChannelException])
        refused += 1
      }
      assert(turned, s"$refused reads were refused, and no callback of the event loop's ran")
    }
  }

  test("a run that returns with a read pending and handles open closes them, and no other run may use them") {
    var left: (AnyRef, AnyRef, AnyRef) | Null = null
    running { io ?=>
      val (in, out) = stream(io)
      val listening = Slot[io.ops.Listener]()
      io.perform(io.ops.listen(loopback, listening))
      Future(io.perform(io.ops.read(in, allocate(16))))
      settle(io)
      left = (in, out, listening.clear()) // never closed, and carried out of the run
    }
    val (leftIn, leftOut, leftListener) = left.nn
    for handle <- List(leftIn, leftOut, leftListener) do
      assertNotEquals(
        handle.asInstanceOf[shareable.BaseHandle].getState,
        shareable.HandleState.OpenState,
        s"$handle was left open by its run"
      )
    // A handle is its run's: brought into another, here by a cast, it is refused.
    running { io ?=>
      val in = leftIn.asInstanceOf[io.ops.Readable]
      val out = leftOut.asInstanceOf[io.ops.Writable]
      val reading = attempt(io, io.ops.read(in, allocate(16)))
      assert(reading.isInstanceOf[IllegalArgumentException], String.valueOf(reading))
      val writing = attempt(io, io.ops.write(out, bytes("y")))
      assert(writing.isInstanceOf[IllegalArgumentException], String.valueOf(writing))
      val closing = attempt(io, io.ops.close(in))
      assert(closing.isInstanceOf[IllegalArgumentException], String.valueOf(closing))
    }
  }

  test("a pooled read whose slot is filled while it waits fails, taking no bytes and holding no buffer") {
    val pool = backend.pool(bufferSize = 16, buffers = 1)
    try
      running { io ?=>
        val (in, out) = stream(io)
        try {
          val into = Slot[Received]()
          val reading = Future(attempt(io, io.ops.read(in, pool, into)))
          settle(io)
          val other = new Received {
            val buffer = java.nio.ByteBuffer.allocate(0)
            def release(): Unit = ()
          }
          into.set(other) // as another op sharing the slot would
          writeAll(io, out, bytes("kept"))
          val failure = reading.await
          assert(failure.isInstanceOf[IllegalArgumentException], String.valueOf(failure))
          assert(into.clear() eq other, "the slot holds what was put there")
          assertEquals(pool.available, 1, "the read that failed holds no buffer")
          val buf = allocate(8)
          readExactly(io, io.ops.read(in, buf), buf, 4)
          assertEquals(text(buf), "kept", "the bytes are the next read's")
        } finally {
          close(io, in)
          close(io, out)
        }
      }
    finally pool.close()
  }

  test("an accept whose slot is filled while it waits fails, and its connection is the next accept's") {
    running { io ?=>
      val listener = opening[io.ops.Listener](io)(slot => io.ops.listen(loopback, slot))
      val address = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress]
      try {
        val first = opening[io.ops.Connection](io)(slot => io.ops.connect(address, slot))
        val firstServer = opening[io.ops.Connection](io)(slot => io.ops.accept(listener, slot))
        val into = Slot[io.ops.Connection]()
        val accepting = Future(attempt(io, io.ops.accept(listener, into)))
        settle(io)
        into.set(firstServer) // the waiting accept's slot, filled before its connection comes
        val second = opening[io.ops.Connection](io)(slot => io.ops.connect(address, slot))
        val failure = accepting.await
        assert(failure.isInstanceOf[IllegalStateException], String.valueOf(failure))
        assert(into.clear() eq firstServer)
        val secondServer = opening[io.ops.Connection](io)(slot => io.ops.accept(listener, slot))
        assertEquals(io.ops.peerAddress(secondServer), io.ops.localAddress(second), "the connection was kept")
        for connection <- List(first, firstServer, second, secondServer) do close(io, connection)
      } finally close(io, listener)
    }
  }

  test("a receive whose slot is filled while it waits fails, and its packet is the next receive's") {
    running { io ?=>
      val server = opening[io.ops.BoundDatagram](io)(slot => io.ops.bindDatagram(loopback, slot))
      val client =
        opening[io.ops.ConnectedDatagram](io)(slot => io.ops.connectDatagram(null, io.ops.localAddress(server), slot))
      try {
        val from = Slot[IpSocketAddress]()
        val buf = allocate(16)
        val receiving = Future(attempt(io, io.ops.receive(server, buf, from)))
        settle(io)
        from.set(loopback)
        io.perform(io.ops.send(client, bytes("kept")))
        val failure = receiving.await
        assert(failure.isInstanceOf[IllegalArgumentException], String.valueOf(failure))
        assertEquals(from.clear(), loopback)
        val again = allocate(16)
        io.perform(io.ops.receive(server, again, from))
        assertEquals(java.nio.charset.StandardCharsets.UTF_8.decode(again).toString, "kept")
        assertEquals(from.clear(), io.ops.localAddress(client))
      } finally {
        close(io, client)
        close(io, server)
      }
    }
  }

  test("a listener closed with connections no accept took closes them: their clients read the end") {
    running { io ?=>
      val listener = opening[io.ops.Listener](io)(slot => io.ops.listen(loopback, slot))
      val address = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress]
      val client = opening[io.ops.Connection](io)(slot => io.ops.connect(address, slot))
      try {
        settle(io) // for the listener to have been handed the connection
        close(io, listener)
        val buf = allocate(8)
        val failure = attempt(io, io.ops.read(client, buf))
        assert(failure == null || failure.isInstanceOf[IOException], String.valueOf(failure))
        assertEquals(buf.position(), 0, "closed, or reset: nothing comes")
      } finally close(io, client)
    }
  }
}

/** Addresses as text, to and from what Node takes and reports. */
class NodeAddressSuite extends munit.FunSuite {
  private def v6(groups: Int*)(scope: Int = 0): IpAddress =
    IpAddress.ipv6(groups(0), groups(1), groups(2), groups(3), groups(4), groups(5), groups(6), groups(7), scope)

  test("addresses written out are read, and what is not one is left to the resolver") {
    assertEquals(Addresses.parse("127.0.0.1"), IpAddress.IPv4Loopback)
    assertEquals(Addresses.parse("255.255.255.255"), IpAddress.ipv4(255, 255, 255, 255))
    assertEquals(Addresses.parse("::"), IpAddress.IPv6Any)
    assertEquals(Addresses.parse("::1"), IpAddress.IPv6Loopback)
    assertEquals(Addresses.parse("1::"), v6(1, 0, 0, 0, 0, 0, 0, 0)())
    assertEquals(Addresses.parse("1:2:3:4:5:6:7::"), v6(1, 2, 3, 4, 5, 6, 7, 0)())
    assertEquals(Addresses.parse("2001:DB8::8:800:200C:417A"), v6(0x2001, 0xdb8, 0, 0, 8, 0x800, 0x200c, 0x417a)())
    assertEquals(Addresses.parse("::ffff:1.2.3.4"), v6(0, 0, 0, 0, 0, 0xffff, 0x0102, 0x0304)())
    assertEquals(Addresses.parse("0:0:0:0:0:ffff:1.2.3.4"), v6(0, 0, 0, 0, 0, 0xffff, 0x0102, 0x0304)())
    assertEquals(Addresses.parse("fe80::1%7"), v6(0xfe80, 0, 0, 0, 0, 0, 0, 1)(7))
    for text <- List(
        "localhost",
        "",
        "1.2.3",
        "1.2.3.4.5",
        "256.1.1.1",
        "1.2.3.",
        "01.2.3.4",
        "1.2.3.4%1",
        "1::2::3",
        ":::",
        ":1:2:3:4:5:6:7",
        "1:2:3:4:5:6:7",
        "1:2:3:4:5:6:7:8:9",
        "1:2:3:4:5:6:7:8::",
        "12345::",
        "g::1",
        "::1.2.3",
        "1.2.3.4::",
        "fe80::1%",
        "fe80::1%no-such-interface"
      )
    do assertEquals(Addresses.parse(text), null, text)
  }

  test("an IPv4-mapped address without a scope is reported as the IPv4 address it maps, and sent to as mapped") {
    assertEquals(Addresses.reported("::ffff:127.0.0.1", 80), IpSocketAddress(IpAddress.IPv4Loopback, 80))
    assertEquals(Addresses.reported("::1", 80), IpSocketAddress(IpAddress.IPv6Loopback, 80))
    assertEquals(Addresses.hostForIPv6(IpAddress.IPv4Loopback), "::ffff:127.0.0.1")
    assertEquals(Addresses.parse(Addresses.hostForIPv6(IpAddress.IPv4Loopback)).nn.protocol, Protocol.IPv6)
    intercept[IOException](Addresses.reported(js.undefined, js.undefined))
  }
}
