package gears.async.io
package testkit

/** What a backend that resolves host names must do: checked through `perform`, as programs over the backend (see
  * `ContractSuite`).
  */
trait ResolveContract extends ContractSuite {
  val backend: Backend { type Interface <: Resolving }

  /** Named-zone literals and their expected addresses, supplied using the platform's interface discovery. Empty when
    * named zones are unsupported or no suitable interface is available.
    */
  protected def namedScopedAddresses: List[(String, IpAddress)] = Nil

  test("resolve finds localhost") {
    running { io ?=>
      val into = Slot[List[IpAddress]]()
      io.perform(io.ops.resolve("localhost", into))
      val found = into.clear()
      assert(found.exists(a => a == IpAddress.IPv4Loopback || a.protocol == Protocol.IPv6), found.toString)
    }
  }

  test("resolve returns numeric address values, and refuses an empty name or one with a NUL as it is built") {
    running { io ?=>
      locally {
        for (numeric, expected) <- List(
            "::1" -> IpAddress.IPv6Loopback,
            "0:0:0:0:0:0:0:1" -> IpAddress.IPv6Loopback,
            "2001:DB8:0:0:1:0:0:1" -> IpAddress.ipv6(0x2001, 0xdb8, 0, 0, 1, 0, 0, 1),
            "fe80::1%7" -> IpAddress.ipv6(0xfe80, 0, 0, 0, 0, 0, 0, 1, scopeId = 7),
            "fe80:0000:0000:0000:0000:0000:0000:0001%7" ->
              IpAddress.ipv6(0xfe80, 0, 0, 0, 0, 0, 0, 1, scopeId = 7)
          )
        do {
          val into = Slot[List[IpAddress]]()
          io.perform(io.ops.resolve(numeric, into))
          assertEquals(into.clear(), List(expected), numeric)
        }
      }
      intercept[IllegalArgumentException](io.ops.resolve("", Slot[List[IpAddress]]()))
      intercept[IllegalArgumentException](io.ops.resolve("local\u0000host", Slot[List[IpAddress]]()))
    }
  }

  // The scope is encoded in the multicast address; the zone suffix is a separate, host-local identifier.
  // Parsing these literals needs neither a configured interface nor multicast socket support.
  for (scope, group) <- List(
      "interface" -> 0xff01,
      "link" -> 0xff02,
      "realm" -> 0xff03,
      "admin" -> 0xff04,
      "site" -> 0xff05,
      "organization" -> 0xff08
    )
  do
    test(s"resolve preserves the numeric zone of $scope-local IPv6 multicast literals") {
      running { io ?=>
        val expected = IpAddress.ipv6(group, 0, 0, 0, 0, 0, 0, 1, scopeId = 7)
        for literal <- List(s"${group.toHexString}::1%7", s"${group.toHexString}:0:0:0:0:0:0:1%7") do {
          val into = Slot[List[IpAddress]]()
          io.perform(io.ops.resolve(literal, into))
          assertEquals(into.clear(), List(expected), literal)
        }
      }
    }

  test("resolve converts an IPv6 interface-name zone to its numeric scope ID") {
    val examples = namedScopedAddresses
    assume(examples.nonEmpty, "no named-zone IPv6 examples are available on this platform")
    running { io ?=>
      for (literal, expected) <- examples do {
        val into = Slot[List[IpAddress]]()
        io.perform(io.ops.resolve(literal, into))
        assertEquals(into.clear(), List(expected), literal)
      }
    }
  }

  test("an op that is not Repeatable is performed only once") {
    val failure = running { io ?=>
      val into = Slot[List[IpAddress]]()
      val resolve = io.ops.resolve("localhost", into)
      io.perform(resolve)
      into.clear()
      try {
        io.perform(resolve)
        null
      } catch case t: Throwable => t
    }
    assert(failure.isInstanceOf[IllegalStateException], String.valueOf(failure))
  }
}
