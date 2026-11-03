package gears.async.io
package testkit

/** What a backend that resolves host names must do: checked through `perform`, as programs over the backend (see
  * `ContractSuite`).
  */
trait ResolveContract extends ContractSuite {
  val backend: Backend & Resolving

  test("resolve finds localhost") {
    running { io ?=>
      val into = Slot[List[IpAddress]]()
      io.perform(backend.ops.resolve("localhost", into))
      val found = into.clear()
      assert(found.exists(a => a == IpAddress.IPv4Loopback || a.protocol == Protocol.IPv6), found.toString)
    }
  }

  test("resolve returns numeric address values, and refuses an empty name or one with a NUL as it is built") {
    running { io ?=>
      for (numeric, expected) <- List(
          "::1" -> IpAddress.IPv6Loopback,
          "0:0:0:0:0:0:0:1" -> IpAddress.IPv6Loopback,
          "2001:DB8:0:0:1:0:0:1" -> IpAddress.ipv6(0x2001, 0xdb8, 0, 0, 1, 0, 0, 1),
          "fe80::1%7" -> IpAddress.ipv6(0xfe80, 0, 0, 0, 0, 0, 0, 1, scopeId = 7)
        )
      do {
        val into = Slot[List[IpAddress]]()
        io.perform(backend.ops.resolve(numeric, into))
        assertEquals(into.clear(), List(expected), numeric)
      }
    }
    intercept[IllegalArgumentException](backend.ops.resolve("", Slot[List[IpAddress]]()))
    intercept[IllegalArgumentException](backend.ops.resolve("local\u0000host", Slot[List[IpAddress]]()))
  }
}
