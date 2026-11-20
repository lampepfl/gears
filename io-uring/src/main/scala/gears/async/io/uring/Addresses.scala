package gears.async.io
package uring

import gears.async.io.posix.PosixSockets
import gears.async.io.shareable.IpAddresses

import java.nio.charset.StandardCharsets
import scala.scalanative.posix.sys.socket
import scala.scalanative.runtime.ByteArray
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

// - a datagram's address lives in its command's own array: the kernel reads it after the submitting call returns
private[uring] object Addresses {
  inline val StorageSize = 128

  private inline val AF_UNIX = 1
  private inline val AF_INET = 2
  private inline val AF_INET6 = 10

  /** Writes `address` into `b`, at least `StorageSize` bytes, as a socket of the given kind sends to it: its length. */
  def write(address: IpSocketAddress | UnixAddress, ipv6Socket: Boolean, b: Array[Byte]): Int = {
    java.util.Arrays.fill(b, 0.toByte)
    address match {
      case asked: IpSocketAddress =>
        val target = if ipv6Socket then IpAddresses.mapped(asked) else asked
        val ip = target.address
        val port = target.port
        val octets = ip.bytes
        b(2) = (port >>> 8).toByte
        b(3) = port.toByte
        if ip.protocol == Protocol.IPv4 then {
          family(b, AF_INET)
          var i = 0
          while i < 4 do { b(4 + i) = octets(i); i += 1 }
          16
        } else {
          family(b, AF_INET6)
          var i = 0
          while i < 16 do { b(8 + i) = octets(i); i += 1 }
          val scope = ip.scopeId
          b(24) = scope.toByte
          b(25) = (scope >>> 8).toByte
          b(26) = (scope >>> 16).toByte
          b(27) = (scope >>> 24).toByte
          28
        }
      case UnixAddress(path) =>
        val bytes = path.getBytes(StandardCharsets.UTF_8)
        family(b, AF_UNIX)
        System.arraycopy(bytes, 0, b, 2, bytes.length)
        2 + bytes.length + 1
    }
  }

  private def family(b: Array[Byte], f: Int): Unit = {
    b(0) = f.toByte
    b(1) = (f >>> 8).toByte
  }

  /** The address the kernel wrote into `b`, `length` bytes of it. */
  def read(b: Array[Byte], length: Int): IpSocketAddress | UnixAddress =
    PosixSockets.decode(b.asInstanceOf[ByteArray].at(0).asInstanceOf[Ptr[socket.sockaddr]], length.toUInt)
}
