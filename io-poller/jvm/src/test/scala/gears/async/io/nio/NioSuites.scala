package gears.async.io
package nio

import gears.async.io.testkit.*
import gears.io.testkit.SimpleIOChecks
import gears.io.testkit.SimpleIOIpStreamChecks
import gears.io.testkit.SimpleIOResolveChecks

import java.net.Inet6Address
import java.net.NetworkInterface
import scala.jdk.CollectionConverters.*

/** The names a link-local IPv6 address of this machine has with its interface's name for a scope. */
private[nio] object ScopedNames {
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
  override protected def namedScopedAddresses: List[(String, IpAddress)] = ScopedNames.addresses
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

// The same contracts with the futures carried by the pool's workers.

class NioPoolContractSuite extends OpsContract {
  val backend: Backend = NioPoolBackend
}

class NioPoolThreadedTimerSuite extends ThreadedTimerContract {
  val backend: Backend = NioPoolBackend
}

class NioPoolBlockingSuite extends BlockingContract {
  val backend: Backend { type Interface <: Blocking } = NioPoolBackend
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

class NioPoolFileSuite extends FileContract {
  val backend: Backend { type Interface <: RegularFiles } = NioPoolBackend
}

class NioPoolResolveSuite extends ResolveContract {
  val backend: Backend { type Interface <: Resolving } = NioPoolBackend
  override protected def namedScopedAddresses: List[(String, IpAddress)] = ScopedNames.addresses
}

class NioPoolSimpleIOResolveSuite extends SimpleIOResolveChecks {
  val backend: Backend { type Interface <: Resolving } = NioPoolBackend
}

class NioPoolSimpleIOSocketSuite extends SimpleIOChecks with IpStreamFixture {
  val backend: Backend { type Interface <: IpStreams } = NioPoolBackend
}

class NioPoolSimpleIOIpStreamSuite extends SimpleIOIpStreamChecks {
  val backend: Backend { type Interface <: IpStreams } = NioPoolBackend
}
