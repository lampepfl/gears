package gears.async.io
package reference

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.UnixDomainSocketAddress

/** Addresses between the interface's and the JDK's: checked as an op is built, and canonical, IPv6 written as
  * `inet_ntop` writes it, so that one address has one text on every backend.
  */
private[reference] object Addresses {
  private val Dotted = """(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""".r

  private def invalid(address: IpAddress) =
    new IllegalArgumentException(s"Not a numeric address of its family, with a port from 0 to 65535: $address")

  /** The bytes of a numeric IPv4 address, or null. */
  private def ipv4(host: String): Array[Byte] | Null = host match {
    case Dotted(parts*) if parts.forall(_.toInt <= 255) => parts.map(_.toInt.toByte).toArray
    case _                                              => null
  }

  /** The 16 bytes of a numeric IPv6 address with no scope, or null. A host with a colon is never looked up. */
  private def ipv6(host: String): Array[Byte] | Null =
    if !host.contains(':') || host.exists(c => c == '%' || c == '[' || c == ']') then null
    else
      try
        InetAddress.getByName(host) match {
          case v4: Inet4Address => Array.fill[Byte](10)(0) ++ Array[Byte](-1, -1) ++ v4.getAddress() // ::ffff:a.b.c.d
          case v6               => v6.getAddress()
        }
      catch case _: java.net.UnknownHostException => null

  /** `address` checked and canonical: refused with an `IllegalArgumentException` if not numeric, not of its case's
    * family, scoped, or with a port out of range.
    */
  def checked(address: IpAddress): IpAddress = address match {
    case IpAddress.IPv4(host, port) =>
      val bytes = ipv4(host)
      if bytes == null || port < 0 || port > 65535 then throw invalid(address)
      IpAddress.IPv4(bytes.map(_ & 0xff).mkString("."), port)
    case IpAddress.IPv6(host, port) =>
      val bytes = ipv6(host)
      if bytes == null || port < 0 || port > 65535 then throw invalid(address)
      IpAddress.IPv6(ipv6Text(bytes), port)
  }

  /** A datagram socket's two addresses, checked, and of one family. */
  def pair(local: IpAddress | Null, remote: IpAddress): (IpAddress | Null, IpAddress) = {
    val l = if local == null then null else checked(local)
    val r = checked(remote)
    if l != null && l.ordinal != r.ordinal then
      throw new IllegalArgumentException(s"The local and remote addresses are of different families: $l, $r")
    (l, r)
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

  /** The JDK's address for a checked one. */
  def jdk(address: IpAddress): InetSocketAddress = address match {
    case IpAddress.IPv4(host, port) => new InetSocketAddress(InetAddress.getByAddress(ipv4(host)), port)
    case IpAddress.IPv6(host, port) => new InetSocketAddress(InetAddress.getByAddress(ipv6(host)), port)
  }

  def jdk(address: UnixAddress): UnixDomainSocketAddress = UnixDomainSocketAddress.of(address.path)

  /** The interface's address for one the JDK reports: an IPv4 peer of a dual-stack socket by its IPv4 address. */
  def ip(address: SocketAddress): IpAddress = address match {
    case inet: InetSocketAddress =>
      inet.getAddress() match {
        case v4: Inet4Address => IpAddress.IPv4(v4.getAddress().map(_ & 0xff).mkString("."), inet.getPort())
        case v6               =>
          val bytes = v6.getAddress()
          if isMapped(bytes) then IpAddress.IPv4(bytes.drop(12).map(_ & 0xff).mkString("."), inet.getPort())
          else IpAddress.IPv6(ipv6Text(bytes), inet.getPort())
      }
    case other => throw new IllegalArgumentException(s"Not an IP address: $other")
  }

  def unix(address: SocketAddress | Null): UnixAddress = address match {
    case unix: UnixDomainSocketAddress => UnixAddress(unix.getPath().toString)
    case _                             => UnixAddress("")
  }

  /** An address the JDK resolved, in the interface's form. */
  def resolved(address: InetAddress): ResolvedAddress = address match {
    case v4: Inet4Address => ResolvedAddress(AddressFamily.IPv4, v4.getAddress().map(_ & 0xff).mkString("."))
    case v6               => ResolvedAddress(AddressFamily.IPv6, ipv6Text(v6.getAddress()))
  }

  private def isMapped(bytes: Array[Byte]): Boolean =
    bytes.take(10).forall(_ == 0) && bytes(10) == -1 && bytes(11) == -1

  /** 16 bytes as `inet_ntop` writes them (RFC 5952): lowercase, no leading zeros, the longest run of two or more zero
    * groups as `::`, the first of equal runs, and an IPv4-mapped address with its IPv4 part dotted.
    */
  def ipv6Text(bytes: Array[Byte]): String = {
    if isMapped(bytes) then return "::ffff:" + bytes.drop(12).map(_ & 0xff).mkString(".")
    val groups = Array.tabulate(8)(i => ((bytes(2 * i) & 0xff) << 8) | (bytes(2 * i + 1) & 0xff))
    var bestStart = -1
    var bestLength = 1
    var i = 0
    while i < 8 do {
      if groups(i) == 0 then {
        var j = i
        while j < 8 && groups(j) == 0 do j += 1
        if j - i > bestLength then {
          bestStart = i
          bestLength = j - i
        }
        i = j
      } else i += 1
    }
    def hex(from: Int, until: Int) = (from until until).map(g => Integer.toHexString(groups(g))).mkString(":")
    if bestStart < 0 then hex(0, 8) else hex(0, bestStart) + "::" + hex(bestStart + bestLength, 8)
  }
}
