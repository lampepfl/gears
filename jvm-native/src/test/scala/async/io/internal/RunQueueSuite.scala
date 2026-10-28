package gears.async.io
package internal

import munit.FunSuite

class RunQueueSuite extends FunSuite {
  test("the run queue runs every task once while other threads take tasks from it") {
    val outcome = RunQueueCheck.run(tasks = 2_000_000, takers = 3)
    assertEquals(outcome.failures, 0L)
    assert(outcome.moved > 0, "nothing was moved, so the check proves little")
  }
}
