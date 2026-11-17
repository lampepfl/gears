package gears.async.io
package nio

import gears.async.io.testkit.*
import gears.io.testkit.SimpleIOChecks
import gears.io.testkit.SimpleIOIpStreamChecks

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

class NioSimpleIOSocketSuite extends SimpleIOChecks with IpStreamFixture {
  val backend: Backend { type Interface <: IpStreams } = NioBackend
}

class NioSimpleIOIpStreamSuite extends SimpleIOIpStreamChecks {
  val backend: Backend { type Interface <: IpStreams } = NioBackend
}

// The same contracts with the futures carried by the pool's workers.

class NioPoolContractSuite extends OpsContract {
  val backend: Backend = NioPoolBackend
}

class NioPoolThreadedTimerSuite extends ThreadedTimerContract {
  val backend: Backend = NioPoolBackend
}

class NioPoolPipeSuite extends PipeContract {
  val backend: NioPoolBackend.type = NioPoolBackend
}

class NioPoolSimpleIOSuite extends SimpleIOChecks with PipeFixture {
  val backend: NioPoolBackend.type = NioPoolBackend
}

class NioPoolSocketStreamSuite extends StreamContract with IpStreamFixture {
  val backend: Backend { type Interface <: IpStreams } = NioPoolBackend
}

class NioPoolIpStreamSuite extends IpStreamContract {
  val backend: Backend { type Interface <: IpStreams } = NioPoolBackend
}

class NioPoolIpDatagramSuite extends IpDatagramContract {
  val backend: Backend { type Interface <: IpDatagrams } = NioPoolBackend
}

class NioPoolUnixStreamSuite extends UnixStreamContract {
  val backend: Backend { type Interface <: UnixStreams } = NioPoolBackend
}

class NioPoolSimpleIOSocketSuite extends SimpleIOChecks with IpStreamFixture {
  val backend: Backend { type Interface <: IpStreams } = NioPoolBackend
}

class NioPoolSimpleIOIpStreamSuite extends SimpleIOIpStreamChecks {
  val backend: Backend { type Interface <: IpStreams } = NioPoolBackend
}
