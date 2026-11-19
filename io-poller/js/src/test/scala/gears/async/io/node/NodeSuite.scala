package gears.async.io
package node

import gears.async.io.testkit.ContractSuite

import scala.scalajs.js

/** What every suite of the Node.js backend's is: each check runs within `js.async`, where a run over the backend can
  * suspend it (see `NodeBackend`), and the test is done once the promise is.
  */
trait NodeSuite extends ContractSuite {
  override def test(options: munit.TestOptions)(body: => Any)(using loc: munit.Location): Unit =
    super.test(options) {
      js.async {
        val result: Any = body // a block of its own: `js.async` takes no by-name argument as it is
        result
      }.toFuture
    }
}
