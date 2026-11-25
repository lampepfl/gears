package gears.async.io
package node
package host

import gears.async.io.node.internal.Os

import java.io.IOException
import scala.scalajs.js

/** Converts address values to and from the text Node takes and reports, without resolving names. */
private[node] object Addresses {

  /** A datagram socket's two addresses, checked to be of one protocol. */
  def pair(local: IpSocketAddress | Null, remote: IpSocketAddress): (IpSocketAddress | Null, IpSocketAddress) = {
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

  /** The text Node takes for `address`: an IPv6 scope by its interface's name, which is how Node's addresses carry one,
    * if the host has an interface of that index, else by its number.
    */
  def host(address: IpAddress): String =
    if address.scopeId == 0 then address.toString
    else {
      val unscoped = IpAddress(Protocol.IPv6, address.bytes).toString
      s"$unscoped%${interfaceName(address.scopeId).getOrElse(address.scopeId.toString)}"
    }

  /** `address` as an IPv6 socket takes it: an IPv4 one as its IPv4-mapped IPv6 address. */
  def hostForIPv6(address: IpAddress): String = address.protocol match {
    case Protocol.IPv4 => s"::ffff:$address"
    case Protocol.IPv6 => host(address)
  }

  /** The address Node reports as `text` with `port`: an unscoped IPv4-mapped one, an IPv4 peer of a dual-stack socket,
    * by its IPv4 address.
    */
  def reported(text: Any, port: Any): IpSocketAddress = {
    val address = if js.typeOf(text) == "string" then parse(text.asInstanceOf[String]) else null
    if address == null || js.typeOf(port) != "number" then
      throw new IOException(s"Node reported no address for the socket: $text, port $port")
    IpSocketAddress(unmapped(address), port.asInstanceOf[Int])
  }

  /** An unscoped IPv4-mapped IPv6 address as the IPv4 address it maps; any other as it is. */
  def unmapped(address: IpAddress): IpAddress = {
    val bytes = address.bytes
    if address.protocol == Protocol.IPv6 && address.scopeId == 0 &&
      (0 until 10).forall(bytes(_) == 0) && bytes(10) == -1 && bytes(11) == -1
    then IpAddress.fromBytes(Protocol.IPv4, address.toBytes.drop(12))
    else address
  }

  private def interfaces: Iterator[(String, js.Dynamic)] =
    for
      (name, addresses) <- Os.networkInterfaces().iterator
      address <- addresses.iterator
    yield (name, address)

  /** The name of the interface whose IPv6 scope is `scopeId`, if the host has one. */
  def interfaceName(scopeId: Int): Option[String] =
    interfaces.collectFirst {
      case (name, address) if js.typeOf(address.scopeid) == "number" && address.scopeid.asInstanceOf[Int] == scopeId =>
        name
    }

  /** The IPv6 scope of the interface named `name`, if the host has one. */
  def interfaceScope(name: String): Option[Int] =
    interfaces.collectFirst {
      case (`name`, address) if js.typeOf(address.scopeid) == "number" && address.scopeid.asInstanceOf[Int] > 0 =>
        address.scopeid.asInstanceOf[Int]
    }

  /** The address `text` writes, if it is an IP address written out, an IPv4 one in dotted decimal or an IPv6 one in
    * hexadecimal groups, with a scope after `%` given by number or by an interface's name; null if it is not one, a
    * host's name say.
    */
  def parse(text: String): IpAddress | Null = {
    val percent = text.indexOf('%')
    if percent < 0 then {
      val v4 = parseIPv4(text)
      if v4 != null then IpAddress.fromBytes(Protocol.IPv4, v4)
      else {
        val v6 = parseIPv6(text)
        if v6 == null then null else IpAddress.fromBytes(Protocol.IPv6, v6)
      }
    } else {
      val v6 = parseIPv6(text.substring(0, percent))
      val zone = text.substring(percent + 1)
      val scope =
        if zone.nonEmpty && zone.length <= 9 && zone.forall(c => c >= '0' && c <= '9') then Some(zone.toInt)
        else interfaceScope(zone)
      if v6 == null || scope.isEmpty then null else IpAddress.fromBytes(Protocol.IPv6, v6, scope.get)
    }
  }

  private def parseIPv4(text: String): Array[Byte] | Null = {
    val parts = text.split("\\.", -1)
    if parts.length != 4 then return null
    val bytes = new Array[Byte](4)
    var i = 0
    while i < 4 do {
      val part = parts(i)
      if part.isEmpty || part.length > 3 || !part.forall(c => c >= '0' && c <= '9') then return null
      if part.length > 1 && part.charAt(0) == '0' then return null // read as octal by some, so left to the resolver
      val value = part.toInt
      if value > 255 then return null
      bytes(i) = value.toByte
      i += 1
    }
    bytes
  }

  private def parseIPv6(text: String): Array[Byte] | Null = {
    if !text.contains(':') then return null
    val gap = text.indexOf("::")
    if gap >= 0 && text.indexOf("::", gap + 1) >= 0 then return null

    /** The 16-bit groups `part` writes, colon-separated, its last perhaps an IPv4 address; null if it writes none. */
    def groups(part: String, last: Boolean): List[Int] | Null =
      if part.isEmpty then Nil
      else {
        val pieces = part.split(":", -1).toList
        val out = List.newBuilder[Int]
        var left = pieces
        while left.nonEmpty do {
          val piece = left.head
          left = left.tail
          if left.isEmpty && last && piece.contains('.') then {
            val v4 = parseIPv4(piece)
            if v4 == null then return null
            out += ((v4(0) & 0xff) << 8) | (v4(1) & 0xff)
            out += ((v4(2) & 0xff) << 8) | (v4(3) & 0xff)
          } else {
            val hex = piece.nonEmpty && piece.length <= 4 && piece.forall(c => Character.digit(c, 16) >= 0)
            if !hex then return null
            out += Integer.parseInt(piece, 16)
          }
        }
        out.result()
      }

    val all: List[Int] | Null =
      if gap < 0 then {
        val whole = groups(text, last = true)
        if whole == null || whole.length != 8 then null else whole
      } else {
        val head = groups(text.substring(0, gap), last = false)
        val tail = groups(text.substring(gap + 2), last = true)
        if head == null || tail == null || head.length + tail.length > 7 then null
        else head ++ List.fill(8 - head.length - tail.length)(0) ++ tail
      }
    if all == null then null
    else {
      val bytes = new Array[Byte](16)
      for (group, i) <- all.zipWithIndex do {
        bytes(2 * i) = (group >>> 8).toByte
        bytes(2 * i + 1) = group.toByte
      }
      bytes
    }
  }
}
