package gears.async.io
package internal

import java.nio.ByteBuffer

/** The checks every backend makes as it builds ops, and the failures they report, the same on every backend. */
private[io] object Checks {

  /** Checks the options an opener of a socket of kind `kind` is given as it is built: each one the backend `supports`,
    * and that applies to such a socket; else an `IllegalArgumentException`.
    */
  def checkOptions(
      options: SocketOptions,
      supports: (SocketOption[?], SocketOption.Kind) => Boolean,
      kind: SocketOption.Kind
  ): Unit =
    for option <- options.options do {
      if !option.appliesTo(kind) then throw notFor(option, kind)
      if !supports(option, kind) then throw noOption(option, kind)
    }

  /** The failure of `option`, which applies to sockets of kind `kind`, on a backend that has it not for them here. */
  def noOption(option: SocketOption[?], kind: SocketOption.Kind): IllegalArgumentException =
    new IllegalArgumentException(s"This backend has no socket option $option for a ${kind.description} here")

  /** The failure of `option` on a socket of a kind it does not apply to. */
  def notFor(option: SocketOption[?], kind: SocketOption.Kind): IllegalArgumentException =
    new IllegalArgumentException(s"$option does not apply to a ${kind.description}")

  /** Whether a stream socket opening with `options` has `NoDelay` on: unless they set it off. */
  def noDelay(options: SocketOptions): Boolean = options.get(SocketOption.NoDelay) != false

  /** Checks a path an op names: a NUL would end it early as a C string, so the op would act on another path. */
  def checkPath(path: String): Unit =
    if path.indexOf('\u0000') >= 0 then
      throw new IllegalArgumentException(s"The path has a NUL in it: ${path.replace("\u0000", "\\0")}")

  /** Checks a host name to resolve: an empty one names nothing (the JDK would take it for the loopback address), and a
    * NUL would end it early.
    */
  def checkHost(host: String): Unit = {
    if host.isEmpty then throw new IllegalArgumentException("An empty host name names no host")
    if host.indexOf('\u0000') >= 0 then
      throw new IllegalArgumentException(s"The host name has a NUL in it: ${host.replace("\u0000", "\\0")}")
  }

  /** `address` checked and canonical (`IpText.canonical`), as every op taking one is built. */
  def ip(address: IpAddress): IpAddress = IpText.canonical(address)

  /** A datagram socket's two addresses, each checked as `ip` does, and of one family. */
  def ipPair(local: IpAddress | Null, remote: IpAddress | Null): (IpAddress | Null, IpAddress | Null) = {
    val l = if local == null then null else ip(local)
    val r = if remote == null then null else ip(remote)
    if l != null && r != null && l.ordinal != r.ordinal then
      throw new IllegalArgumentException(s"The local and remote addresses are of different families: $l, $r")
    (l, r)
  }

  /** A Unix socket path checked, as every op taking one is built: an empty path names no file (an unnamed socket is one
    * given no path), and a NUL would end it early.
    */
  def unix(address: UnixAddress): UnixAddress = {
    if address.path.isEmpty then throw new IllegalArgumentException("An empty Unix socket path names no file")
    checkPath(address.path)
    address
  }

  /** Checks a file offset: a negative one names no byte of the file. */
  def checkOffset(offset: Long): Unit =
    if offset < 0 then throw new IllegalArgumentException(s"A file offset must not be negative: $offset")

  /** `addresses` resolved for `host`, failing if there are none: a name with no IPv4 or IPv6 address cannot be
    * connected to, which the caller learns here rather than from an empty list.
    */
  def resolved(host: String, addresses: List[ResolvedAddress]): List[ResolvedAddress] =
    if addresses.isEmpty then throw new java.io.IOException(s"Failed to resolve $host: it has no IPv4 or IPv6 address")
    else addresses

  /** The failure of an op submitted with a slot that still holds a value (see `Slot`), before it does anything: what it
    * opened, accepted or received would have nowhere to go, an opened handle leaking; null if `slots` are all empty.
    */
  def notEmpty(slots: Slot[?]*): IllegalArgumentException | Null =
    if slots.forall(_.isEmpty) then null
    else
      new IllegalArgumentException("The op's slot still holds a value, not yet cleared, which the op would overwrite")

  /** Checks the buffers of a vectored op as it is built: at least one. */
  def checkBuffers(bufs: Array[ByteBuffer]): Unit =
    if bufs.isEmpty then throw new IllegalArgumentException("A vectored op takes at least one buffer")

  /** Whether any of `bufs` has room, or bytes remaining. */
  def anyRemaining(bufs: Array[ByteBuffer]): Boolean = bufs.exists(_.hasRemaining())

  /** The bytes remaining in `bufs`, all told. */
  def remaining(bufs: Array[ByteBuffer]): Long = bufs.foldLeft(0L)(_ + _.remaining())

  /** The failure of a read into `bufs` when none has room, as `bufferFull` is for one. */
  def buffersFull(bufs: Array[ByteBuffer]): IllegalArgumentException =
    new IllegalArgumentException(s"No buffer has room, so a read into them can read nothing: ${bufs.mkString(", ")}")

  /** The failure of a read into `buf` when it has no room: such a read could only read nothing, which says the stream
    * has ended, so it fails before anything is read or waited for.
    */
  def bufferFull(buf: ByteBuffer): IllegalArgumentException =
    new IllegalArgumentException(s"The buffer is full, so a read into it can read nothing: $buf")

  /** The failure of a receive into `buf` when it has no capacity, which would drop the whole packet. */
  def noCapacity(buf: ByteBuffer): IllegalArgumentException =
    new IllegalArgumentException(s"The buffer has no capacity, so a packet received into it would be dropped: $buf")

  /** The failure of a send of an empty packet on a connected socket, where not every backend can send one (see
    * `IpDatagramOps.send`).
    */
  def emptyPacket(buf: ByteBuffer): IllegalArgumentException =
    new IllegalArgumentException(s"The buffer is empty, and an empty packet is not sent on a connected socket: $buf")

  /** The failure of `op`, submitted while another op reading, or writing, is pending on its stream, on any submitter:
    * one of each at a time, since two reads would take the stream's bytes in no order, and two writes interleave
    * theirs. A datagram socket's or a listener's ops take turns instead (see `Ops`).
    */
  def alreadyPending(op: AnyRef): IllegalArgumentException =
    new IllegalArgumentException(s"An op reading, or writing, as $op does, is already pending on its handle")

  /** Checks, as an op that fills `buf` is built, that it can be filled: not a read-only buffer, which no backend reads
    * into; else an `IllegalArgumentException`. Any other buffer is taken (see `staged`).
    */
  def checkFillable(buf: ByteBuffer): Unit =
    if buf.isReadOnly() then throw new IllegalArgumentException(s"A read-only buffer cannot be read into: $buf")

  /** `checkFillable` on each of `bufs`. */
  def checkFillable(bufs: Array[ByteBuffer]): Unit = bufs.foreach(checkFillable)
}
