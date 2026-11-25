package gears.async.io
package poller

import gears.async.Async
import gears.async.default.given

/** A suite whose tests run gears programs to their end: on the JVM and Scala Native, by blocking the test's thread. */
abstract class SuspendingSuite extends munit.FunSuite {

  /** Runs `body` as a gears program, and returns what it returns. */
  protected def blocking[T](body: Async.Spawn ?=> T): T = Async.blocking(body)
}
