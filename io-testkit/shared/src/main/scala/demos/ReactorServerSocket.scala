package gears.io.demos

import gears.async.io.IpAddress
import gears.async.io.IpStreams
import gears.async.io.Repeatable
import gears.async.io.Slot
import gears.async.io.StreamSockets
import gears.async.io.UnixAddress
import gears.async.io.UnixStreams
import gears.async.io.internal.Completion
import gears.async.io.internal.Reactor
import gears.async.io.internal.ReactorBackend
import gears.async.io.testkit.BufferArena

import java.io.IOException
import java.util.concurrent.TimeoutException

import DemoIO.{FrameReader, InvalidFrame, StreamProtocol}

/** `example.KQueueExampleServerSocket` written against the interface alone: the same state machine of completions on a
  * reactor the program drives itself, with no futures, but each step an op of the backend's (`read`, `write`,
  * `shutdownOutput`, `close`) where kqueue's steps are commands run on readiness, reading and writing a descriptor
  * themselves. A completion learns that its op finished, not that its handle is ready, so the same code runs on any
  * reactor backend: kqueue, NIO, or one such as Network.framework, whose connections have no readiness to wait for.
  */
object ReactorServerSocket {
  private val Response = "Just pinging back!\n"

  def run(sock: String)(using ReactorBackend & UnixStreams): Unit = run(UnixAddress(sock))

  def run(address: UnixAddress)(using backend: ReactorBackend & UnixStreams): Unit =
    new Serving(backend) {
      def listen(into: Slot[b.Listener]): b.Op = b.ops.listen(address, into)
    }.run()

  def run(address: IpAddress)(using backend: ReactorBackend & IpStreams): Unit =
    new Serving(backend) {
      def listen(into: Slot[b.Listener]): b.Op = b.ops.listen(address, into)
    }.run()

  /** A server on a reactor of `b`'s, whose listener `listen` opens. */
  private abstract class Serving[B <: ReactorBackend & StreamSockets](val b: B) {
    def listen(into: Slot[b.Listener]): b.Op

    private val r: Reactor.Of[b.Op, b.Handle] = b.open()

    /** Listens and serves until the process ends, or until the listener cannot be made, which is thrown. */
    def run(): Unit =
      try {
        val server = new Server
        server.start()
        r.run()
        server.failure.foreach(throw _)
      } finally r.close()

    /** Listens, then accepts every connection pending each time the listener has some (`acceptAll`), starting each. An
      * accept that fails leaves the server running, accepting again after 10 ms, as `Sockets.serve` does.
      */
    private final class Server extends Completion[b.Op] {
      private val listening = Slot[b.Listener]()
      private val accepted = Slot[Array[b.Connection]]()
      private val open = listen(listening)
      private var accept: (b.Op & Repeatable) | Null = null
      private val pause = b.ops.timer(10)
      private var failing = false

      /** Why the server stopped, if it did: its listener could not be made. */
      var failure: Option[Throwable] = None

      def start(): Unit = r.submit(open, this)

      def onComplete(op: b.Op): Unit =
        if op == open then {
          accept = b.ops.acceptAll(listening.clear(), accepted, max = 64)
          println("Socket is now listening for connections.")
          r.submit(accept.nn, this)
        } else if op == pause then r.submit(accept.nn, this)
        else {
          failing = false
          for client <- accepted.clear() do {
            println(s"Accepted new client connection: $client")
            new Connection(client).start()
          }
          r.submit(accept.nn, this)
        }

      override def onFailure(op: b.Op, e: Throwable): Unit = e match {
        case e: IOException if op != open =>
          if !failing then println(s"Accepting failed, and is tried again: ${e.getMessage}") // once per run of them
          failing = true
          r.submit(pause, this)
        case _ =>
          failure = Some(e)
          r.stop()
      }
    }

    /** One accepted connection: reads a length-framed request exactly (`FrameReader`), replies with a framed response,
      * and closes gracefully, as `HigherLevel.closeGracefully` does: shuts its output down, so that the client reads
      * the whole reply, reads and discards until the client's end, for at most 2 s, and closes. Every step is the
      * completion of the one op before it, so at most one op of the connection is pending. Its 8 KiB buffer, through
      * which a body larger than that is read a buffer's worth at a time, is its arena's, which closes with the
      * connection.
      */
    private final class Connection(client: b.Connection) extends Completion[b.Op] {
      private var state = State.ReadRequest
      private val arena = BufferArena.open()
      private val buf = arena.allocate(8192)
      private val reader = new FrameReader(buf)
      // One repeatable op per direction for the whole connection; the buffer's limit says how much each read takes.
      private val read = b.ops.read(client, buf)
      private val write = b.ops.write(client, buf)
      private var before = 0 // the buffer's position as the read was submitted: a read that adds nothing is the end
      private var deadline = 0L // when draining gives up

      def start(): Unit = submitRead()

      private def submitRead(timeoutMillis: Int = -1): Unit = {
        before = buf.position()
        r.submit(read, this, timeoutMillis)
      }

      def onComplete(op: b.Op): Unit = state match {
        case State.ReadRequest =>
          if buf.position() == before then {
            println(s"Unexpected EOF from client socket $client.")
            shutDown()
          } else
            try {
              val request = reader.advance() // the buffer's limit stays at what is expected next
              if request == null then submitRead()
              else {
                println(s"message contents: `$request`")
                StreamProtocol.frame(Response, buf) // framed, so that the client knows when it has all of it
                state = State.SendResponse
                r.submit(write, this)
              }
            } catch {
              case invalid: InvalidFrame =>
                println(s"Invalid message size from client socket $client: ${invalid.length}")
                shutDown()
            }
        case State.SendResponse =>
          if buf.hasRemaining() then r.submit(write, this) else shutDown()
        case State.ShutDown =>
          state = State.Drain
          deadline = System.nanoTime() + 2_000_000_000L
          drain()
        case State.Drain =>
          if buf.position() > 0 then drain() else close() // a read that adds nothing is the client's end
        case State.Close =>
          arena.close()
          println(s"Closed client socket: $client")
      }

      override def onFailure(op: b.Op, e: Throwable): Unit = (state, e) match {
        // One misbehaving client must not take the server down.
        case (State.ReadRequest | State.SendResponse, e: IOException) =>
          println(s"Client socket $client failed: ${e.getMessage}")
          shutDown()
        // A client that resets, or never ends its side, is closed on all the same.
        case (State.ShutDown | State.Drain, _: IOException | _: TimeoutException) => close()
        case (State.Close, e: IOException)                                        =>
          arena.close()
          println(s"Closing client socket $client failed: ${e.getMessage}")
        case _ =>
          arena.close()
          throw e
      }

      /** The reactor closed with the connection's op pending: its buffer is the connection's again. */
      override def onCancel(op: b.Op): Unit = arena.close()

      private def shutDown(): Unit = {
        state = State.ShutDown
        r.submit(b.ops.shutdownOutput(client), this)
      }

      private def drain(): Unit = {
        val left = ((deadline - System.nanoTime()) / 1_000_000L).toInt
        if left <= 0 then close()
        else {
          buf.clear()
          submitRead(left)
        }
      }

      private def close(): Unit = {
        state = State.Close
        r.submit(b.ops.close(client), this)
      }
    }
  }

  private enum State {
    case ReadRequest, SendResponse, ShutDown, Drain, Close
  }
}
