package gears.async.io
package nio

import gears.async.io.testkit.*
import gears.io.testkit.SimpleIOChecks
import gears.io.testkit.SimpleIOIpStreamChecks
import gears.io.testkit.SimpleIOResolveChecks


// The contracts over the backend on the JDK's own virtual threads. Over the pool, whose workers carry them, they are
// checked from the shared tests (`gears.async.io.poller.PoolSuites`).

/** Each suite sees only the capabilities required by its contracts. */
class NioContractSuite extends OpsContract {
  val backend: Backend = NioBackend
}

class NioThreadedTimerSuite extends ThreadedTimerContract {
  val backend: Backend = NioBackend
}

class NioBlockingSuite extends BlockingContract {
  val backend: Backend { type Interface <: Blocking } = NioBackend
}

class NioPipeSuite extends PipeContract {
  val backend: NioBackend.type = NioBackend
}

class NioSimpleIOSuite extends SimpleIOChecks with PipeFixture {
  val backend: NioBackend.type = NioBackend
}

/** Runs the same stream contracts using TCP, without exposing Pipes to the suite. */
class NioSocketStreamSuite extends StreamContract with IpStreamFixture {
  val backend: Backend { type Interface <: IpStreams } = NioBackend
}

class NioIpStreamSuite extends IpStreamContract {
  val backend: Backend { type Interface <: IpStreams } = NioBackend
}

class NioIpDatagramSuite extends IpDatagramContract {
  val backend: Backend { type Interface <: IpDatagrams } = NioBackend
}

class NioUnixStreamSuite extends UnixStreamContract {
  val backend: Backend { type Interface <: UnixStreams } = NioBackend
}

class NioFileSuite extends FileContract {
  val backend: Backend { type Interface <: RegularFiles } = NioBackend
}

class NioResolveSuite extends ResolveContract {
  val backend: Backend { type Interface <: Resolving } = NioBackend
  override protected def namedScopedAddresses: List[(String, IpAddress)] = poller.ScopedNames.addresses
}

class NioSimpleIOResolveSuite extends SimpleIOResolveChecks {
  val backend: Backend { type Interface <: Resolving } = NioBackend
}

class NioSimpleIOSocketSuite extends SimpleIOChecks with IpStreamFixture {
  val backend: Backend { type Interface <: IpStreams } = NioBackend
}

class NioSimpleIOIpStreamSuite extends SimpleIOIpStreamChecks {
  val backend: Backend { type Interface <: IpStreams } = NioBackend
}
