package gears.io.testkit

import gears.async.io.Backend
import gears.async.io.Resolving
import gears.async.io.testkit.ContractSuite

/** Public API resolution helpers, requiring only Resolving. */
trait SimpleIOResolveChecks extends ContractSuite {
  val backend: Backend & Resolving

  test("SimpleIO resolves localhost") {
    val resolved = running { io ?=> SimpleIO.resolve("localhost") }
    assert(resolved.nonEmpty)
  }
}
