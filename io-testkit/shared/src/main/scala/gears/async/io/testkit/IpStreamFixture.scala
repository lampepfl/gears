package gears.async.io
package testkit

import gears.async.Async
import gears.async.Future

/** Listener and connected stream fixtures requiring only IpStreams. */
trait IpStreamFixture extends StreamFixture {
  val backend: Backend { type Interface <: IpStreams }
  protected trait Connect[C] {
    def apply()(using Async): C
  }

  protected def listenAndConnect(
      io: Io[backend.Interface]
  )(using Async): (io.ops.Listener, Connect[io.ops.Connection]) = {
    val listener =
      opening(io)(slot => io.ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), slot))
    val address = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress]
    val connect = new Connect[io.ops.Connection] {
      def apply()(using Async): io.ops.Connection = {
        opening(io)(slot => io.ops.connect(address, slot))
      }
    }
    (listener, connect)
  }

  /** Pairs a connection from the supplied opener with the connection accepted by `listener`. */
  protected def pair(io: Io[backend.Interface], listener: io.ops.Listener)(connect: Async ?=> io.ops.Connection)(using
      Async.Spawn
  ): (io.ops.Connection, io.ops.Connection) = {
    val accepting = Future(opening(io)(slot => io.ops.accept(listener, slot)))
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

  protected def stream(io: Io[backend.Interface])(using Async.Spawn): (io.ops.Readable, io.ops.Writable) = {
    val (listener, connect) = listenAndConnect(io)
    try {
      val (writer, reader) = pair(io, listener)(connect())
      (reader, writer)
    } finally close(io, listener)
  }
}
