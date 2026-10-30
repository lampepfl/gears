package gears.io.demos

import gears.async.Async
import gears.async.BufferedChannel
import gears.async.Future
import gears.async.Future.awaitAllOrCancel
import gears.async.io.Io
import gears.async.io.IpStreams
import gears.async.io.StreamSockets
import gears.async.io.UnixAddress
import gears.async.io.UnixStreams
import gears.async.io.testkit.BufferArena
import gears.io.higherlevel.HigherLevel

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import scala.collection.mutable

import DemoIO.{FrameParser, StreamProtocol}

/** The pipelined key-value service. Each session is a reader future and a writer future joined by a bounded channel of
  * reply batches, which replaces the shared flags and the byte backlog: when the channel is full the reader stops
  * reading, which is the back-pressure.
  */
object GearsKeyValue {
  private inline val BufferSize = 8192

  /** Reply batches held for the writer before the reader pauses; about 64 KiB of replies. */
  private inline val Backlog = 8

  /** Executes `SET key value`, `GET key`, and `INCR key` against an in-memory map shared by every session. Sessions run
    * on different threads, so access is synchronised.
    */
  private final class Store {
    private val values = mutable.Map.empty[String, String]

    def execute(command: String): String = synchronized {
      command.split(" ", 3) match {
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
  }

  /** Reads commands until EOF, executes them, and hands each batch of framed replies to the writer. An empty batch
    * marks the end.
    */
  private def readCommands(
      io: Io[StreamSockets],
      fd: io.backend.Connection,
      inbox: ByteBuffer,
      store: Store,
      replies: BufferedChannel[Array[Byte]]
  )(using
      Async
  ): Unit = {
    val commands = new FrameParser
    val readCommands = io.backend.ops.read(fd, inbox) // one repeatable op for the whole session
    var eof = false
    while !eof do {
      inbox.clear()
      io.perform(readCommands)
      if inbox.position() == 0 then eof = true // Nothing added: end of stream. Answer what has been parsed, then close.
      else {
        inbox.flip()
        commands.feed(inbox)
        val batch = new ByteArrayOutputStream()
        var next = commands.next()
        while next != null do {
          StreamProtocol.appendTo(batch, store.execute(next))
          next = commands.next()
        }
        if batch.size > 0 then replies.send(batch.toByteArray)
      }
    }
    replies.send(Array.emptyByteArray)
  }

  /** Writes reply batches in order, one buffer at a time, until the end marker. */
  private def writeReplies(
      io: Io[StreamSockets],
      fd: io.backend.Connection,
      outbox: ByteBuffer,
      replies: BufferedChannel[Array[Byte]]
  )(using
      Async
  ): Unit = {
    val writeReplies = io.backend.ops.write(fd, outbox) // one repeatable op for the whole session
    var done = false
    while !done do {
      val batch = replies.read().right.get
      if batch.isEmpty then done = true
      else {
        var offset = 0
        while offset < batch.length do {
          val chunk = math.min(batch.length - offset, outbox.capacity())
          outbox.clear()
          outbox.put(batch, offset, chunk)
          outbox.flip()
          offset += chunk
          while outbox.hasRemaining() do io.perform(writeReplies)
        }
      }
    }
  }

  /** One client connection: reading and writing run at the same time on the same socket. */
  private def session(io: Io[StreamSockets], fd: io.backend.Connection, store: Store)(using Async): Unit = {
    val arena = BufferArena.open()
    try {
      val inbox = arena.allocate(BufferSize)
      val outbox = arena.allocate(BufferSize)
      val replies = BufferedChannel[Array[Byte]](Backlog)
      Async.group {
        Seq(
          Future(readCommands(io, fd, inbox, store, replies)),
          Future(writeReplies(io, fd, outbox, replies))
        ).awaitAllOrCancel
      }
    } catch {
      case e: IOException => println(s"Client socket $fd failed: ${e.getMessage}")
    } finally {
      arena.close()
      io.perform(io.backend.ops.close(fd))
      println(s"Closed client socket: $fd")
    }
  }

  def serve(address: StreamAddress)(using io: Io[UnixStreams & IpStreams], async: Async.Spawn): Unit = {
    val store = new Store
    val server = Sockets.listen(address)
    try {
      println("Key-value server is now listening for connections.")
      Sockets.serve(server)(client => session(io, client, store))
    } finally io.perform(io.backend.ops.close(server))
  }

  /** Sets a counter, increments it `count` times, then reads it back, all in one pipelined batch: a sender future
    * writes commands while a receiver future reads replies.
    */
  def run(address: StreamAddress, count: Int)(using io: Io[UnixStreams & IpStreams], async: Async.Spawn): Unit = {
    require(count > 0, "count must be positive")
    val batch = "SET counter 0" +: Vector.fill(count)("INCR counter") :+ "GET counter"
    BufferArena.scoped { arena =>
      val outbox = arena.allocate(BufferSize)
      val inbox = arena.allocate(BufferSize)
      println(s"connection in progress to: $address")
      val socket = Sockets.connect(address)
      try {
        val sender = Future {
          val write = io.backend.ops.write(socket, outbox)
          var sent = 0
          while sent < batch.length do {
            outbox.clear()
            while sent < batch.length && StreamProtocol.append(batch(sent), outbox) do sent += 1
            outbox.flip()
            while outbox.hasRemaining() do io.perform(write)
          }
          println(s"Pipelined ${batch.length} commands.")
        }
        val receiver = Future {
          val parser = new FrameParser
          val read = io.backend.ops.read(socket, inbox)
          var received = 0
          var lastReply = ""
          while received < batch.length do {
            inbox.clear()
            io.perform(read)
            if inbox.position() == 0 then throw new IOException(s"Server closed the connection after $received replies")
            inbox.flip()
            parser.feed(inbox)
            var reply = parser.next()
            while reply != null do {
              received += 1
              lastReply = reply
              reply = parser.next()
            }
          }
          println(s"Received $received replies; last reply: `$lastReply`")
        }
        Seq(sender, receiver).awaitAllOrCancel
      } finally io.perform(io.backend.ops.close(socket))
    }
  }
}
