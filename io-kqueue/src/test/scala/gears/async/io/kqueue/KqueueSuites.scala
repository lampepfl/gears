package gears.async.io
package kqueue

import gears.async.io.testkit.*
import gears.io.testkit.SimpleIOChecks
import gears.io.testkit.SimpleIOIpStreamChecks
import gears.io.testkit.SimpleIOResolveChecks

import java.net.Inet6Address
import java.net.NetworkInterface
import scala.jdk.CollectionConverters.*

/** Each suite sees only the capabilities required by its contracts. */
class KqueueContractSuite extends OpsContract {
  val backend: Backend = KqueueBackend
}

class KqueueThreadedTimerSuite extends ThreadedTimerContract {
  val backend: Backend = KqueueBackend
}

// class KqueueBlockingSuite extends BlockingContract {
//   val backend: Blocking = KqueueBackend
// }

// class KqueuePipeSuite extends PipeContract {
//   val backend: Pipes = KqueueBackend
// }

// /** Runs the same stream contracts using TCP, without exposing Pipes to the suite. */
// class KqueueSocketStreamSuite extends StreamContract with IpStreamFixture {
//   val backend: IpStreams = KqueueBackend
// }

// class KqueueIpStreamSuite extends IpStreamContract {
//   val backend: IpStreams = KqueueBackend
// }

// class KqueueIpDatagramSuite extends IpDatagramContract {
//   val backend: IpDatagrams = KqueueBackend
// }

// class KqueueUnixStreamSuite extends UnixStreamContract {
//   val backend: UnixStreams = KqueueBackend
// }

// class KqueueFileSuite extends FileContract {
//   val backend: RegularFiles = KqueueBackend
// }

// class KqueueResolveSuite extends ResolveContract {
//   val backend: Resolving = KqueueBackend

//   override protected def namedScopedAddresses: List[(String, IpAddress)] =
//     NetworkInterface
//       .getNetworkInterfaces()
//       .asScala
//       .flatMap(interface =>
//         interface.getInetAddresses().asScala.collect {
//           case address: Inet6Address if address.isLinkLocalAddress() && address.getScopeId() > 0 =>
//             (interface.getName(), address)
//         }
//       )
//       .nextOption()
//       .toList
//       .flatMap { (name, address) =>
//         val expected = IpAddress.fromBytes(Protocol.IPv6, address.getAddress(), address.getScopeId())
//         val compressed = expected.toString.takeWhile(_ != '%')
//         val expanded = address.getHostAddress().takeWhile(_ != '%')
//         List(s"$compressed%$name", s"$expanded%$name", s"[$compressed%$name]").map(_ -> expected)
//       }
// }

// class KqueueSimpleIOSuite extends SimpleIOChecks with PipeFixture {
//   val backend: Pipes = KqueueBackend
// }

// class KqueueSimpleIOSocketSuite extends SimpleIOChecks with IpStreamFixture {
//   val backend: IpStreams = KqueueBackend
// }

// class KqueueSimpleIOIpStreamSuite extends SimpleIOIpStreamChecks {
//   val backend: IpStreams = KqueueBackend
// }

// class KqueueSimpleIOResolveSuite extends SimpleIOResolveChecks {
//   val backend: Resolving = KqueueBackend
// }
