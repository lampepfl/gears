package gears.io.demos

import gears.async.Async
import gears.async.io.Io
import gears.async.io.IpStreams
import gears.async.io.StreamSockets
import gears.async.io.UnixAddress
import gears.async.io.UnixStreams
import gears.async.io.testkit.BufferArena
import gears.io.higherlevel.HigherLevel

import java.io.IOException

import DemoIO.{FrameReader, StreamProtocol}

/** Mirror of `example.KQueueExampleSocket`: connect, send one framed request, and read the framed response exactly,
  * through the same buffer (`FrameReader`); a stream that ends before the whole frame is a reply cut short, and an
  * error. The client shuts its side down once the request is out, so that the server sees where it ends.
  */
object GearsSocket {
  def run(sock: String)(using io: Io[UnixStreams & IpStreams], async: Async.Spawn): Unit = run(UnixAddress(sock))

  def run(address: StreamAddress)(using io: Io[UnixStreams & IpStreams], async: Async.Spawn): Unit = {
    BufferArena.scoped { arena =>
      val buf = arena.allocate(8192)
      println(s"connection in progress to: $address")
      val socket = Sockets.connect(address)
      try {
        // One repeatable op, resubmitted until the request is out.
        val write = io.backend.ops.write(socket, buf)
        StreamProtocol.frame("Hello from Scala Native KQueue Example!\n", buf)
        while buf.hasRemaining() do {
          val before = buf.position()
          io.perform(write)
          println(s"Wrote ${buf.position() - before} bytes to socket.")
        }
        HigherLevel.shutdownOutput(socket) // the request ends here, and the server's reply follows to its end
        // The response, read exactly: its header, then its body, through the same buffer.
        val read = io.backend.ops.read(socket, buf)
        val reader = new FrameReader(buf)
        var reply: String | Null = null
        while reply == null do {
          val before = buf.position()
          io.perform(read)
          if buf.position() == before then
            throw new IOException("The server closed the connection before its whole reply")
          println(s"Read ${buf.position() - before} bytes from socket.")
          reply = reader.advance()
        }
        println(s"Received message: `$reply`")
      } finally io.perform(io.backend.ops.close(socket))
    }
  }
}
