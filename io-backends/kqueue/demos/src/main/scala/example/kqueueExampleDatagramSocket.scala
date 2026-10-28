package example

import gears.async.io.internal.Completion
import gears.async.io.internal.Interest
import gears.async.io.internal.NativeBuffer
import gears.async.io.internal.Sockets.Flavor
import gears.async.io.internal.Sockets.Transport
import gears.async.io.kqueue.Command
import gears.async.io.kqueue.KqueueOp
import gears.async.io.kqueue.KqueueReactor
import gears.async.io.kqueue.KqueueTimer

import java.io.IOException
import java.nio.charset.StandardCharsets
import scala.scalanative.unsafe.Zone

import KQueueExampleIO.*

object KQueueExampleDatagramSocket {

  /** Sends one packet to the connected peer and collects the echo. */
  private final class Ping(r: KqueueReactor, fd: Int, message: String)(using Zone) extends Completion[KqueueOp] {
    private val request = NativeBuffer.of(message.getBytes(StandardCharsets.UTF_8))
    private val response = NativeBuffer.allocate(65536)
    private val server = new PeerAddress

    /** Sends the request; done once it has gone. */
    private object Send extends Command {
      def fd: Int = Ping.this.fd
      def interest: Interest = Interest.Write
      def perform(): Boolean = {
        val sent = sendDatagram(fd, request)
        if sent then println(s"Sent datagram of ${request.limit()} bytes.")
        sent
      }
    }

    /** Receives the echo; done once it has arrived. */
    private object Receive extends Command {
      def fd: Int = Ping.this.fd
      def interest: Interest = Interest.Read
      def perform(): Boolean = {
        val read = server.receive(fd, response)
        if read >= 0 then println(s"Received datagram of $read bytes: `${text(response)}`")
        read >= 0
      }
    }

    /** UDP has no delivery guarantee; make a missing reply visible in the demo. */
    private val deadline = new KqueueTimer(milliseconds = 5000)

    def start(): Unit = {
      r.submit(deadline, this)
      r.submit(Send, this)
    }

    def onComplete(op: KqueueOp): Unit = op match {
      case Send    => r.submit(Receive, this)
      case Receive =>
        r.cancel(deadline)
        r.stop() // Stop once the reply has arrived
      case `deadline` => throw new IOException("Timed out waiting for datagram socket readiness or a reply")
      case _          => ()
    }
  }

  def run(sock: String, localSock: String, message: String): Unit = {
    require(sock != localSock, "The client and server socket paths must be different")
    // Unix datagram clients need their own bound address for the server's reply.
    KQueueExampleAddress.Unix(localSock).withBoundSocket(Transport.Datagram) { fd =>
      runConnected(fd, KQueueExampleAddress.Unix(sock), message)
    }
  }

  def run(address: KQueueExampleAddress, message: String): Unit = {
    require(address.flavor != Flavor.Unix, "Unix datagrams need a local socket path")
    address.withSocket(Transport.Datagram) { fd =>
      // Connecting an IP datagram socket also assigns it an ephemeral local port.
      runConnected(fd, address, message)
    }
  }

  private def runConnected(fd: Int, address: KQueueExampleAddress, message: String): Unit = {
    address.connect(fd)
    println(s"Connected datagram socket $fd to $address")
    KqueueReactor.scoped(maxEvents = 1) { r =>
      Zone.acquire { implicit z =>
        val ping = new Ping(r, fd, message)
        ping.start()
        KQueueExampleIO.runFailingFast(r)
      }
    }
  }
}
