package gears.async.io
package nio

import gears.async.io.shareable.HandleOwner

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.NetworkChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Either kind of socket address, as the JDK's sockets take them. */
private[nio] type SocketAddress = IpSocketAddress | UnixAddress

/** The interface's addresses as the JDK's and back, without parsing text or resolving names. */
private[nio] object NioAddresses {

  def family(address: SocketAddress): StandardProtocolFamily = address match
    case ip: IpSocketAddress =>
      ip.address.protocol match
        case Protocol.IPv4 => StandardProtocolFamily.INET
        case Protocol.IPv6 => StandardProtocolFamily.INET6
    case _: UnixAddress => StandardProtocolFamily.UNIX

  def jdk(address: SocketAddress): java.net.SocketAddress = address match
    case address: IpSocketAddress =>
      val ip = address.address
      val inet = ip.protocol match
        case Protocol.IPv4 => InetAddress.getByAddress(ip.toBytes)
        case Protocol.IPv6 => Inet6Address.getByAddress(null, ip.toBytes, ip.scopeId)
      new InetSocketAddress(inet, address.port)
    case unix: UnixAddress => UnixDomainSocketAddress.of(unix.path)

  /** The interface's address for one the JDK reports: a socket with no address, as an unbound Unix socket is, has the
    * empty path.
    */
  def of(address: java.net.SocketAddress | Null): SocketAddress = address match
    case inet: InetSocketAddress       => IpSocketAddress(ip(inet.getAddress()), inet.getPort())
    case unix: UnixDomainSocketAddress => UnixAddress(unix.getPath().toString)
    case null                          => UnixAddress("")
    case other                         => throw new IOException(s"Unsupported JDK address: $other")

  /** An address of the JDK's, keeping its numeric scope and giving an unscoped IPv4-mapped address as IPv4. */
  def ip(address: InetAddress): IpAddress = address match
    case v4: Inet4Address => IpAddress.fromBytes(Protocol.IPv4, v4.getAddress())
    case v6: Inet6Address =>
      val bytes = v6.getAddress()
      val mapped = bytes.take(10).forall(_ == 0) && bytes(10) == -1 && bytes(11) == -1
      if mapped && v6.getScopeId() == 0 then IpAddress.fromBytes(Protocol.IPv4, bytes.drop(12))
      else IpAddress.fromBytes(Protocol.IPv6, bytes, v6.getScopeId())
    case other => throw new IOException(s"Unsupported JDK address: $other")
}

/** The interface's socket options as the JDK's: what each kind of socket has, probed once on a socket of that kind, and
  * reading and setting them.
  */
private[nio] object NioSocketOptions {
  import SocketOption.*
  import StandardSocketOptions.*
  import _root_.jdk.net.ExtendedSocketOptions.*

  private def jdk(option: SocketOption[?]): List[java.net.SocketOption[?]] = option match
    case NoDelay       => List(TCP_NODELAY)
    case KeepAlive     => List(SO_KEEPALIVE, TCP_KEEPIDLE, TCP_KEEPINTERVAL, TCP_KEEPCOUNT)
    case ReceiveBuffer => List(SO_RCVBUF)
    case SendBuffer    => List(SO_SNDBUF)
    case DontFragment  => List(IP_DONTFRAGMENT)
    case ReusePort     => List(SO_REUSEPORT)

  /** The options the JDK has for each kind of socket the backend opens. */
  private lazy val probed: Map[Kind, Set[java.net.SocketOption[?]]] = {
    def of(open: => NetworkChannel): Set[java.net.SocketOption[?]] = {
      val channel = open
      try scala.jdk.CollectionConverters.SetHasAsScala(channel.supportedOptions()).asScala.toSet
      finally channel.close()
    }
    val datagram = of(java.nio.channels.DatagramChannel.open())
    Map(
      Kind.Connection -> of(SocketChannel.open()),
      Kind.Listener -> of(ServerSocketChannel.open()),
      Kind.BoundDatagram -> datagram,
      Kind.ConnectedDatagram -> datagram,
      Kind.UnixConnection -> of(SocketChannel.open(StandardProtocolFamily.UNIX)),
      Kind.UnixListener -> of(ServerSocketChannel.open(StandardProtocolFamily.UNIX)),
      Kind.UnixBoundDatagram -> Set.empty,
      Kind.UnixConnectedDatagram -> Set.empty
    )
  }

  /** The kind of socket that carries `option` for one of kind `kind`: a listener's connections carry those it gives
    * them.
    */
  private def carrier(option: SocketOption[?], kind: Kind): Kind = (option, kind) match
    case (NoDelay | KeepAlive | SendBuffer, Kind.Listener) => Kind.Connection
    case (SendBuffer, Kind.UnixListener)                   => Kind.UnixConnection
    case _                                                 => kind

  def supports(option: SocketOption[?], kind: Kind): Boolean =
    option.appliesTo(kind) && jdk(option).forall(probed(carrier(option, kind)).contains)

  /** Whether `option` is read and changed on an open socket of kind `kind`. */
  def onOpen(option: SocketOption[?], kind: Kind): Boolean =
    SocketOption.onSocket(option, kind) && jdk(option).forall(probed(kind).contains)

  def canChange(option: SocketOption[?], kind: Kind): Boolean = option != ReusePort && onOpen(option, kind)

  /** Whether `channel` has `option` itself. */
  def on(channel: NetworkChannel, option: SocketOption[?]): Boolean =
    jdk(option).forall(channel.supportedOptions().contains)

  def set(channel: NetworkChannel, option: SocketOption[?], value: Any): Unit = option match
    case NoDelay   => channel.setOption(TCP_NODELAY, value.asInstanceOf[Boolean]): Unit
    case KeepAlive =>
      value match
        case KeepAlive.On(idle, interval, count) =>
          channel.setOption(SO_KEEPALIVE, true)
          channel.setOption(TCP_KEEPIDLE, idle)
          channel.setOption(TCP_KEEPINTERVAL, interval)
          channel.setOption(TCP_KEEPCOUNT, count): Unit
        case _ => channel.setOption(SO_KEEPALIVE, false): Unit
    case ReceiveBuffer => channel.setOption(SO_RCVBUF, value.asInstanceOf[Int]): Unit
    case SendBuffer    => channel.setOption(SO_SNDBUF, value.asInstanceOf[Int]): Unit
    case DontFragment  => channel.setOption(IP_DONTFRAGMENT, value.asInstanceOf[Boolean]): Unit
    case ReusePort     => channel.setOption(SO_REUSEPORT, value.asInstanceOf[Boolean]): Unit

  def get(channel: NetworkChannel, option: SocketOption[?]): Any = option match
    case NoDelay   => channel.getOption(TCP_NODELAY).booleanValue
    case KeepAlive =>
      if !channel.getOption(SO_KEEPALIVE).booleanValue then KeepAlive.Off
      else
        KeepAlive.On(
          channel.getOption(TCP_KEEPIDLE).intValue,
          channel.getOption(TCP_KEEPINTERVAL).intValue,
          channel.getOption(TCP_KEEPCOUNT).intValue
        )
    case ReceiveBuffer => channel.getOption(SO_RCVBUF).intValue
    case SendBuffer    => channel.getOption(SO_SNDBUF).intValue
    case DontFragment  => channel.getOption(IP_DONTFRAGMENT).booleanValue
    case ReusePort     => channel.getOption(SO_REUSEPORT).booleanValue
}

/** The socket file a Unix listener bound, to remove as the listener closes, if it is still that file: a listener that
  * took the path since replaced it with its own, which must stay.
  */
private[nio] final class UnixBinding(path: Path) {
  private val file: AnyRef | Null = UnixBinding.fileKey(path)

  def remove(): Unit =
    if file != null && UnixBinding.fileKey(path) == file then Files.deleteIfExists(path): Unit
}

private[nio] object UnixBinding {
  private def fileKey(path: Path): AnyRef | Null =
    try Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS).fileKey()
    catch case _: IOException => null

  /** Removes a socket file at `path`, as a restarted server's would be; fails, leaving it, on any other kind of file.
    */
  def replaceSocket(path: Path): Unit =
    if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
      val mode = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS).asInstanceOf[Int]
      if (mode & 0xf000) == 0xc000 then Files.deleteIfExists(path): Unit
      else throw new IOException(s"$path exists and is not a socket")
}

/** Opens this backend's stream sockets, non-blocking, over the JDK's channels. */
private[nio] object NioSockets {
  import SocketOption.Kind

  /** The most connections a listener keeps pending. */
  private inline val Backlog = 1024

  private def isIp(address: SocketAddress): Boolean = address.isInstanceOf[IpSocketAddress]

  /** A stream socket whose connect to `address` has begun, and may have gone through (`finishConnect`). */
  def startConnect(address: SocketAddress, options: SocketOptions, owner: HandleOwner): NioConnection = {
    val kind = if isIp(address) then Kind.Connection else Kind.UnixConnection
    val socket = SocketChannel.open(NioAddresses.family(address))
    try
      socket.configureBlocking(false)
      SocketOption.opening(options, kind).foreach((option, value) => NioSocketOptions.set(socket, option, value))
      socket.connect(NioAddresses.jdk(address)): Unit
      new NioConnection(socket, kind, owner)
    catch
      case failure: Throwable =>
        socket.close()
        throw failure
  }

  /** Whether `connection`'s connect has gone through; throws its failure if it has failed. */
  def finishConnect(connection: NioConnection): Boolean = connection.socket.finishConnect()

  /** A stream socket bound to `address` and listening. An IP one binds as `SO_REUSEADDR` does (see `IpStreams.listen`);
    * a Unix one replaces a socket file at its path.
    */
  def listen(address: SocketAddress, options: SocketOptions, owner: HandleOwner): NioListener = {
    val server = ServerSocketChannel.open(NioAddresses.family(address))
    var bound: UnixBinding | Null = null
    try
      val kind = address match
        case _: IpSocketAddress =>
          server.setOption(StandardSocketOptions.SO_REUSEADDR, true)
          Kind.Listener
        case unix: UnixAddress =>
          UnixBinding.replaceSocket(Path.of(unix.path))
          Kind.UnixListener
      // A listener sets those it has itself; the rest it gives the connections it accepts.
      for (option, value) <- SocketOption.opening(options, kind) do
        if NioSocketOptions.on(server, option) then NioSocketOptions.set(server, option, value)
      server.bind(NioAddresses.jdk(address), Backlog)
      address match
        case unix: UnixAddress => bound = new UnixBinding(Path.of(unix.path))
        case _                 => ()
      new NioListener(server, kind, options, bound, owner)
    catch
      case failure: Throwable =>
        try server.close()
        finally if bound != null then bound.nn.remove()
        throw failure
  }

  /** A connection pending on `listener`, accepted, with the options the listener gives its connections, and of its
    * listener's run; null if there is none.
    */
  def accept(listener: NioListener): NioConnection | Null = {
    val socket = listener.server.accept()
    if socket == null then null
    else
      try
        socket.configureBlocking(false)
        // A connection its client reset at once may refuse options: it is delivered all the same, and its first read
        // fails, as a reset connection's does, rather than failing the accept.
        try
          for (option, value) <- SocketOption.accepted(listener.accepting, listener.kind) do
            NioSocketOptions.set(socket, option, value)
        catch case _: IOException => ()
        val kind = if listener.kind.unix then Kind.UnixConnection else Kind.Connection
        new NioConnection(socket, kind, listener.owner)
      catch
        case failure: Throwable =>
          socket.close()
          throw failure
  }

  /** The local address of `socket`, or its peer's, with the socket's work claimed, so that a closed one refuses. */
  def addressOf(socket: NioSocket, peer: Boolean): SocketAddress = {
    socket.claimWork()
    try
      socket.network match
        case connection: SocketChannel if peer                    => NioAddresses.of(connection.getRemoteAddress())
        case datagrams: java.nio.channels.DatagramChannel if peer => NioAddresses.of(datagrams.getRemoteAddress())
        case network                                              => NioAddresses.of(network.getLocalAddress())
    finally socket.releaseWork()
  }
}

/** This backend's datagram sockets, non-blocking, over the JDK's datagram channels. */
private[nio] object NioDatagrams {

  /** A datagram socket bound to `local`, if given, and connected to `remote`, if given, as the handle `make` makes of
    * its channel.
    */
  def open[D](
      local: IpSocketAddress | Null,
      remote: IpSocketAddress | Null,
      options: SocketOptions,
      kind: SocketOption.Kind,
      owner: HandleOwner
  )(make: (DatagramChannel, HandleOwner) => D): D = {
    val either = if local != null then local else remote
    if either == null then throw new IllegalArgumentException("A datagram socket needs a local or a remote address")
    val datagrams = DatagramChannel.open(NioAddresses.family(either))
    try
      datagrams.configureBlocking(false)
      SocketOption.opening(options, kind).foreach((option, value) => NioSocketOptions.set(datagrams, option, value))
      if local != null then datagrams.bind(NioAddresses.jdk(local))
      if remote != null then datagrams.connect(NioAddresses.jdk(remote)) // assigns an ephemeral local port
      make(datagrams, owner)
    catch
      case failure: Throwable =>
        datagrams.close()
        throw failure
  }

  /** Takes one packet, if there is one, into `buf`, cleared first and left flipped, what does not fit dropped, and
    * writes its sender into `from` if that is given: whether there was one.
    */
  def receive[A <: AnyRef](datagrams: DatagramChannel, buf: ByteBuffer, from: Slot[A] | Null): Boolean = {
    buf.clear()
    val sender =
      try datagrams.receive(buf)
      catch
        // JDK 21 refuses a buffer of a shared arena here: through a buffer of its own, of the same capacity.
        case _: UnsupportedOperationException =>
          val packet = ByteBuffer.allocate(buf.capacity())
          val sender = datagrams.receive(packet)
          if sender != null then buf.put(packet.flip())
          sender
    if sender == null then false
    else
      buf.flip()
      if from != null then from.set(NioAddresses.of(sender).asInstanceOf[A])
      true
  }

  /** Sends what remains of `bufs` as one packet, to `to`, or to the socket's peer if it is null, consuming them:
    * whether it was sent, or the socket had no room. A channel reports an empty packet sent and a packet it had no room
    * for the same way, as nothing written: an empty one is taken as sent.
    */
  def send(datagrams: DatagramChannel, bufs: Array[ByteBuffer], to: IpSocketAddress | Null): Boolean = {
    val size = bufs.foldLeft(0L)(_ + _.remaining())
    val sent =
      try
        if to == null then datagrams.write(bufs)
        else if bufs.length == 1 then datagrams.send(bufs(0), NioAddresses.jdk(to)).toLong
        else sendCopied(datagrams, bufs, to)
      catch case _: UnsupportedOperationException => sendCopied(datagrams, bufs, to) // as `receive`, on JDK 21
    sent > 0 || size == 0
  }

  /** As one buffer of the backend's own: a datagram channel sends several buffers as one packet only to its peer. */
  private def sendCopied(datagrams: DatagramChannel, bufs: Array[ByteBuffer], to: IpSocketAddress | Null): Long = {
    val packet = ByteBuffer.allocate(bufs.foldLeft(0)(_ + _.remaining()))
    bufs.foreach(buf => packet.put(buf.duplicate()))
    packet.flip()
    val sent = if to == null then datagrams.write(packet) else datagrams.send(packet, NioAddresses.jdk(to))
    if sent > 0 then bufs.foreach(buf => buf.position(buf.limit()))
    sent.toLong
  }
}
