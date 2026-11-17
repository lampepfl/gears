package gears.async.io
package posix

import gears.async.io.shareable.IpAddresses

import java.nio.ByteBuffer
import scala.scalanative.posix.sys.socket
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** One packet received or sent on a non-blocking datagram socket, as the interface's ops have it. */
object PosixDatagrams {

  /** Takes one packet, if there is one, into `buf`, cleared first and left flipped for reading, what does not fit
    * dropped; its sender is written into `from`, if given. Whether there was one.
    */
  def receive[A <: AnyRef](fd: Int, buf: ByteBuffer, from: Slot[A] | Null): Boolean = {
    buf.clear()
    val sender = stackalloc[socket.sockaddr_storage]().asInstanceOf[Ptr[socket.sockaddr]]
    val senderLength = stackalloc[socket.socklen_t]()
    !senderLength = sizeOf[socket.sockaddr_storage].toUInt
    val received = NativeBuffer.staged(buf, copyIn = false, copyOut = true) { b =>
      val n = PosixSockets.receiveDatagram(fd, NativeBuffer.atPosition(b), b.remaining(), sender, senderLength)
      if n > 0 then NativeBuffer.advance(b, n)
      n
    }
    if received < 0 then false
    else {
      buf.flip()
      if from != null then from.set(PosixSockets.decode(sender, !senderLength).asInstanceOf[A])
      true
    }
  }

  /** Sends what remains of `bufs`, in order, as one packet, to `to`, or to the socket's peer if it is null, consuming
    * them: whether it was sent, or the socket had no room. An IPv6 socket, which is dual stack, reaches an IPv4 address
    * by its IPv4-mapped one.
    */
  def send(fd: Int, ipv6: Boolean, bufs: Array[ByteBuffer], to: SocketAddress | Null): Boolean = to match {
    case null                => sendTo(fd, bufs, null, 0.toUInt)
    case UnixAddress(path)   => PosixSockets.withUnixSocketAddr(path)(sendTo(fd, bufs, _, _))
    case ip: IpSocketAddress =>
      val target = if ipv6 then IpAddresses.mapped(ip) else ip
      PosixSockets.withIpSocketAddr(target)(sendTo(fd, bufs, _, _))
  }

  /** One buffer goes with `sendto`, and so does an empty packet, which `sendmsg` with nothing to gather refuses; more
    * are gathered by one `sendmsg`.
    */
  private def sendTo(
      fd: Int,
      bufs: Array[ByteBuffer],
      destination: Ptr[socket.sockaddr],
      destinationLength: socket.socklen_t
  ): Boolean =
    if bufs.length == 1 || !bufs.exists(_.hasRemaining()) then
      NativeBuffer.staged(bufs(0), copyIn = true, copyOut = false) { buf =>
        val sent =
          PosixSockets.sendDatagram(fd, NativeBuffer.atPosition(buf), buf.remaining(), destination, destinationLength)
        if sent then buf.position(buf.limit()) // a datagram goes whole or not at all
        sent
      }
    else PosixSockets.sendDatagram(fd, bufs, destination, destinationLength)
}
