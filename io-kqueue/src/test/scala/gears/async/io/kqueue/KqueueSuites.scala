package gears.async.io
package kqueue

import gears.async.Async
import gears.async.AsyncSupport
import gears.async.Future
import gears.async.io.testkit.*
import gears.io.testkit.SimpleIOChecks
import gears.io.testkit.SimpleIOIpStreamChecks

/** Each suite sees only the capabilities required by its contracts. */
class KqueueContractSuite extends OpsContract with FailsOnStarvation {
  val backend: Backend = KqueueBackend
}

class KqueueThreadedTimerSuite extends ThreadedTimerContract with FailsOnStarvation {
  val backend: Backend = KqueueBackend
}

class KqueuePipeSuite extends PipeContract with FailsOnStarvation {
  val backend: KqueueBackend.type = KqueueBackend
}

/** Runs the same stream contracts using TCP, without exposing Pipes to the suite. */
class KqueueSocketStreamSuite extends StreamContract with IpStreamFixture with FailsOnStarvation {
  val backend: Backend { type Interface <: IpStreams } = KqueueBackend
}

class KqueueIpStreamSuite extends IpStreamContract with FailsOnStarvation {
  val backend: Backend { type Interface <: IpStreams } = KqueueBackend
}

class KqueueIpDatagramSuite extends IpDatagramContract with FailsOnStarvation {
  val backend: Backend { type Interface <: IpDatagrams } = KqueueBackend
}

class KqueueUnixDatagramSuite extends UnixDatagramContract with FailsOnStarvation {
  val backend: Backend { type Interface <: UnixDatagrams } = KqueueBackend
}

class KqueueUnixStreamSuite extends UnixStreamContract with FailsOnStarvation {
  val backend: Backend { type Interface <: UnixStreams } = KqueueBackend
}

class KqueueSimpleIOSuite extends SimpleIOChecks with PipeFixture with FailsOnStarvation {
  val backend: KqueueBackend.type = KqueueBackend
}

class KqueueSimpleIOSocketSuite extends SimpleIOChecks with IpStreamFixture with FailsOnStarvation {
  val backend: Backend { type Interface <: IpStreams } = KqueueBackend
}

class KqueueSimpleIOIpStreamSuite extends SimpleIOIpStreamChecks with FailsOnStarvation {
  val backend: Backend { type Interface <: IpStreams } = KqueueBackend
}

/** Runs a suite's programs on a pool of `count` workers rather than `Io.run`'s one for each processor. On one worker
  * every op waits on the worker that performs it, and is done, when its handle is ready, by that same thread.
  */
trait OnWorkers(count: Int) extends FailsOnStarvation {
  override protected def workers: Int = count
}

class KqueueOnOneContractSuite extends OpsContract with OnWorkers(1) with FailsOnStarvation {
  val backend: Backend = KqueueBackend
}

class KqueueOnOnePipeSuite extends PipeContract with OnWorkers(1) with FailsOnStarvation {
  val backend: KqueueBackend.type = KqueueBackend
}

class KqueueOnOneSocketStreamSuite
    extends StreamContract
    with IpStreamFixture
    with OnWorkers(1)
    with FailsOnStarvation {
  val backend: Backend { type Interface <: IpStreams } = KqueueBackend
}

class KqueueOnOneIpStreamSuite extends IpStreamContract with OnWorkers(1) with FailsOnStarvation {
  val backend: Backend { type Interface <: IpStreams } = KqueueBackend
}

class KqueueOnOneIpDatagramSuite extends IpDatagramContract with OnWorkers(1) with FailsOnStarvation {
  val backend: Backend { type Interface <: IpDatagrams } = KqueueBackend
}

class KqueueOnOneUnixStreamSuite extends UnixStreamContract with OnWorkers(1) with FailsOnStarvation {
  val backend: Backend { type Interface <: UnixStreams } = KqueueBackend
}

class KqueueOnTwoIpStreamSuite extends IpStreamContract with OnWorkers(2) with FailsOnStarvation {
  val backend: Backend { type Interface <: IpStreams } = KqueueBackend
}

class KqueueOnTwoIpDatagramSuite extends IpDatagramContract with OnWorkers(2) with FailsOnStarvation {
  val backend: Backend { type Interface <: IpDatagrams } = KqueueBackend
}
