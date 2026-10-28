package gears.async.io
package internal

/** Addresses as text, the same on every backend. */
private[io] object IpText {

  /** `address` checked, and in canonical form: a numeric host of its case's family, IPv4 dotted with no leading zeros,
    * IPv6 as `ipv6` writes it, and a port from 0 to 65535; anything else is refused with an `IllegalArgumentException`,
    * as is an IPv6 scope (`fe80::1%en0`), which `IpAddress` cannot carry. Every op that takes an address calls this as
    * it is built, so that two spellings of one address are equal, and what a backend reports compares with it.
    */
  def canonical(address: IpAddress): IpAddress = address match {
    case IpAddress.IPv4(host, port) =>
      val bytes = parseIPv4(host)
      if bytes == null then throw invalid(host, "IPv4")
      IpAddress.IPv4(ipv4(bytes.nn), checkPort(port))
    case IpAddress.IPv6(host, port) =>
      if host.indexOf('%') >= 0 then
        throw new IllegalArgumentException(s"An IPv6 address with a scope is not supported: $host")
      val bytes = parseIPv6(host)
      if bytes == null then throw invalid(host, "IPv6")
      IpAddress.IPv6(ipv6(bytes.nn), checkPort(port))
  }

  /** `address` as the IPv4 address it maps, if it is an IPv4-mapped IPv6 address (`::ffff:a.b.c.d`), which is how a
    * dual-stack IPv6 socket sees an IPv4 peer; otherwise `address`.
    */
  def unmapped(address: IpAddress): IpAddress = address match {
    case IpAddress.IPv6(host, port) if host.startsWith("::ffff:") && host.indexOf('.') > 0 =>
      IpAddress.IPv4(host.substring("::ffff:".length), port)
    case other => other
  }

  /** Whether `address`, canonical, is a loopback address: IPv4's 127.0.0.0/8, IPv6's `::1`, or IPv4 loopback mapped
    * into IPv6.
    */
  def isLoopback(address: IpAddress): Boolean = unmapped(address) match {
    case IpAddress.IPv4(host, _) => host.startsWith("127.")
    case IpAddress.IPv6(host, _) => host == "::1"
  }

  private def invalid(host: String, family: String) =
    new IllegalArgumentException(s"Not a numeric $family address: $host")

  private def checkPort(port: Int): Int = {
    if port < 0 || port > 65535 then throw new IllegalArgumentException(s"A port is from 0 to 65535, not $port")
    port
  }

  def ipv4(bytes: Array[Byte]): String = bytes.map(_ & 0xff).mkString(".")

  /** The 4 bytes of a dotted IPv4 address, each part decimal from 0 to 255 without leading zeros; or null. */
  def parseIPv4(host: String): Array[Byte] | Null = {
    val parts = host.split("\\.", -1)
    if parts.length != 4 then null
    else if !parts.forall(p => p.nonEmpty && p.length <= 3 && p.forall(_.isDigit) && (p.length == 1 || p(0) != '0'))
    then null
    else {
      val values = parts.map(_.toInt)
      if values.exists(_ > 255) then null else values.map(_.toByte)
    }
  }

  /** The 16 bytes of an IPv6 address: groups of 1 to 4 hex digits, at most one `::`, the last 32 bits perhaps dotted
    * IPv4; or null.
    */
  def parseIPv6(host: String): Array[Byte] | Null = {
    def groups(text: String): Array[Int] | Null = // the groups of one side of `::`, a dotted tail as two
      if text.isEmpty then Array.empty[Int]
      else {
        val parts = text.split(":", -1)
        val last = parts.last
        val tail: Array[Int] | Null =
          if last.indexOf('.') < 0 then Array.empty[Int]
          else {
            val v4 = parseIPv4(last)
            if v4 == null then null
            else Array(((v4.nn(0) & 0xff) << 8) | (v4.nn(1) & 0xff), ((v4.nn(2) & 0xff) << 8) | (v4.nn(3) & 0xff))
          }
        val hex = if last.indexOf('.') < 0 then parts else parts.init
        val ok = hex.forall(g => g.nonEmpty && g.length <= 4 && g.forall(c => Character.digit(c, 16) >= 0))
        if tail == null || !ok then null else hex.map(Integer.parseInt(_, 16)) ++ tail.nn
      }
    val gap = host.indexOf("::")
    val all: Array[Int] | Null =
      if gap < 0 then {
        val g = groups(host)
        if g == null || g.nn.length != 8 then null else g
      } else if host.indexOf("::", gap + 1) >= 0 then null
      else {
        val before = groups(host.substring(0, gap))
        val after = groups(host.substring(gap + 2))
        if before == null || after == null then null
        else {
          // A dotted tail goes only at the very end, so not before the gap.
          val dottedBefore = host.substring(0, gap).indexOf('.') >= 0
          val fill = 8 - before.nn.length - after.nn.length
          if fill < 1 || dottedBefore then null else before.nn ++ Array.fill(fill)(0) ++ after.nn
        }
      }
    if all == null then null
    else {
      val bytes = new Array[Byte](16)
      for i <- 0 until 8 do {
        bytes(2 * i) = (all.nn(i) >> 8).toByte
        bytes(2 * i + 1) = all.nn(i).toByte
      }
      bytes
    }
  }

  /** The 16 bytes of an IPv6 address in RFC 5952's form, as `inet_ntop` writes it: groups in lower-case hex without
    * leading zeros, the first longest run of two or more zero groups written `::`, and an IPv4-mapped or
    * IPv4-compatible address with its IPv4 part dotted (`::ffff:192.0.2.1`, `::192.0.2.1`), as BIND's `inet_ntop`,
    * which macOS's and glibc's are, does.
    */
  def ipv6(bytes: Array[Byte]): String = {
    require(bytes.length == 16, s"An IPv6 address has 16 bytes, not ${bytes.length}")
    val groups = Array.tabulate(8)(i => ((bytes(2 * i) & 0xff) << 8) | (bytes(2 * i + 1) & 0xff))
    def dotted = s"${bytes(12) & 0xff}.${bytes(13) & 0xff}.${bytes(14) & 0xff}.${bytes(15) & 0xff}"
    if groups.take(5).forall(_ == 0) && groups(5) == 0xffff then s"::ffff:$dotted"
    else if groups.take(6).forall(_ == 0) && groups(6) != 0 then s"::$dotted"
    else {
      var bestStart = -1
      var bestLength = 1 // a run of one zero group is written as 0, not ::
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
      if bestStart < 0 then hex(0, 8)
      else s"${hex(0, bestStart)}::${hex(bestStart + bestLength, 8)}"
    }
  }
}
