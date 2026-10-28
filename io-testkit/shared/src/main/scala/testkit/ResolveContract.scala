package gears.async.io
package testkit

import gears.async.io.internal.ReactorBackend

/** What a backend that resolves host names must do (see `ReactorContract`). */
trait ResolveContract extends ReactorContract {
  val backend: ReactorBackend & Blocking & Pipes & Resolving

  test("resolve finds localhost") {
    withReactor { r =>
      val into = Slot[List[ResolvedAddress]]()
      val outcome = Outcome()
      r.submit(backend.ops.resolve("localhost", into), outcome)
      turnUntil(r)(outcome.finished)
      assertEquals(outcome.completed, 1, String.valueOf(outcome.failure))
      val found = into.clear()
      assert(found.exists(a => a.host == "127.0.0.1" || a.family == AddressFamily.IPv6), found.toString)
    }
  }

  test("resolve writes an IPv6 address as inet_ntop does, and refuses an empty name or one with a NUL as it is built") {
    withReactor { r =>
      for numeric <- List("::1", "0:0:0:0:0:0:0:1", "2001:DB8:0:0:1:0:0:1") do {
        val into = Slot[List[ResolvedAddress]]()
        assertEquals(run(r, backend.ops.resolve(numeric, into)), null)
        val expected = if numeric == "2001:DB8:0:0:1:0:0:1" then "2001:db8::1:0:0:1" else "::1"
        assertEquals(into.clear(), List(ResolvedAddress(AddressFamily.IPv6, expected)), numeric)
      }
      intercept[IllegalArgumentException](backend.ops.resolve("", Slot[List[ResolvedAddress]]()))
      intercept[IllegalArgumentException](backend.ops.resolve("local\u0000host", Slot[List[ResolvedAddress]]()))
    }
  }

}
