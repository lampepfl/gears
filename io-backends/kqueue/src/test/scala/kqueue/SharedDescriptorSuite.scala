package gears.async.io
package kqueue

import munit.FunSuite

class SharedDescriptorSuite extends FunSuite {

  test("kqueue reactors on different threads share a descriptor, dropping stale filters and reused numbers") {
    val outcome = SharedDescriptorCheck.run()
    assertEquals(outcome.failures, 0)
    assert(outcome.reuseChecked, "the new pipe did not reuse the old one's numbers, so reuse was not checked")
  }
}
