package gears.async.io
package poller

import gears.async.io.testkit.*
import gears.io.testkit.SimpleIOChecks
import gears.io.testkit.SimpleIOIpStreamChecks
import gears.io.testkit.SimpleIOResolveChecks

import java.net.Inet6Address
import java.net.NetworkInterface
import scala.jdk.CollectionConverters.*

// The contracts of every capability the pool backends have in common, over the pool backend of the platform the tests
// are built for (`PollerPlatform.backend`): `NioPoolBackend` on the JVM, and `EventPollingBackend` on Scala Native,
// which is kqueue's or epoll's by the system. Each suite sees only the capabilities its contracts ask for.

/** The names a link-local IPv6 address of this machine has with its interface's name for a scope. */
private[io] object ScopedNames {
  def addresses: List[(String, IpAddress)] =
    NetworkInterface
      .getNetworkInterfaces()
      .asScala
      .flatMap(interface =>
        interface.getInetAddresses().asScala.collect {
          case address: Inet6Address if address.isLinkLocalAddress() && address.getScopeId() > 0 =>
            (interface.getName(), address)
        }
      )
      .nextOption()
      .toList
      .flatMap { (name, address) =>
        val expected = IpAddress.fromBytes(Protocol.IPv6, address.getAddress(), address.getScopeId())
        val compressed = expected.toString.takeWhile(_ != '%')
        val expanded = address.getHostAddress().takeWhile(_ != '%')
        List(s"$compressed%$name", s"$expanded%$name", s"[$compressed%$name]").map(_ -> expected)
      }
}

/** Runs a suite's programs on a pool of `count` workers rather than `Io.run`'s one for each processor. On one worker
  * every op waits on the worker that performs it, and is done, when its handle is ready, by that same thread.
  */
trait OnWorkers(count: Int) extends FailsOnStarvation {
  override protected def workers: Int = count
}

class PoolContractSuite extends OpsContract with FailsOnStarvation {
  val backend: Backend = PollerPlatform.backend
}

class PoolThreadedTimerSuite extends ThreadedTimerContract with FailsOnStarvation {
  val backend: Backend = PollerPlatform.backend
}

class PoolBlockingSuite extends BlockingContract with FailsOnStarvation {
  val backend: Backend { type Interface <: Blocking } = PollerPlatform.backend
}

class PoolPipeSuite extends PipeContract with FailsOnStarvation {
  val backend: PollerPlatform.backend.type = PollerPlatform.backend
}

/** Runs the same stream contracts using TCP, without exposing Pipes to the suite. */
class PoolSocketStreamSuite extends StreamContract with IpStreamFixture with FailsOnStarvation {
  val backend: Backend { type Interface <: IpStreams } = PollerPlatform.backend
}

class PoolIpStreamSuite extends IpStreamContract with FailsOnStarvation {
  val backend: Backend { type Interface <: IpStreams } = PollerPlatform.backend
}

class PoolIpDatagramSuite extends IpDatagramContract with FailsOnStarvation {
  val backend: Backend { type Interface <: IpDatagrams } = PollerPlatform.backend
}

class PoolUnixStreamSuite extends UnixStreamContract with FailsOnStarvation {
  val backend: Backend { type Interface <: UnixStreams } = PollerPlatform.backend
}

class PoolFileSuite extends FileContract with FailsOnStarvation {
  val backend: Backend { type Interface <: RegularFiles } = PollerPlatform.backend
}

class PoolResolveSuite extends ResolveContract with FailsOnStarvation {
  val backend: Backend { type Interface <: Resolving } = PollerPlatform.backend
  override protected def namedScopedAddresses: List[(String, IpAddress)] = ScopedNames.addresses
}

class PoolSimpleIOSuite extends SimpleIOChecks with PipeFixture with FailsOnStarvation {
  val backend: PollerPlatform.backend.type = PollerPlatform.backend
}

class PoolSimpleIOSocketSuite extends SimpleIOChecks with IpStreamFixture with FailsOnStarvation {
  val backend: Backend { type Interface <: IpStreams } = PollerPlatform.backend
}

class PoolSimpleIOIpStreamSuite extends SimpleIOIpStreamChecks with FailsOnStarvation {
  val backend: Backend { type Interface <: IpStreams } = PollerPlatform.backend
}

class PoolSimpleIOResolveSuite extends SimpleIOResolveChecks with FailsOnStarvation {
  val backend: Backend { type Interface <: Resolving } = PollerPlatform.backend
}

// The same on one worker, and for sockets on two.

class PoolOnOneContractSuite extends OpsContract with OnWorkers(1) {
  val backend: Backend = PollerPlatform.backend
}

class PoolOnOneBlockingSuite extends BlockingContract with OnWorkers(1) {
  val backend: Backend { type Interface <: Blocking } = PollerPlatform.backend
}

class PoolOnOnePipeSuite extends PipeContract with OnWorkers(1) {
  val backend: PollerPlatform.backend.type = PollerPlatform.backend
}

class PoolOnOneSocketStreamSuite extends StreamContract with IpStreamFixture with OnWorkers(1) {
  val backend: Backend { type Interface <: IpStreams } = PollerPlatform.backend
}

class PoolOnOneIpStreamSuite extends IpStreamContract with OnWorkers(1) {
  val backend: Backend { type Interface <: IpStreams } = PollerPlatform.backend
}

class PoolOnOneIpDatagramSuite extends IpDatagramContract with OnWorkers(1) {
  val backend: Backend { type Interface <: IpDatagrams } = PollerPlatform.backend
}

class PoolOnOneUnixStreamSuite extends UnixStreamContract with OnWorkers(1) {
  val backend: Backend { type Interface <: UnixStreams } = PollerPlatform.backend
}

class PoolOnOneFileSuite extends FileContract with OnWorkers(1) {
  val backend: Backend { type Interface <: RegularFiles } = PollerPlatform.backend
}

class PoolOnTwoIpStreamSuite extends IpStreamContract with OnWorkers(2) {
  val backend: Backend { type Interface <: IpStreams } = PollerPlatform.backend
}

class PoolOnTwoIpDatagramSuite extends IpDatagramContract with OnWorkers(2) {
  val backend: Backend { type Interface <: IpDatagrams } = PollerPlatform.backend
}

// What a run leaves when it ends, and what passes between runs: on the pool and on one worker.

class PoolRunEndSuite extends RunEndContract with FailsOnStarvation {
  val backend: Backend { type Interface <: IpStreams } = PollerPlatform.backend
}

class PoolOnOneRunEndSuite extends RunEndContract with OnWorkers(1) {
  val backend: Backend { type Interface <: IpStreams } = PollerPlatform.backend
}

class PoolThreadedRunSuite extends ThreadedRunContract with FailsOnStarvation {
  val backend: Backend { type Interface <: Blocking & IpStreams } = PollerPlatform.backend
}

class PoolOnOneThreadedRunSuite extends ThreadedRunContract with OnWorkers(1) {
  val backend: Backend { type Interface <: Blocking & IpStreams } = PollerPlatform.backend
}
