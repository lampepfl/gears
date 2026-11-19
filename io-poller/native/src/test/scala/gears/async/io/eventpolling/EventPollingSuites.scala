package gears.async.io
package eventpolling

import gears.async.Async
import gears.async.AsyncSupport
import gears.async.Future
import gears.async.io.testkit.*
import gears.io.testkit.SimpleIOChecks
import gears.io.testkit.SimpleIOIpStreamChecks
import gears.io.testkit.SimpleIOResolveChecks

import java.net.Inet6Address
import java.net.NetworkInterface
import scala.jdk.CollectionConverters.*

/** Each suite sees only the capabilities required by its contracts. */
class EventPollingContractSuite extends OpsContract with FailsOnStarvation {
  val backend: Backend = EventPollingBackend
}

class EventPollingThreadedTimerSuite extends ThreadedTimerContract with FailsOnStarvation {
  val backend: Backend = EventPollingBackend
}

class EventPollingBlockingSuite extends BlockingContract with FailsOnStarvation {
  val backend: Backend { type Interface <: Blocking } = EventPollingBackend
}

class EventPollingPipeSuite extends PipeContract with FailsOnStarvation {
  val backend: EventPollingBackend.type = EventPollingBackend
}

/** Runs the same stream contracts using TCP, without exposing Pipes to the suite. */
class EventPollingSocketStreamSuite extends StreamContract with IpStreamFixture with FailsOnStarvation {
  val backend: Backend { type Interface <: IpStreams } = EventPollingBackend
}

class EventPollingIpStreamSuite extends IpStreamContract with FailsOnStarvation {
  val backend: Backend { type Interface <: IpStreams } = EventPollingBackend
}

class EventPollingIpDatagramSuite extends IpDatagramContract with FailsOnStarvation {
  val backend: Backend { type Interface <: IpDatagrams } = EventPollingBackend
}

class EventPollingUnixDatagramSuite extends UnixDatagramContract with FailsOnStarvation {
  val backend: Backend { type Interface <: UnixDatagrams } = EventPollingBackend
}

class EventPollingUnixStreamSuite extends UnixStreamContract with FailsOnStarvation {
  val backend: Backend { type Interface <: UnixStreams } = EventPollingBackend
}

class EventPollingFileSuite extends FileContract with FailsOnStarvation {
  val backend: Backend { type Interface <: RegularFiles } = EventPollingBackend
}

class EventPollingResolveSuite extends ResolveContract with FailsOnStarvation {
  val backend: Backend { type Interface <: Resolving } = EventPollingBackend

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

class EventPollingFifoSuite extends FifoContract with FailsOnStarvation {
  val backend: Backend { type Interface <: Fifos } = EventPollingBackend
}

class EventPollingFifoFileSuite extends FifoFileContract with FailsOnStarvation {
  val backend: Backend { type Interface <: Fifos & RegularFiles } = EventPollingBackend
}

class EventPollingSimpleIOSuite extends SimpleIOChecks with PipeFixture with FailsOnStarvation {
  val backend: EventPollingBackend.type = EventPollingBackend
}

class EventPollingSimpleIOSocketSuite extends SimpleIOChecks with IpStreamFixture with FailsOnStarvation {
  val backend: Backend { type Interface <: IpStreams } = EventPollingBackend
}

class EventPollingSimpleIOIpStreamSuite extends SimpleIOIpStreamChecks with FailsOnStarvation {
  val backend: Backend { type Interface <: IpStreams } = EventPollingBackend
}

class EventPollingSimpleIOResolveSuite extends SimpleIOResolveChecks with FailsOnStarvation {
  val backend: Backend { type Interface <: Resolving } = EventPollingBackend
}

/** Runs a suite's programs on a pool of `count` workers rather than `Io.run`'s one for each processor. On one worker
  * every op waits on the worker that performs it, and is done, when its handle is ready, by that same thread.
  */
trait OnWorkers(count: Int) extends FailsOnStarvation {
  override protected def workers: Int = count
}

class EventPollingOnOneContractSuite extends OpsContract with OnWorkers(1) with FailsOnStarvation {
  val backend: Backend = EventPollingBackend
}

class EventPollingOnOneBlockingSuite extends BlockingContract with OnWorkers(1) with FailsOnStarvation {
  val backend: Backend { type Interface <: Blocking } = EventPollingBackend
}

class EventPollingOnOnePipeSuite extends PipeContract with OnWorkers(1) with FailsOnStarvation {
  val backend: EventPollingBackend.type = EventPollingBackend
}

class EventPollingOnOneSocketStreamSuite
    extends StreamContract
    with IpStreamFixture
    with OnWorkers(1)
    with FailsOnStarvation {
  val backend: Backend { type Interface <: IpStreams } = EventPollingBackend
}

class EventPollingOnOneIpStreamSuite extends IpStreamContract with OnWorkers(1) with FailsOnStarvation {
  val backend: Backend { type Interface <: IpStreams } = EventPollingBackend
}

class EventPollingOnOneIpDatagramSuite extends IpDatagramContract with OnWorkers(1) with FailsOnStarvation {
  val backend: Backend { type Interface <: IpDatagrams } = EventPollingBackend
}

class EventPollingOnOneUnixStreamSuite extends UnixStreamContract with OnWorkers(1) with FailsOnStarvation {
  val backend: Backend { type Interface <: UnixStreams } = EventPollingBackend
}

class EventPollingOnOneFileSuite extends FileContract with OnWorkers(1) with FailsOnStarvation {
  val backend: Backend { type Interface <: RegularFiles } = EventPollingBackend
}

class EventPollingOnTwoIpStreamSuite extends IpStreamContract with OnWorkers(2) with FailsOnStarvation {
  val backend: Backend { type Interface <: IpStreams } = EventPollingBackend
}

class EventPollingOnTwoIpDatagramSuite extends IpDatagramContract with OnWorkers(2) with FailsOnStarvation {
  val backend: Backend { type Interface <: IpDatagrams } = EventPollingBackend
}

// What a run leaves when it ends: on the pool and on one worker.
class EventPollingRunEndSuite extends RunEndContract with FailsOnStarvation {
  val backend: Backend { type Interface <: Blocking & Pipes } = EventPollingBackend
}

class EventPollingOnOneRunEndSuite extends RunEndContract with OnWorkers(1) with FailsOnStarvation {
  val backend: Backend { type Interface <: Blocking & Pipes } = EventPollingBackend
}
