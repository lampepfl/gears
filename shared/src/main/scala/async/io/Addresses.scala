package gears.async.io

/** An immutable address with an explicit protocol, stored as bytes in network byte order. Construction validates the
  * representation for that protocol. Equality and hashing use the protocol, bytes and IPv6 scope ID, independently of
  * any textual form. The nonnegative scope ID identifies a zone on the local system; zero means unspecified and is
  * required for IPv4. IPv4-mapped IPv6 addresses remain IPv6 values. Construction never resolves a name. A backend may
  * support only some families and rejects the others with an `IllegalArgumentException` when an op is built.
  */
final class IpAddress private (val protocol: Protocol, private val octets: IArray[Byte], val scopeId: Int) {
  require(scopeId >= 0, s"A scope ID is nonnegative: $scopeId")

  /** The address in network byte order, as immutable bytes. */
  def bytes: IArray[Byte] = octets

  /** A mutable copy, for platform APIs that take an array. */
  def toBytes: Array[Byte] = octets.toArray

  override def equals(other: Any): Boolean = other match {
    case that: IpAddress => protocol == that.protocol && scopeId == that.scopeId && octets.sameElements(that.octets)
    case _               => false
  }

  override def hashCode: Int = {
    var hash = 31 * protocol.hashCode + scopeId
    var i = 0
    while i < octets.length do {
      hash = 31 * hash + octets(i)
      i += 1
    }
    hash
  }

  override def toString: String = protocol match {
    case Protocol.IPv4 => octets.iterator.map(_ & 0xff).mkString(".")
    case Protocol.IPv6 =>
      val base = IpAddress.formatIPv6(octets)
      if scopeId == 0 then base else s"$base%$scopeId"
  }
}

object IpAddress {

  /** Constructs an IPv4 address from four octets, each between 0 and 255. */
  def ipv4(a: Int, b: Int, c: Int, d: Int): IpAddress =
    new IpAddress(Protocol.IPv4, IArray(octet(a), octet(b), octet(c), octet(d)), 0)

  /** Constructs an IPv6 address from eight groups in network order, each between 0 and 65535, with an optional
    * nonnegative numeric scope ID.
    */
  def ipv6(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int, scopeId: Int = 0): IpAddress = {
    checkGroup(a)
    checkGroup(b)
    checkGroup(c)
    checkGroup(d)
    checkGroup(e)
    checkGroup(f)
    checkGroup(g)
    checkGroup(h)
    new IpAddress(
      Protocol.IPv6,
      IArray[Byte](
        (a >>> 8).toByte,
        a.toByte,
        (b >>> 8).toByte,
        b.toByte,
        (c >>> 8).toByte,
        c.toByte,
        (d >>> 8).toByte,
        d.toByte,
        (e >>> 8).toByte,
        e.toByte,
        (f >>> 8).toByte,
        f.toByte,
        (g >>> 8).toByte,
        g.toByte,
        (h >>> 8).toByte,
        h.toByte
      ),
      scopeId
    )
  }

  private def octet(value: Int): Byte = {
    require(value >= 0 && value <= 255, s"An IPv4 octet is between 0 and 255: $value")
    value.toByte
  }

  private def checkGroup(value: Int): Unit =
    require(value >= 0 && value <= 65535, s"An IPv6 group is between 0 and 65535: $value")

  /** Copies the bytes and validates them for the given protocol. Copying also protects the value if the caller created
    * the IArray from an aliased mutable array.
    */
  def apply(protocol: Protocol, bytes: IArray[Byte]): IpAddress =
    checked(protocol, IArray.from(bytes))

  /** Copies IPv6 bytes with a nonnegative numeric scope ID. */
  def apply(protocol: Protocol.IPv6.type, bytes: IArray[Byte], scopeId: Int): IpAddress =
    checked(protocol, IArray.from(bytes), scopeId)

  /** Copies a mutable array, for platform APIs that return one. */
  def fromBytes(protocol: Protocol, bytes: Array[Byte]): IpAddress =
    checked(protocol, IArray.from(bytes))

  /** Copies mutable IPv6 bytes with a nonnegative numeric scope ID. */
  def fromBytes(protocol: Protocol.IPv6.type, bytes: Array[Byte], scopeId: Int): IpAddress =
    checked(protocol, IArray.from(bytes), scopeId)

  private def checked(protocol: Protocol, bytes: IArray[Byte], scopeId: Int = 0): IpAddress = {
    protocol match {
      case Protocol.IPv4 => require(bytes.length == 4, "An IPv4 address has exactly 4 bytes")
      case Protocol.IPv6 => require(bytes.length == 16, "An IPv6 address has exactly 16 bytes")
    }
    new IpAddress(protocol, bytes, scopeId)
  }

  /** Compressed hexadecimal, with a dotted IPv4 suffix for IPv4-mapped addresses. */
  private def formatIPv6(octets: IArray[Byte]): String = {
    if (0 until 10).forall(octets(_) == 0) && octets(10) == -1 && octets(11) == -1 then
      return "::ffff:" + (12 until 16).map(i => octets(i) & 0xff).mkString(".")
    val groups = Array.tabulate(8)(i => ((octets(2 * i) & 0xff) << 8) | (octets(2 * i + 1) & 0xff))
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

  val IPv4Any: IpAddress = ipv4(0, 0, 0, 0)
  val IPv4Loopback: IpAddress = ipv4(127, 0, 0, 1)
  val IPv6Any: IpAddress = ipv6(0, 0, 0, 0, 0, 0, 0, 0)
  val IPv6Loopback: IpAddress = ipv6(0, 0, 0, 0, 0, 0, 0, 1)
}

/** An IP address and a port, checked at construction. Port zero requests an ephemeral port when binding. An IPv6 socket
  * on `IpAddress.IPv6Any` is dual stack on every backend, taking IPv4 peers too, which it reports, and sends to, by
  * their IPv4 addresses.
  */
final case class IpSocketAddress(address: IpAddress, port: Int) {
  require(port >= 0 && port <= 65535, s"A port is between 0 and 65535: $port")

  override def toString: String = address.protocol match {
    case Protocol.IPv4 => s"$address:$port"
    case Protocol.IPv6 => s"[$address]:$port"
  }
}

/** Where a Unix domain socket lives: a path, or the empty path for an unnamed socket, as a sender is reported; an op
  * given the empty path, or one with a NUL, refuses it with an `IllegalArgumentException` as it is built.
  */
final case class UnixAddress(path: String)

/** The IP protocols represented by an address. New protocols are added here with their validation and formatting. */
enum Protocol {
  case IPv4, IPv6
}
