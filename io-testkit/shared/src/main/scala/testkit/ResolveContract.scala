package gears.async.io
package testkit

/** What a backend that resolves host names must do: checked through `perform`, as programs over the backend (see
  * `ContractSuite`).
  */
trait ResolveContract extends ContractSuite {
  val backend: Backend & Resolving

  test("resolve finds localhost") {
    running { io ?=>
      val into = Slot[List[ResolvedAddress]]()
      io.perform(backend.ops.resolve("localhost", into))
      val found = into.clear()
      assert(found.exists(a => a.host == "127.0.0.1" || a.family == AddressFamily.IPv6), found.toString)
    }
  }

  test("resolve writes an IPv6 address as inet_ntop does, and refuses an empty name or one with a NUL as it is built") {
    running { io ?=>
      for numeric <- List("::1", "0:0:0:0:0:0:0:1", "2001:DB8:0:0:1:0:0:1") do {
        val into = Slot[List[ResolvedAddress]]()
        io.perform(backend.ops.resolve(numeric, into))
        val expected = if numeric == "2001:DB8:0:0:1:0:0:1" then "2001:db8::1:0:0:1" else "::1"
        assertEquals(into.clear(), List(ResolvedAddress(AddressFamily.IPv6, expected)), numeric)
      }
    }
    intercept[IllegalArgumentException](backend.ops.resolve("", Slot[List[ResolvedAddress]]()))
    intercept[IllegalArgumentException](backend.ops.resolve("local\u0000host", Slot[List[ResolvedAddress]]()))
  }
}
