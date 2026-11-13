package gears.async.io
package testkit

import gears.async.Async
import gears.async.Future

/** Listener and connected stream fixtures requiring only IpStreams. */
trait IpStreamFixture extends StreamFixture {
  val backend: Backend & IpStreams
  protected type Connection = backend.ops.Connection
  protected type Listener = backend.ops.Listener

  protected trait Connect {
    def apply()(using Async): Connection
  }

  protected def listenAndConnect(io: Io[backend.type])(using Async): (Listener, Connect) = {
    val listener =
      opening(io)(slot => io.backend.ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), slot))
    val address = io.backend.ops.localAddress(listener).asInstanceOf[IpSocketAddress]
    val connect = new Connect {
      def apply()(using Async): Connection = {
        opening(io)(slot => io.backend.ops.connect(address, slot))
      }
    }
    (listener, connect)
  }

  /** Pairs a connection from the supplied opener with the connection accepted by `listener`. */
  protected def pair(io: Io[backend.type], listener: Listener)(connect: Async ?=> Connection)(using
      Async.Spawn
  ): (Connection, Connection) = {
    val accepting = Future(opening(io)(slot => io.backend.ops.accept(listener, slot)))
    try {
      val client = connect
      try (client, accepting.await)
      catch {
        case failure: Throwable =>
          close(io, client)
          throw failure
      }
    } catch {
      case failure: Throwable =>
        accepting.cancel()
        accepting.awaitResult
        throw failure
    }
  }

  protected def stream(io: Io[backend.type])(using Async.Spawn): (Readable, Writable) = {
    val (listener, connect) = listenAndConnect(io)
    try {
      val (writer, reader) = pair(io, listener)(connect())
      (reader, writer)
    } finally close(io, listener)
  }
}
