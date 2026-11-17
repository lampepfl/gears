package gears.async.io
package kqueue

import gears.async.Async
import gears.async.AsyncSupport
import gears.async.Future
import gears.async.io.testkit.*
import gears.io.testkit.SimpleIOChecks

/** Each suite sees only the capabilities required by its contracts. */
class KqueueContractSuite extends OpsContract with FailsOnStarvation {
  val backend: Backend = KqueueBackend
}

class KqueueThreadedTimerSuite extends ThreadedTimerContract with FailsOnStarvation {
  val backend: Backend = KqueueBackend
}

class KqueuePipeSuite extends PipeContract with FailsOnStarvation {
  val backend: KqueueBackend.type = KqueueBackend
}

class KqueueSimpleIOSuite extends SimpleIOChecks with PipeFixture with FailsOnStarvation {
  val backend: KqueueBackend.type = KqueueBackend
}

/** Runs a suite's programs on a pool of `count` workers rather than `Io.run`'s one for each processor. On one worker
  * every op waits on the worker that performs it, and is done, when its handle is ready, by that same thread.
  */
trait OnWorkers(count: Int) extends FailsOnStarvation {
  override protected def workers: Int = count
}

class KqueueOnOneContractSuite extends OpsContract with OnWorkers(1) with FailsOnStarvation {
  val backend: Backend = KqueueBackend
}

class KqueueOnOnePipeSuite extends PipeContract with OnWorkers(1) with FailsOnStarvation {
  val backend: KqueueBackend.type = KqueueBackend
}
