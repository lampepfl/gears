import language.experimental.captureChecking

import gears.async.AsyncOperations.*
import gears.async.default.given
import gears.async.{Async, AsyncSupport, Future, uninterruptible}

import java.util.concurrent.CancellationException
import scala.concurrent.duration.{Duration, DurationInt}
import scala.util.Properties
import scala.util.Success
import scala.util.boundary

import boundary.break

// JVM-only since `munitTimeout` is not available on scala native.
// See (here)[https://scalameta.org/munit/docs/tests.html#customize-test-timeouts].
class JVMCancellationBehavior extends munit.FunSuite:
  override def munitTimeout: Duration = 2.seconds
  test("no cancel -> timeout".fail):
    Async.blocking:
      val f = Future:
        Thread.sleep(5000)
        1
      f.awaitResult
