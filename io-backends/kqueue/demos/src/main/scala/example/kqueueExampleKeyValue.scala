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
import gears.async.io.kqueue.ReadIntoBuffer
import gears.async.io.kqueue.WriteFromBuffer

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.collection.mutable
import scala.scalanative.unsafe.Zone

import KQueueExampleIO.*

/** A pipelined key-value service over the length-framed stream protocol, in the style of a Redis client and server. A
  * connection stays open for many commands, and replies go back in order while further commands are still arriving, so
  * each socket has a read and a write request pending at the same time.
  */
object KQueueExampleKeyValue {
  private inline val BufferSize = 8192

  /** Unsent replies (server) or unread replies (client) beyond this many bytes pause reading until they drain. */
  private inline val Backlog = 64 * 1024

  /** Executes `SET key value`, `GET key`, and `INCR key` against an in-memory map. */
  private final class Store {
    private val values = mutable.Map.empty[String, String]

    def execute(command: String): String = command.split(" ", 3) match {
      case Array("SET", key, value) =>
        values(key) = value
        "OK"
      case Array("GET", key)  => values.getOrElse(key, "(nil)")
      case Array("INCR", key) =>
        values.get(key).map(_.toIntOption) match {
          case Some(None) => "ERR value is not an integer"
          case current    =>
            val next = current.flatten.getOrElse(0) + 1
            values(key) = next.toString
            next.toString
        }
      case _ => s"ERR unknown command `$command`"
    }
  }

  /** One client connection: parses commands out of whatever arrives, queues the replies, and writes them back while
    * reading continues. Owns a zone holding its two buffers, closed with the connection.
    */
  private final class Session(r: KqueueReactor, val fd: KqueueKinds.Connection, store: Store, server: Server)
      extends AutoCloseable
      with Completion[Command] {
    private val zone: Zone = Zone.open()
    private val inbox: ByteBuffer = NativeBuffer.allocate(BufferSize)(using zone)
    private val outbox: ByteBuffer = NativeBuffer.allocate(BufferSize)(using zone)
    private val commands = new FrameParser
    private val replies = new ByteArrayOutputStream() // framed, waiting for the outbox
    private var writing = false
    private var reading = false
    private var clientClosed = false

    // One repeatable op per direction for the whole session.
    private val readCommands = KqueueOps.read(fd, inbox)
    private val writeReplies = KqueueOps.write(fd, outbox)

    def onComplete(command: Command): Unit =
      if command eq readCommands then {
        reading = false
        if inbox.position() == 0 then {
          clientClosed = true // Nothing added: end of stream. Answer what has been parsed, then close.
          if !writing then server.drop(this)
        } else {
          inbox.flip()
          commands.feed(inbox)
          var next = commands.next()
          while next != null do {
            StreamProtocol.appendTo(replies, store.execute(next))
            next = commands.next()
          }
          if !writing then sendReplies(r)
          // Back-pressure: stop reading while the client is not taking its replies.
          if replies.size <= Backlog then submitRead(r)
        }
      } else if outbox.hasRemaining() then r.submit(writeReplies, this)
      else {
        writing = false
        if replies.size > 0 then sendReplies(r)
        if !reading && !clientClosed && replies.size <= Backlog then submitRead(r)
        if clientClosed && !writing then server.drop(this)
      }

    /** A failed read or write ends the session; one misbehaving client must not take the server down. */
    override def onFailure(command: Command, failure: Throwable): Unit = {
      println(s"Client socket $fd failed: ${failure.getMessage}")
      server.drop(this)
    }

    def start(): Unit = submitRead(r)

    private def submitRead(r: KqueueReactor): Unit = {
      reading = true
      inbox.clear()
      r.submit(readCommands, this)
    }

    /** Moves up to one buffer of queued replies into the outbox and starts writing it. */
    private def sendReplies(r: KqueueReactor): Unit = {
      val queued = replies.toByteArray
      val chunk = math.min(queued.length, outbox.capacity())
      outbox.clear()
      outbox.put(queued, 0, chunk)
      outbox.flip()
      replies.reset()
      replies.write(queued, chunk, queued.length - chunk)
      writing = true
      r.submit(writeReplies, this)
    }

    def close(): Unit = {
      // The other direction may still be pending; cancel it so nothing is left on this descriptor's slot.
      r.cancel(readCommands)
      r.cancel(writeReplies)
      zone.close()
      KqueueHandles.close(fd)
      println(s"Closed client socket: $fd")
    }
  }

  private final class Server(r: KqueueReactor, serverFd: Int) extends Completion[Accept] {
    private val store = new Store
    private var sessions: Map[Handle, Session] = Map.empty

    private val accepted = gears.async.io.Slot[KqueueKinds.Connection]() // each accept writes its connection here

    def start(): Unit = r.submit(Accept(serverFd, accepted), this)

    def onComplete(command: Accept): Unit = {
      val clientFd = accepted.clear()
      val session = new Session(r, clientFd, store, this)
      sessions += clientFd -> session
      session.start()
      println(s"Accepted new client connection on serverFd: $clientFd")
      r.submit(command, this)
    }

    def drop(session: Session): Unit = {
      sessions -= session.fd
      session.close()
    }

    def close(): Unit = {
      val old = sessions
      sessions = Map.empty
      old.values.foreach(_.close())
    }
  }

  def serve(address: KQueueExampleAddress): Unit = {
    KqueueReactor.scoped() { r =>
      address.withBoundSocket(Transport.Stream) { serverFd =>
        PosixSockets.listen(serverFd, PosixSockets.maxConnections)
        println("Key-value server is now listening for connections.")
        val server = new Server(r, serverFd)
        server.start()
        try r.run()
        finally server.close()
      }
    }
  }

  /** Pipelines a batch of commands without waiting for replies, and reads the replies back as they arrive. */
  private final class Pipeline(r: KqueueReactor, sock: ClientSock, batch: IndexedSeq[String])(using Zone)
      extends Completion[Command] {
    private val outbox = NativeBuffer.allocate(BufferSize)
    private val inbox = NativeBuffer.allocate(BufferSize)
    private val parser = new FrameParser
    private var sent = 0
    private var received = 0
    private var lastReply = ""
    outbox.flip() // Start empty in draining mode, so the first write packs commands rather than sending unset bytes.

    /** Packs as many pending commands as fit into the empty outbox. */
    private def pack(): Unit = {
      outbox.clear()
      while sent < batch.length && StreamProtocol.append(batch(sent), outbox) do sent += 1
      outbox.flip()
    }

    /** Packs the next commands whenever the outbox has drained, then writes it. */
    private object SendBatch extends Command {
      def fd: Int = sock.fd
      def interest: Interest = Interest.Write
      // Until the connection is verified, wait for writability, which is when the connect has finished: tried at once,
      // the check passes while it is still in progress, and the write fails with ENOTCONN.
      override def tryFirst: Boolean = sock.isConnected
      def perform(): Boolean = {
        sock.ensureConnected()
        if !outbox.hasRemaining() then pack()
        nioWriteBytes(sock.fd, outbox) >= 0 || !outbox.hasRemaining()
      }
    }

    private var reading = false

    def onComplete(command: Command): Unit = command match {
      case SendBatch =>
        if !reading then { // connected now, so replies can be read
          reading = true
          inbox.clear()
          r.submit(ReadIntoBuffer(sock.fd, inbox), this)
        }
        if outbox.hasRemaining() || sent < batch.length then r.submit(SendBatch, this)
        else println(s"Pipelined ${batch.length} commands.")
      case read: ReadIntoBuffer =>
        if inbox.position() == 0 then throw new IOException(s"Server closed the connection after $received replies")
        else {
          inbox.flip()
          parser.feed(inbox)
          var reply = parser.next()
          while reply != null do {
            received += 1
            lastReply = reply
            reply = parser.next()
          }
          if received == batch.length then {
            println(s"Received $received replies; last reply: `$lastReply`")
            r.stop()
          } else {
            inbox.clear()
            r.submit(read, this)
          }
        }
      case _ => ()
    }

    /** Starts sending; reading starts once the first send has shown the connection made. */
    def start(): Unit = r.submit(SendBatch, this)
  }

  /** Sets a counter, increments it `count` times, then reads it back, all in one pipelined batch. */
  def run(address: KQueueExampleAddress, count: Int): Unit = {
    require(count > 0, "count must be positive")
    val batch = "SET counter 0" +: Vector.fill(count)("INCR counter") :+ "GET counter"
    KqueueReactor.scoped() { r =>
      address.withSocket(Transport.Stream) { clientFd =>
        println(s"opened file descriptor: $clientFd (socket-type)")
        address.connect(clientFd)
        println(s"connection in progress to: $address")
        Zone.acquire { implicit z =>
          new Pipeline(r, ClientSock(clientFd), batch).start()
          KQueueExampleIO.runFailingFast(r)
        }
      }
    }
  }
}
