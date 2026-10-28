package example

import gears.async.io.internal.Completion
import gears.async.io.internal.Interest
import gears.async.io.internal.NativeBuffer
import gears.async.io.internal.Sockets.Transport
import gears.async.io.kqueue.Command
import gears.async.io.kqueue.KqueueReactor

import scala.scalanative.unsafe.Zone

import KQueueExampleIO.*

object KQueueExampleDatagramServerSocket {

  /** Echoes each packet to its sender, keeping the packet and its sender until the whole echo can be sent. */
  private final class Echo(r: KqueueReactor, fd: Int)(using Zone) extends Completion[Command] {
    private val packet = NativeBuffer.allocate(65536)
    private val sender = new PeerAddress

    /** Receives one packet; done once one is held for reply. */
    private object Receive extends Command {
      def fd: Int = Echo.this.fd
      def interest: Interest = Interest.Read
      def perform(): Boolean = {
        val read = sender.receive(fd, packet)
        if read >= 0 then println(s"Received datagram of $read bytes: `${text(packet)}`")
        read >= 0
      }
    }

    /** Sends the held packet back; done once it has gone. */
    private object Reply extends Command {
      def fd: Int = Echo.this.fd
      def interest: Interest = Interest.Write
      def perform(): Boolean = {
        val sent = sender.reply(fd, packet)
        if sent then println(s"Replied with datagram of ${packet.limit()} bytes.")
        sent
      }
    }

    def start(): Unit = r.submit(Receive, this)

    def onComplete(command: Command): Unit = command match {
      case Receive => r.submit(Reply, this)
      case Reply   => r.submit(Receive, this)
      case _       => ()
    }
  }

  def run(address: KQueueExampleAddress): Unit = {
    KqueueReactor.scoped(maxEvents = 1) { r =>
      address.withBoundSocket(Transport.Datagram) { fd =>
        println("Datagram socket is now waiting for messages.")
        Zone.acquire { implicit z =>
          val echo = new Echo(r, fd)
          echo.start()
          r.run()
        }
      }
    }
  }
}
