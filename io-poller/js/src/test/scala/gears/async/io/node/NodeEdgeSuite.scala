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
