package gears.async.io.reference

import gears.async.io.IpAddress
import gears.async.io.IpSocketAddress
import gears.async.io.Protocol

import java.net.Inet6Address
import java.net.InetSocketAddress

class AddressInteropSuite extends munit.FunSuite {
  test("JDK conversion preserves network-order bytes and port without a hostname") {
    val random = new scala.util.Random(0)
    for (protocol, length) <- List(Protocol.IPv4 -> 4, Protocol.IPv6 -> 16); _ <- 0 until 100 do {
      val bytes = Array.fill[Byte](length)(random.nextInt(256).toByte)
      val endpoint = IpSocketAddress(IpAddress.fromBytes(protocol, bytes), random.nextInt(65536))
      val jdk = Addresses.jdk(endpoint)
      assertEquals(jdk.getAddress.getAddress.toList, bytes.toList)
      assertEquals(jdk.getPort, endpoint.port)
      assertEquals(Addresses.ip(jdk), endpoint)
      assertEquals(Addresses.resolved(jdk.getAddress), endpoint.address)
    }
  }

  test("mapped IPv4 peers are reported as IPv4 even when the JDK supplies sixteen bytes") {
    val bytes = Array[Byte](0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 127, 0, 0, 1)
    val mapped = Inet6Address.getByAddress(null, bytes, 0)
    val endpoint = Addresses.ip(new InetSocketAddress(mapped, 1234))
    assertEquals(endpoint, IpSocketAddress(IpAddress.IPv4Loopback, 1234))
    assertEquals(endpoint.address.protocol, Protocol.IPv4)
    assertEquals(Addresses.resolved(mapped), IpAddress.IPv4Loopback)
  }

  test("scoped IPv6 addresses preserve bytes, scope and port in both directions") {
    for scope <- List(0, 1, 7, Int.MaxValue) do {
      val ip = IpAddress.ipv6(0xfe80, 0, 0, 0, 0, 0, 0, 1, scopeId = scope)
      val scoped = Inet6Address.getByAddress(null, ip.toBytes, scope)
      val endpoint = IpSocketAddress(ip, 1234)
      assertEquals(Addresses.resolved(scoped), ip)
      assertEquals(Addresses.ip(new InetSocketAddress(scoped, 1234)), endpoint)
      val jdk = Addresses.jdk(endpoint)
      assertEquals(jdk.getAddress.asInstanceOf[Inet6Address].getScopeId(), scope)
      assertEquals(jdk.getAddress.getAddress.toList, ip.bytes.toList)
      assertEquals(jdk.getPort, 1234)
      assertEquals(Addresses.ip(jdk), endpoint)
    }
  }

  test("mapped addresses with a scope retain it instead of being normalized to IPv4") {
    val mapped = IpAddress.ipv6(0, 0, 0, 0, 0, 0xffff, 0x7f00, 1, scopeId = 7)
    assertEquals(Addresses.ip(Addresses.jdk(IpSocketAddress(mapped, 1234))), IpSocketAddress(mapped, 1234))
  }

}
