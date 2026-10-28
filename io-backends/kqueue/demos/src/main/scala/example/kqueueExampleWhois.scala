package example

import gears.async.io.AddressFamily
import gears.async.io.ResolvedAddress
import gears.async.io.Slot
import gears.async.io.internal.Completion
import gears.async.io.internal.NativeBuffer
import gears.async.io.internal.PosixSockets
import gears.async.io.internal.Sockets.Transport
import gears.async.io.kqueue.Connect
import gears.async.io.kqueue.KqueueHandles
import gears.async.io.kqueue.KqueueOp
import gears.async.io.kqueue.KqueueReactor
import gears.async.io.kqueue.ReadIntoBuffer
import gears.async.io.kqueue.Resolve
import gears.async.io.kqueue.WriteFromBuffer

import java.nio.charset.StandardCharsets
import scala.scalanative.unsafe.Zone

import KQueueExampleIO.*

/** Pulls a real reply off the internet with the WHOIS protocol (RFC 3912): resolve the host, connect to port 43, send
  * one query line, and print whatever comes back until the server closes the connection. Nothing is parsed.
  */
object KQueueExampleWhois {

  /** Resolves, connects, sends the query, then prints each chunk of the reply as it arrives. Built from library
    * commands only; the socket is opened once the address family is known.
    */
  private final class Lookup(r: KqueueReactor, host: String, port: Int, query: String)(using Zone)
      extends Completion[KqueueOp] {
    private val buf = NativeBuffer.allocate(8192)
    private var fd = -1
    private var received = 0L

    private val addresses = Slot[List[ResolvedAddress]]()

    def start(): Unit = r.submit(new Resolve(host, addresses), this)

    def onComplete(op: KqueueOp): Unit = op match {
      case _: Resolve =>
        val resolvedAll = addresses.clear()
        val resolved = resolvedAll.head
        val address = resolved.family match {
          case AddressFamily.IPv6 => KQueueExampleAddress.IPv6(resolved.host, port)
          case _                  => KQueueExampleAddress.IPv4(resolved.host, port)
        }
        println(s"resolved $host to $address (${resolvedAll.length} addresses)")
        fd = PosixSockets.open(address.flavor, Transport.Stream)
        PosixSockets.setNonBlocking(fd)
        address.connect(fd)
        r.submit(Connect(fd), this)
      case Connect(_) =>
        println(s"connected; sending query `$query`")
        buf.clear()
        buf.put(s"$query\r\n".getBytes(StandardCharsets.UTF_8))
        buf.flip()
        r.submit(WriteFromBuffer(fd, buf), this)
      case write: WriteFromBuffer =>
        if buf.hasRemaining() then r.submit(write, this)
        else {
          buf.clear()
          r.submit(ReadIntoBuffer(fd, buf), this)
        }
      case read: ReadIntoBuffer =>
        if buf.position() == 0 then { // nothing added: the server closed the connection
          println(s"--- server closed the connection after $received bytes")
          KqueueHandles.close(KqueueHandles.handle(fd))
          r.stop()
        } else {
          received += buf.position()
          buf.flip()
          print(text(buf)) // Raw reply text, exactly as received.
          buf.clear()
          r.submit(read, this)
        }
      case _ => ()
    }
  }

  def run(host: String, port: Int, query: String): Unit = {
    KqueueReactor.scoped(maxEvents = 1) { r =>
      Zone.acquire { implicit z =>
        new Lookup(r, host, port, query).start()
        KQueueExampleIO.runFailingFast(r)
      }
    }
  }

  /** Resolves a host on the reactor and prints every address, as a check of the resolver itself. */
  def resolve(host: String): Unit = {
    KqueueReactor.scoped(maxEvents = 1) { r =>
      val addresses = Slot[List[ResolvedAddress]]() // written by the lookup, read in its completion
      r.submit(
        new Resolve(host, addresses),
        (_: Resolve) => {
          val resolvedAll = addresses.clear()
          resolvedAll.foreach { a =>
            val family = if a.family == AddressFamily.IPv4 then "IPv4" else "IPv6"
            println(s"$family ${a.host}")
          }
          println(s"resolved $host to ${resolvedAll.length} addresses")
          r.stop()
        }
      )
      KQueueExampleIO.runFailingFast(r)
    }
  }
}
