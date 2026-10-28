package gears.io.demos

import gears.async.Async
import gears.async.Future
import gears.async.io.Io
import gears.async.io.IpStreams
import gears.async.io.StreamSockets
import gears.async.io.UnixAddress
import gears.async.io.UnixStreams
import gears.async.io.testkit.BufferArena
import gears.io.higherlevel.HigherLevel

import java.io.EOFException
import java.io.IOException

import DemoIO.{FrameReader, InvalidFrame, StreamProtocol}

/** Mirror of `example.KQueueExampleServerSocket`. The acceptor is a loop, and each connection is its own future whose
  * state machine is now plain sequential code.
  */
object GearsServerSocket {
  private val Response = "Just pinging back!\n"

  /** Reads a length-framed request through one 8 KiB buffer, replies with a framed response, and closes the connection
    * gracefully, so that the client reads the whole reply however the request ended.
    */
  private def serve(io: Io[StreamSockets], client: io.Connection)(using Async): Unit = {
    val arena = BufferArena.open()
    try {
      val buf = arena.allocate(8192)
      // One repeatable op per direction for the whole connection; the buffer's limit says how much each read takes.
      val read = io.backend.ops.read(client, buf)
      val write = io.backend.ops.write(client, buf)

      // The request, read exactly to its frame's end: whatever the client sent after it stays unread.
      val reader = new FrameReader(buf)
      var request: String | Null = null
      while request == null do {
        val before = buf.position()
        io.perform(read)
        if buf.position() == before then throw new EOFException("Unexpected EOF") // a read that adds nothing
        request = reader.advance()
      }
      StreamProtocol.frame(Response, buf) // framed, so that the client knows when it has all of it
      while buf.hasRemaining() do io.perform(write)
    } catch {
      case invalid: InvalidFrame => println(s"Invalid message size from client socket $client: ${invalid.length}")
      case _: EOFException       => println(s"Unexpected EOF from client socket $client.")
      case e: IOException        =>
        // One misbehaving client must not take the server down.
        println(s"Client socket $client failed: ${e.getMessage}")
    } finally
      try HigherLevel.closeGracefully(using io)(client, arena.allocate(256))
      finally arena.close()
  }

  def run(sock: String)(using io: Io[UnixStreams & IpStreams], async: Async.Spawn): Unit = run(UnixAddress(sock))

  def run(address: StreamAddress)(using io: Io[UnixStreams & IpStreams], async: Async.Spawn): Unit = {
    val server = Sockets.listen(address)
    try {
      println("Socket is now listening for connections.")
      Sockets.serve(server)(client => serve(io, client))
    } finally io.perform(io.backend.ops.close(server))
  }
}
