package gears.async.io
package reference

import gears.async.io.testkit.*
import gears.io.testkit.SimpleIOChecks
import gears.io.testkit.SimpleIOIpStreamChecks
import gears.io.testkit.SimpleIOResolveChecks

import java.net.Inet6Address
import java.net.NetworkInterface
import scala.jdk.CollectionConverters.*

/** Each suite sees only the capabilities required by its contracts. */
class ReferenceContractSuite extends OpsContract {
  val backend: Backend = ReferenceBackend
}

class ReferenceThreadedTimerSuite extends ThreadedTimerContract {
  val backend: Backend = ReferenceBackend
}

class ReferenceBlockingSuite extends BlockingContract {
  val backend: Backend { val ops: Blocking } = ReferenceBackend
}

class ReferencePipeSuite extends PipeContract {
  val backend: Backend { val ops: Pipes } = ReferenceBackend
}

/** Runs the same stream contracts using TCP, without exposing Pipes to the suite. */
class ReferenceSocketStreamSuite extends StreamContract with IpStreamFixture {
  val backend: Backend { val ops: IpStreams } = ReferenceBackend
}

class ReferenceIpStreamSuite extends IpStreamContract {
  val backend: Backend { val ops: IpStreams } = ReferenceBackend
}

class ReferenceIpDatagramSuite extends IpDatagramContract {
  val backend: Backend { val ops: IpDatagrams } = ReferenceBackend
}

class ReferenceUnixStreamSuite extends UnixStreamContract {
  val backend: Backend { val ops: UnixStreams } = ReferenceBackend
}

class ReferenceFileSuite extends FileContract {
  val backend: Backend { val ops: RegularFiles } = ReferenceBackend
}

class ReferenceResolveSuite extends ResolveContract {
  val backend: Backend { val ops: Resolving } = ReferenceBackend

  override protected def namedScopedAddresses: List[(String, IpAddress)] =
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

class ReferenceSimpleIOSuite extends SimpleIOChecks with PipeFixture {
  val backend: Backend { val ops: Pipes } = ReferenceBackend
}

class ReferenceSimpleIOSocketSuite extends SimpleIOChecks with IpStreamFixture {
  val backend: Backend { val ops: IpStreams } = ReferenceBackend
}

class ReferenceSimpleIOIpStreamSuite extends SimpleIOIpStreamChecks {
  val backend: Backend { val ops: IpStreams } = ReferenceBackend
}

class ReferenceSimpleIOResolveSuite extends SimpleIOResolveChecks {
  val backend: Backend { val ops: Resolving } = ReferenceBackend
}
