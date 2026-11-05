package gears.async.io
package reference

import gears.async.io.testkit.*
import gears.io.testkit.SimpleIOChecks
import gears.io.testkit.SimpleIOIpStreamChecks
import gears.io.testkit.SimpleIOResolveChecks

/** Each suite sees only the capabilities required by its contracts. */
class ReferenceContractSuite extends OpsContract {
  val backend: Backend = ReferenceBackend
}

class ReferenceThreadedTimerSuite extends ThreadedTimerContract {
  val backend: Backend = ReferenceBackend
}

class ReferenceBlockingSuite extends BlockingContract {
  val backend: Blocking = ReferenceBackend
}

class ReferencePipeSuite extends PipeContract {
  val backend: Pipes = ReferenceBackend
}

/** Runs the same stream contracts using TCP, without exposing Pipes to the suite. */
class ReferenceSocketStreamSuite extends StreamContract with IpStreamFixture {
  val backend: IpStreams = ReferenceBackend
}

class ReferenceIpStreamSuite extends IpStreamContract {
  val backend: IpStreams = ReferenceBackend
}

class ReferenceIpDatagramSuite extends IpDatagramContract {
  val backend: IpDatagrams = ReferenceBackend
}

class ReferenceUnixStreamSuite extends UnixStreamContract {
  val backend: UnixStreams = ReferenceBackend
}

class ReferenceFileSuite extends FileContract {
  val backend: RegularFiles = ReferenceBackend
}

class ReferenceResolveSuite extends ResolveContract {
  val backend: Resolving = ReferenceBackend
}

class ReferenceSimpleIOSuite extends SimpleIOChecks with PipeFixture {
  val backend: Pipes = ReferenceBackend
}

class ReferenceSimpleIOSocketSuite extends SimpleIOChecks with IpStreamFixture {
  val backend: IpStreams = ReferenceBackend
}

class ReferenceSimpleIOIpStreamSuite extends SimpleIOIpStreamChecks {
  val backend: IpStreams = ReferenceBackend
}

class ReferenceSimpleIOResolveSuite extends SimpleIOResolveChecks {
  val backend: Resolving = ReferenceBackend
}
