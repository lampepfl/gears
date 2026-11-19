package gears.async.io
package shareable

/** What the backends share about IP addresses beyond what `IpAddress` itself does. */
private[io] object IpAddresses {

  private def isMapped(bytes: IArray[Byte]): Boolean =
    bytes.length == 16 && (0 until 10).forall(bytes(_) == 0) && bytes(10) == -1 && bytes(11) == -1

  /** `address` as the IPv4 address it maps, if it is an IPv4-mapped IPv6 address with no scope (`::ffff:a.b.c.d`),
    * which is how a dual-stack IPv6 socket sees an IPv4 peer; otherwise `address`.
    */
  def unmapped(address: IpAddress): IpAddress =
    if address.protocol == Protocol.IPv6 && address.scopeId == 0 && isMapped(address.bytes) then
      IpAddress(Protocol.IPv4, address.bytes.drop(12))
    else address

  /** `address` as a dual-stack IPv6 socket reaches it: an IPv4 address as its IPv4-mapped one, since such a socket
    * cannot take an IPv4 socket address; otherwise `address`.
    */
  def mapped(address: IpSocketAddress): IpSocketAddress = address.address.protocol match {
    case Protocol.IPv4 =>
      val v4 = address.address.bytes
      val bytes = IArray.from(Array.fill[Byte](10)(0) ++ Array[Byte](-1, -1) ++ v4.toArray)
      IpSocketAddress(IpAddress(Protocol.IPv6, bytes), address.port)
    case Protocol.IPv6 => address
  }

  /** A datagram socket's two addresses, checked to be of one protocol, as the op is built. */
  def pair(local: IpSocketAddress | Null, remote: IpSocketAddress): (IpSocketAddress | Null, IpSocketAddress) = {
    if local != null && local.address.protocol != remote.address.protocol then
      throw new IllegalArgumentException(s"The local and remote addresses are of different families: $local, $remote")
    (local, remote)
  }
}
