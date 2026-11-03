package gears.async.io
package reference

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress

/** Converts address values to and from JDK addresses without parsing text or resolving names. */
private[reference] object Addresses {

  private val supportedProtocols = Map(
    Protocol.IPv4 -> StandardProtocolFamily.INET,
    Protocol.IPv6 -> StandardProtocolFamily.INET6
  )

  /** Rejects protocols without a representation in the reference backend. */
  def protocol(protocol: Protocol): StandardProtocolFamily =
    supportedProtocols.getOrElse(
      protocol,
      throw new IllegalArgumentException(s"Unsupported address protocol: $protocol")
    )

  def checked(address: IpSocketAddress): IpSocketAddress = {
    protocol(address.address.protocol)
    address
  }

  /** A datagram socket's two addresses, checked to be of one protocol. */
  def pair(local: IpSocketAddress | Null, remote: IpSocketAddress): (IpSocketAddress | Null, IpSocketAddress) = {
    if local != null then checked(local)
    checked(remote)
    if local != null && local.address.protocol != remote.address.protocol then
      throw new IllegalArgumentException(s"The local and remote addresses are of different families: $local, $remote")
    (local, remote)
  }

  /** A Unix socket path checked: not empty, with no NUL. */
  def checked(address: UnixAddress): UnixAddress = {
    if address.path.isEmpty then throw new IllegalArgumentException("An empty Unix socket path names no file")
    checkPath(address.path)
    address
  }

  /** Checks a path an op names: a NUL would end it early as a C string. */
  def checkPath(path: String): Unit =
    if path.indexOf('\u0000') >= 0 then
      throw new IllegalArgumentException(s"The path has a NUL in it: ${path.replace("\u0000", "\\0")}")

  def jdk(address: IpSocketAddress): InetSocketAddress = {
    checked(address)
    val ip = address.address
    val inet = ip.protocol match {
      case Protocol.IPv4 => InetAddress.getByAddress(ip.toBytes)
      case Protocol.IPv6 => Inet6Address.getByAddress(null, ip.toBytes, ip.scopeId)
    }
    new InetSocketAddress(inet, address.port)
  }

  def jdk(address: UnixAddress): UnixDomainSocketAddress = UnixDomainSocketAddress.of(address.path)

  /** The interface's address for one the JDK reports: an IPv4 peer of a dual-stack socket by its IPv4 address. */
  def ip(address: SocketAddress): IpSocketAddress = address match {
    case inet: InetSocketAddress => IpSocketAddress(resolved(inet.getAddress()), inet.getPort())
    case other                   => throw new IllegalArgumentException(s"Not an IP address: $other")
  }

  def unix(address: SocketAddress | Null): UnixAddress = address match {
    case unix: UnixDomainSocketAddress => UnixAddress(unix.getPath().toString)
    case _                             => UnixAddress("")
  }

  /** An address the JDK resolved, preserving its numeric scope ID and reporting unscoped mapped IPv4 addresses as IPv4.
    */
  def resolved(address: InetAddress): IpAddress = {
    address match {
      case v4: Inet4Address => IpAddress.fromBytes(Protocol.IPv4, v4.getAddress())
      case v6: Inet6Address =>
        val bytes = v6.getAddress()
        val mapped = bytes.take(10).forall(_ == 0) && bytes(10) == -1 && bytes(11) == -1
        if mapped && v6.getScopeId() == 0 then IpAddress.fromBytes(Protocol.IPv4, bytes.drop(12))
        else IpAddress.fromBytes(Protocol.IPv6, bytes, v6.getScopeId())
      case other => throw new IOException(s"Unsupported JDK address: $other")
    }
  }
}
