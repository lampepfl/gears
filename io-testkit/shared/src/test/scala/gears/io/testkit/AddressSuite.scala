package gears.io.testkit

import gears.async.io.IpAddress
import gears.async.io.IpSocketAddress
import gears.async.io.Protocol

class AddressSuite extends munit.FunSuite {
  test("scoped constructors accept only IPv6 at compile time") {
    assert(compileErrors("IpAddress.ipv4(127, 0, 0, 1, scopeId = 7)").nonEmpty)
    assert(compileErrors("IpAddress(Protocol.IPv4, IArray[Byte](127, 0, 0, 1), scopeId = 7)").nonEmpty)
    assert(compileErrors("IpAddress.fromBytes(Protocol.IPv4, Array[Byte](127, 0, 0, 1), scopeId = 7)").nonEmpty)
    assert(
      compileErrors(
        "val protocol: Protocol = Protocol.IPv6; IpAddress(protocol, IArray.fill[Byte](16)(0), scopeId = 7)"
      ).nonEmpty
    )
  }

  test("IPv6 scopes are part of identity and numeric formatting") {
    val address = IpAddress.ipv6(0xfe80, 0, 0, 0, 0, 0, 0, 1, scopeId = 7)
    val unscoped = IpAddress(Protocol.IPv6, address.bytes)
    val otherScope = IpAddress(Protocol.IPv6, address.bytes, scopeId = 8)
    val fromBytes = IpAddress.fromBytes(Protocol.IPv6, address.toBytes, scopeId = 7)
    val fromIArray = IpAddress(Protocol.IPv6, address.bytes, scopeId = 7)
    assertEquals(address.scopeId, 7)
    assertEquals(unscoped.scopeId, 0)
    assertEquals(address, fromBytes)
    assertEquals(address, fromIArray)
    assertEquals(address.hashCode, fromBytes.hashCode)
    assertNotEquals(address, unscoped)
    assertNotEquals(address, otherScope)
    assertEquals(Set(address, fromBytes, fromIArray, unscoped, otherScope).size, 3)
    assertEquals(Map(address -> "found")(fromIArray), "found")
    assertEquals(address.toString, "fe80::1%7")
    assertEquals(unscoped.toString, "fe80::1")
    assertEquals(IpSocketAddress(address, 1234).toString, "[fe80::1%7]:1234")
    assertEquals(IpAddress.IPv4Loopback.scopeId, 0)
    assertEquals(IpAddress.IPv6Loopback.scopeId, 0)
    assertEquals(IpAddress(Protocol.IPv6, address.bytes, scopeId = Int.MaxValue).scopeId, Int.MaxValue)
  }

  test("IPv6 scopes must be nonnegative") {
    val address = IpAddress.IPv6Loopback
    for invalid <- List(-1, Int.MinValue) do {
      intercept[IllegalArgumentException](IpAddress(Protocol.IPv6, address.bytes, scopeId = invalid))
      intercept[IllegalArgumentException](IpAddress.fromBytes(Protocol.IPv6, address.toBytes, scopeId = invalid))
      intercept[IllegalArgumentException](IpAddress.ipv6(0, 0, 0, 0, 0, 0, 0, 1, scopeId = invalid))
    }

  }

  test("IPv4 constructor accepts unsigned integer octets") {
    assertEquals(IpAddress.ipv4(127, 0, 0, 1), IpAddress.IPv4Loopback)
    val address = IpAddress.ipv4(192, 168, 1, 255)
    assertEquals(address.protocol, Protocol.IPv4)
    assertEquals(address.bytes.toList, List[Byte](-64, -88, 1, -1))
    assertEquals(address.toString, "192.168.1.255")
    assertEquals(IpAddress.ipv4(0, 255, 0, 255).toString, "0.255.0.255")
  }

  test("IPv6 constructor accepts integer groups and stores them in network order") {
    assertEquals(IpAddress.ipv6(0, 0, 0, 0, 0, 0, 0, 1), IpAddress.IPv6Loopback)
    val address = IpAddress.ipv6(0x2001, 0xdb8, 0, 0x7fff, 0x8000, 0xabcd, 0xff00, 0xffff)
    assertEquals(address.protocol, Protocol.IPv6)
    assertEquals(
      address.bytes.toList,
      List[Byte](0x20, 1, 0x0d, -72, 0, 0, 0x7f, -1, -128, 0, -85, -51, -1, 0, -1, -1)
    )
    assertEquals(address.toString, "2001:db8:0:7fff:8000:abcd:ff00:ffff")
  }

  test("IPv4 constructor rejects out-of-range octets in every position") {
    for invalid <- List(Int.MinValue, -1, 256, Int.MaxValue); index <- 0 until 4 do {
      val octets = Array.fill(4)(0)
      octets(index) = invalid
      intercept[IllegalArgumentException](IpAddress.ipv4(octets(0), octets(1), octets(2), octets(3)))
    }
  }

  test("IPv6 constructor rejects out-of-range groups in every position") {
    for invalid <- List(Int.MinValue, -1, 65536, Int.MaxValue); index <- 0 until 8 do {
      val groups = Array.fill(8)(0)
      groups(index) = invalid
      intercept[IllegalArgumentException](
        IpAddress.ipv6(groups(0), groups(1), groups(2), groups(3), groups(4), groups(5), groups(6), groups(7))
      )
    }
  }

  test("each built-in protocol validates its own byte length") {
    for (protocol, size) <- List(Protocol.IPv4 -> 4, Protocol.IPv6 -> 16); length <- 0 to 32 do {
      val bytes = Array.fill[Byte](length)(0)
      if length == size then {
        val address = IpAddress.fromBytes(protocol, bytes)
        assertEquals(address.protocol, protocol)
        assertEquals(address.bytes.length, length)
      } else {
        intercept[IllegalArgumentException](IpAddress.fromBytes(protocol, bytes))
        intercept[IllegalArgumentException](IpAddress(protocol, IArray.from(bytes)))
      }
    }
  }

  test("equality and hashing use bytes, including bytes with the high bit set") {
    for (protocol, length) <- List(Protocol.IPv4 -> 4, Protocol.IPv6 -> 16) do {
      val source = Array.tabulate[Byte](length)(i => (128 + i).toByte)
      val a = IpAddress.fromBytes(protocol, source)
      val b = IpAddress(protocol, IArray.from(source))
      assertEquals(a, b)
      assertEquals(a.hashCode, b.hashCode)
      assertEquals(Set(a, b).size, 1)
      assertEquals(Map(a -> "found")(b), "found")
      source(length - 1) = 0
      assertNotEquals(a, IpAddress.fromBytes(protocol, source))
      assert(!a.equals(null))
      assert(!a.equals(a.toString))
    }
    assertNotEquals(IpAddress.IPv4Any, IpAddress.IPv6Any)
  }

  test("constructors and mutable exports isolate the address from array aliases") {
    val input = Array[Byte](127, 0, 0, 1)
    val a = IpAddress.fromBytes(Protocol.IPv4, input)
    val b = IpAddress(Protocol.IPv4, IArray.unsafeFromArray(input))
    val hash = b.hashCode
    input(0) = 1
    val exported = b.toBytes
    exported(3) = 2
    assertEquals(a, IpAddress.IPv4Loopback)
    assertEquals(b, IpAddress.IPv4Loopback)
    assertEquals(b.hashCode, hash)
    assertEquals(b.bytes.toList, List[Byte](127, 0, 0, 1))
  }

  test("an IPv4-mapped IPv6 address retains its protocol and sixteen-byte identity") {
    val mapped = IpAddress(Protocol.IPv6, IArray[Byte](0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 127, 0, 0, 1))
    assertEquals(mapped.protocol, Protocol.IPv6)
    assertEquals(mapped.bytes.length, 16)
    assertNotEquals(mapped, IpAddress.IPv4Loopback)
    assertEquals(mapped.toString, "::ffff:127.0.0.1")
  }

  test("socket addresses validate ports at construction and when copied") {
    for port <- List(0, 1, 65535) do {
      val endpoint = IpSocketAddress(IpAddress.IPv4Loopback, port)
      assertEquals(endpoint.port, port)
      assertEquals(endpoint, IpSocketAddress(IpAddress.fromBytes(Protocol.IPv4, Array[Byte](127, 0, 0, 1)), port))
    }
    for port <- List(Int.MinValue, -1, 65536, Int.MaxValue) do {
      intercept[IllegalArgumentException](IpSocketAddress(IpAddress.IPv4Loopback, port))
      intercept[IllegalArgumentException](IpSocketAddress(IpAddress.IPv4Loopback, 80).copy(port = port))
    }
  }

  test("numeric formatting handles unsigned bytes and IPv6 zero compression") {
    def ipv6(groups: Int*): IpAddress =
      IpAddress.fromBytes(Protocol.IPv6, groups.flatMap(g => Seq((g >>> 8).toByte, g.toByte)).toArray)

    assertEquals(IpAddress.fromBytes(Protocol.IPv4, Array[Byte](-64, -88, 1, -1)).toString, "192.168.1.255")
    assertEquals(IpAddress.IPv6Any.toString, "::")
    assertEquals(IpAddress.IPv6Loopback.toString, "::1")
    assertEquals(ipv6(0x2001, 0xdb8, 0, 0, 1, 0, 0, 1).toString, "2001:db8::1:0:0:1")
    assertEquals(ipv6(1, 0, 0, 2, 0, 0, 0, 3).toString, "1:0:0:2::3")
    assertEquals(ipv6(1, 2, 3, 4, 5, 6, 0, 0).toString, "1:2:3:4:5:6::")
    assertEquals(ipv6(1, 2, 3, 0, 5, 6, 7, 0xffff).toString, "1:2:3:0:5:6:7:ffff")
    assertEquals(IpSocketAddress(IpAddress.IPv4Loopback, 80).toString, "127.0.0.1:80")
    assertEquals(IpSocketAddress(IpAddress.IPv6Loopback, 80).toString, "[::1]:80")
  }
}
