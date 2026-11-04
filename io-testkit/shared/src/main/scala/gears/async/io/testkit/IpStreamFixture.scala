package gears.async.io
package testkit

import gears.async.Async
import gears.async.Future

/** Listener and connected stream fixtures requiring only IpStreams. */
trait IpStreamFixture extends StreamFixture {
  val backend: Backend & IpStreams
  protected type Connection = backend.Connection
  protected type Listener = backend.Listener

  protected trait Connect {
    def apply()(using Async): Connection
  }

  protected def listenAndConnect(io: Io[backend.type])(using Async): (Listener, Connect) = {
    val into = Slot[Listener]()
    val listener = opening(io, into, io.backend.ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), into))
    val address = io.backend.ops.localAddress(listener).asInstanceOf[IpSocketAddress]
    val connect = new Connect {
      def apply()(using Async): Connection = {
        val into = Slot[Connection]()
        opening(io, into, io.backend.ops.connect(address, into))
      }
    }
    (listener, connect)
  }

  protected def stream(io: Io[backend.type])(using Async.Spawn): (Readable, Writable) = {
    val (listener, connect) = listenAndConnect(io)
    try {
      val into = Slot[Connection]()
      val accepting = Future(opening(io, into, io.backend.ops.accept(listener, into)))
      val writer = connect()
      try (accepting.await, writer)
      catch {
        case failure: Throwable =>
          close(io, writer)
          throw failure
      }
    } finally close(io, listener)
  }
}
