package gears.async.io
package poller

import gears.async.Async
import gears.async.js.UnsafeJsAsyncFromSync

import scala.scalajs.js

/** A suite whose tests run gears programs to their end: on Scala.js, where nothing blocks, by suspending the test,
  * which is run where JSPI can suspend it (`js.async`) and is done when its promise is.
  */
abstract class SuspendingSuite extends munit.FunSuite {
  override def test(options: munit.TestOptions)(body: => Any)(using loc: munit.Location): Unit =
    super.test(options) {
      js.async {
        val result: Any = body // a block of its own: `js.async` takes no by-name argument as it is
        result
      }.toFuture
    }

  /** Runs `body` as a gears program, and returns what it returns. */
  protected def blocking[T](body: Async.Spawn ?=> T): T = Async.fromSync(using UnsafeJsAsyncFromSync)(body)
}
