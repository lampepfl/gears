package gears.async.io
package nio

import gears.async.io.testkit.*
import gears.io.testkit.SimpleIOChecks

/** Each suite sees only the capabilities required by its contracts. */
class NioContractSuite extends OpsContract {
  val backend: Backend = NioBackend
}

class NioThreadedTimerSuite extends ThreadedTimerContract {
  val backend: Backend = NioBackend
}

class NioPipeSuite extends PipeContract {
  val backend: NioBackend.type = NioBackend
}

class NioSimpleIOSuite extends SimpleIOChecks with PipeFixture {
  val backend: NioBackend.type = NioBackend
}
