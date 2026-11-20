package gears.async.io
package eventpolling

import gears.async.io.poller.FailsOnStarvation
import gears.async.io.testkit.*

// The contracts of the capabilities the native backend has and the JVM's does not, over kqueue or epoll by the system.
// Those the pool backends have in common are checked from the shared tests (`gears.async.io.poller.PoolSuites`).

class EventPollingUnixDatagramSuite extends UnixDatagramContract with FailsOnStarvation {
  val backend: Backend { type Interface <: UnixDatagrams } = EventPollingBackend
}

class EventPollingFifoSuite extends FifoContract with FailsOnStarvation {
  val backend: Backend { type Interface <: Fifos } = EventPollingBackend
}

class EventPollingFifoFileSuite extends FifoFileContract with FailsOnStarvation {
  val backend: Backend { type Interface <: Fifos & RegularFiles } = EventPollingBackend
}
