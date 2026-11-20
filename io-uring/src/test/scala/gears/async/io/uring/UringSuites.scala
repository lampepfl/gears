package gears.async.io
package uring

import gears.async.Async
import gears.async.io.testkit.*
import gears.io.testkit.SimpleIOChecks
import gears.io.testkit.SimpleIOIpStreamChecks
import gears.io.testkit.SimpleIOResolveChecks

/** Runs a suite's programs on a pool of `count` workers. */
trait OnWorkers(count: Int) extends ContractSuite {
  override protected def running[T](body: Io[backend.Interface] ?=> Async.Spawn ?=> T): T =
    UringBackend.runOn(count)(io ?=> body(using io.asInstanceOf[Io[backend.Interface]]))
}

class UringContractSuite extends OpsContract {
  val backend: Backend = UringBackend
}

class UringThreadedTimerSuite extends ThreadedTimerContract {
  val backend: Backend = UringBackend
}

class UringBlockingSuite extends BlockingContract {
  val backend: Backend { type Interface <: Blocking } = UringBackend
}

class UringPipeSuite extends PipeContract {
  val backend: UringBackend.type = UringBackend
}

class UringSocketStreamSuite extends StreamContract with IpStreamFixture {
  val backend: Backend { type Interface <: IpStreams } = UringBackend
}

class UringIpStreamSuite extends IpStreamContract {
  val backend: Backend { type Interface <: IpStreams } = UringBackend
}

class UringIpDatagramSuite extends IpDatagramContract {
  val backend: Backend { type Interface <: IpDatagrams } = UringBackend
}

class UringUnixStreamSuite extends UnixStreamContract {
  val backend: Backend { type Interface <: UnixStreams } = UringBackend
}

class UringUnixDatagramSuite extends UnixDatagramContract {
  val backend: Backend { type Interface <: UnixDatagrams } = UringBackend
}

class UringFileSuite extends FileContract {
  val backend: Backend { type Interface <: RegularFiles } = UringBackend
}

class UringFifoSuite extends FifoContract {
  val backend: Backend { type Interface <: Fifos } = UringBackend
}

class UringFifoFileSuite extends FifoFileContract {
  val backend: Backend { type Interface <: Fifos & RegularFiles } = UringBackend
}

class UringResolveSuite extends ResolveContract {
  val backend: Backend { type Interface <: Resolving } = UringBackend
}

class UringSimpleIOSuite extends SimpleIOChecks with PipeFixture {
  val backend: UringBackend.type = UringBackend
}

class UringSimpleIOSocketSuite extends SimpleIOChecks with IpStreamFixture {
  val backend: Backend { type Interface <: IpStreams } = UringBackend
}

class UringSimpleIOIpStreamSuite extends SimpleIOIpStreamChecks {
  val backend: Backend { type Interface <: IpStreams } = UringBackend
}

class UringSimpleIOResolveSuite extends SimpleIOResolveChecks {
  val backend: Backend { type Interface <: Resolving } = UringBackend
}

// ---- the same on one worker, where every op is performed and completed on the one ring

class UringOnOneContractSuite extends OpsContract with OnWorkers(1) {
  val backend: Backend = UringBackend
}

class UringOnOneBlockingSuite extends BlockingContract with OnWorkers(1) {
  val backend: Backend { type Interface <: Blocking } = UringBackend
}

class UringOnOnePipeSuite extends PipeContract with OnWorkers(1) {
  val backend: UringBackend.type = UringBackend
}

class UringOnOneSocketStreamSuite extends StreamContract with IpStreamFixture with OnWorkers(1) {
  val backend: Backend { type Interface <: IpStreams } = UringBackend
}

class UringOnOneIpStreamSuite extends IpStreamContract with OnWorkers(1) {
  val backend: Backend { type Interface <: IpStreams } = UringBackend
}

class UringOnOneIpDatagramSuite extends IpDatagramContract with OnWorkers(1) {
  val backend: Backend { type Interface <: IpDatagrams } = UringBackend
}

class UringOnOneUnixStreamSuite extends UnixStreamContract with OnWorkers(1) {
  val backend: Backend { type Interface <: UnixStreams } = UringBackend
}

class UringOnOneFileSuite extends FileContract with OnWorkers(1) {
  val backend: Backend { type Interface <: RegularFiles } = UringBackend
}

class UringOnTwoIpStreamSuite extends IpStreamContract with OnWorkers(2) {
  val backend: Backend { type Interface <: IpStreams } = UringBackend
}

class UringRunEndSuite extends gears.async.io.poller.RunEndContract {
  val backend: UringBackend.type = UringBackend
}

class UringOnOneRunEndSuite extends gears.async.io.poller.RunEndContract with OnWorkers(1) {
  val backend: UringBackend.type = UringBackend
}
