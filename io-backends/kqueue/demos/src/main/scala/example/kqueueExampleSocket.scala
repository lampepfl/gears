package example

import gears.async.io.internal.Completion
import gears.async.io.internal.NativeBuffer
import gears.async.io.internal.Sockets.Transport
import gears.async.io.kqueue.Command
import gears.async.io.kqueue.Connect
import gears.async.io.kqueue.KqueueReactor
import gears.async.io.kqueue.ReadIntoBuffer
import gears.async.io.kqueue.WriteFromBuffer

import scala.scalanative.unsafe.Zone

import KQueueExampleIO.*

object KQueueExampleSocket {

  /** Connects, sends one framed request, then reuses the same buffer to read the framed response exactly
    * (`FrameReader`); a stream that ends before the whole frame is a reply cut short. Built entirely from the library's
    * commands.
    */
  final class Exchange(r: KqueueReactor, fd: Int, message: String)(using Zone) extends Completion[Command] {
    private val buf = NativeBuffer.allocate(8192)
    private var reader: FrameReader | Null = null // once the request is out

    private var written = 0 // how much of the request the buffer's position had reached before the latest write
    private var before = 0 // the buffer's position before the latest read

    def start(): Unit = r.submit(Connect(fd), this)

    def onComplete(command: Command): Unit = command match {
      case Connect(_) =>
        StreamProtocol.frame(message, buf)
        r.submit(WriteFromBuffer(fd, buf), this)
      case write: WriteFromBuffer =>
        println(s"Wrote ${buf.position() - written} bytes to socket.")
        written = buf.position()
        if buf.hasRemaining() then r.submit(write, this)
        else {
          reader = new FrameReader(buf) // The request is out; the buffer now reads the response, exactly.
          r.submit(ReadIntoBuffer(fd, buf), this)
        }
      case read: ReadIntoBuffer =>
        if buf.position() == before then {
          // Nothing added: end of stream, before the whole frame.
          println("The server closed the connection before its whole reply")
          r.stop()
        } else {
          println(s"Read ${buf.position() - before} bytes from socket.")
          try {
            val reply = reader.nn.advance() // the buffer's limit stays at what is expected next
            before = buf.position()
            if reply != null then {
              println(s"Received message: `$reply`")
              r.stop()
            } else r.submit(read, this)
          } catch {
            case invalid: InvalidFrame =>
              println(s"Invalid message size from the server: ${invalid.length}")
              r.stop()
          }
        }
      case _ => ()
    }
  }

  def run(sock: String): Unit = run(KQueueExampleAddress.Unix(sock))

  def run(address: KQueueExampleAddress): Unit = {
    KqueueReactor.scoped() { r =>
      address.withSocket(Transport.Stream) { clientFd =>
        println(s"opened file descriptor: $clientFd (socket-type)")
        address.connect(clientFd)
        println(s"connection in progress to: $address")
        Zone.acquire { implicit z =>
          val exchange = Exchange(r, clientFd, "Hello from Scala Native KQueue Example!\n")
          exchange.start()
          KQueueExampleIO.runFailingFast(r)
        }
      }
    }
  }
}
