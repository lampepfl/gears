package example

import gears.async.io.internal.Completion
import gears.async.io.internal.Interest
import gears.async.io.internal.NativeBuffer
import gears.async.io.internal.PosixSockets
import gears.async.io.internal.Sockets.Transport
import gears.async.io.kqueue.Accept
import gears.async.io.kqueue.Command
import gears.async.io.kqueue.KqueueBackend.Handle
import gears.async.io.kqueue.KqueueHandles
import gears.async.io.kqueue.KqueueKinds
import gears.async.io.kqueue.KqueueOps
import gears.async.io.kqueue.KqueueReactor

import java.io.IOException
import java.nio.ByteBuffer
import scala.scalanative.unsafe.Zone

import KQueueExampleIO.*

object KQueueExampleServerSocket {
  private val Response = "Just pinging back!\n"

  private enum State {
    case ReadRequest, SendResponse
  }

  /** One accepted connection: reads a length-framed request exactly (`FrameReader`), then replies with a framed
    * response and closes. Owns a zone holding its single 8 KiB buffer, through which a body larger than that is read a
    * buffer's worth at a time. The zone closes with the connection.
    */
  private final class Connection(r: KqueueReactor, val fd: KqueueKinds.Connection, server: Server)
      extends AutoCloseable
      with Completion[Command] {
    private var state = State.ReadRequest
    private val zone: Zone = Zone.open()
    private val buf: ByteBuffer = NativeBuffer.allocate(8192)(using zone)
    private val reader = new FrameReader(buf)

    inline val KeepOpen = true
    inline val Close = false

    private var closing = false

    /** A step of the connection's state machine, as a command. It is done when the connection must close, or, for
      * reading, when the request is complete and it is time to reply; otherwise the reactor keeps waiting.
      */
    private final class Step(val interest: Interest, step: () => Boolean) extends Command {
      def fd: Int = KqueueHandles.descriptor(Connection.this.fd) // a kqueue command names its descriptor
      def perform(): Boolean = {
        val keepOpen =
          try step()
          catch {
            case e: IOException =>
              // One misbehaving client must not take the server down.
              println(s"Client socket $fd failed: ${e.getMessage}")
              Close
          }
        if !keepOpen then closing = true
        closing || (interest == Interest.Read && state == State.SendResponse)
      }
    }

    private val readStep = new Step(Interest.Read, () => readEvent())
    private val writeStep = new Step(Interest.Write, () => writeEvent())

    def start(): Unit = r.submit(readStep, this)

    def onComplete(command: Command): Unit =
      if closing then server.drop(this)
      else r.submit(writeStep, this) // only reading finishes without closing, once the request is in

    /** Handles readability. Returns false when the connection should be closed. */
    def readEvent(): Boolean = {
      if state == State.SendResponse then return KeepOpen // Ignore stray read events while replying.
      val read = nioReadBytes(KqueueHandles.descriptor(fd), buf)
      if read < 0 then
        println(s"Unexpected EOF from client socket $fd.")
        Close
      else
        try
          val request = reader.advance() // the buffer's limit stays at what is expected next
          if request != null then prepareResponse(request)
          KeepOpen
        catch
          case invalid: InvalidFrame =>
            println(s"Invalid message size from client socket $fd: ${invalid.length}")
            Close
    }

    /** Handles writability. Returns false once the whole response has been sent. */
    def writeEvent(): Boolean = {
      if state != State.SendResponse then return KeepOpen // ignore stray write events while not sending a response.
      nioWriteBytes(KqueueHandles.descriptor(fd), buf)
      if buf.hasRemaining() then KeepOpen else Close
    }

    private def prepareResponse(request: String): Unit = {
      println(s"message contents: `$request`")
      state = State.SendResponse
      StreamProtocol.frame(Response, buf) // framed, so that the client knows when it has all of it
    }

    def close(): Unit =
      zone.close()
      KqueueHandles.close(fd)
      println(s"Closed client socket: ${fd}")
  }

  /** Accepts one connection per readiness event and starts reading from it. */
  private class Server(r: KqueueReactor, serverFd: Int) extends Completion[Accept] {
    private var connections: Map[Handle, Connection] = Map.empty

    private val accepted = gears.async.io.Slot[KqueueKinds.Connection]() // each accept writes its connection here

    def start(): Unit = r.submit(Accept(serverFd, accepted), this)

    def onComplete(command: Accept): Unit = {
      val clientFd = accepted.clear()
      val connection = new Connection(r, clientFd, this)
      connections += clientFd -> connection
      connection.start()
      println(s"Accepted new client connection on serverFd: $clientFd")
      r.submit(command, this)
    }

    def drop(connection: Connection): Unit = {
      connections -= connection.fd
      // No EV_DELETE needed: kqueue(2) states that "calling close() on a file descriptor will remove any
      // kevents that reference the descriptor". The knote is keyed by descriptor number, not by the open file,
      // so this holds even if a dup() of the descriptor stays open (unlike epoll, which keys on the open file).
      connection.close()
    }

    def close(): Unit = {
      val old = connections
      connections = Map.empty
      old.values.foreach(_.close())
    }
  }

  def run(sock: String): Unit = run(KQueueExampleAddress.Unix(sock))

  def run(address: KQueueExampleAddress): Unit = {
    KqueueReactor.scoped() { r =>
      address.withBoundSocket(Transport.Stream) { serverFd =>
        PosixSockets.listen(serverFd, PosixSockets.maxConnections)
        println("Socket is now listening for connections.")
        val server = new Server(r, serverFd)
        server.start()
        try r.run()
        finally server.close()
      }
    }
  }
}
