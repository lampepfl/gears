package gears.async.io
package testkit

/** One-shot resolution in a program requiring only Resolving. */
trait ResolveFuturesContract extends ContractSuite {
  val backend: Backend & Resolving

  test("an op that is not Repeatable is performed only once") {
    val failure = running { io ?=>
      val into = Slot[List[IpAddress]]()
      val resolve = io.backend.ops.resolve("localhost", into)
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
