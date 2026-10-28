package gears.async.io
package nio

import gears.async.io.IpAddress
import gears.async.io.UnixAddress

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel
import java.nio.channels.FileChannel
import java.nio.channels.Pipe
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SelectableChannel
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

import NioCapabilities.Handle

/** What a handle names: an I/O object, and how to read and write it without waiting. `read` returns the bytes read, 0
  * if none are available, or -1 at end of stream; `write` returns the bytes written, or -1 if nothing fits.
  */
private[nio] trait Entry {
  def read(buf: ByteBuffer): Int
  def write(buf: ByteBuffer): Int

  /** Fills `bufs` in order, as `read` fills one: the bytes read, 0 if none are there now, or -1 at end of stream. */
  def read(bufs: Array[ByteBuffer]): Long

  /** Drains `bufs` in order, as `write` drains one: the bytes written, or -1 if none fit now. */
  def write(bufs: Array[ByteBuffer]): Long
  def close(): Unit

  /** What it is, for a handle's `toString`. */
  def description: String
}

/** A channel a `Selector` watches: a socket, a listener, a datagram socket, or an end of a pipe, in non-blocking mode.
  * A Unix path it bound is removed when it is closed.
  */
private[nio] final class Selectable(val channel: SelectableChannel, bound: BoundFile | Null) extends Entry {

  /** An IP listener's options, for each connection it accepts (see `NioHandles.accepted`). */
  @volatile var accepting: SocketOptions | Null = null

  def description: String = channel.toString

  def read(buf: ByteBuffer): Int = channel match {
    case readable: ReadableByteChannel => readable.read(buf)
    case _                             => throw new IOException(s"$channel cannot be read")
  }

  def write(buf: ByteBuffer): Int = channel match {
    case writable: WritableByteChannel =>
      val written = writable.write(buf)
      if written == 0 && buf.hasRemaining then -1 else written
    case _ => throw new IOException(s"$channel cannot be written")
  }

  def read(bufs: Array[ByteBuffer]): Long = channel match {
    case scattering: java.nio.channels.ScatteringByteChannel => scattering.read(bufs)
    case _                                                   => throw new IOException(s"$channel cannot be read")
  }

  def write(bufs: Array[ByteBuffer]): Long = channel match {
    case gathering: java.nio.channels.GatheringByteChannel =>
      val written = gathering.write(bufs)
      if written == 0 && gears.async.io.internal.Checks.anyRemaining(bufs) then -1 else written
    case _ => throw new IOException(s"$channel cannot be written")
  }

  // The reactors whose selectors this channel is registered with, which `close` wakes.
  private val registrations = new java.util.concurrent.ConcurrentLinkedQueue[NioReactor]()

  /** Records that `reactor` registered the channel with its selector. */
  def registered(reactor: NioReactor): Unit = registrations.add(reactor): Unit

  // The channel's own selectors, one for each direction, on which callers off the pool wait (`SelectorWait`),
  // taken from the pool on first use; the ops waiting on them; and whether the channel has been closed, after which
  // none is taken, and the selectors go back to the pool once no op waits on them. Guarded by `this`.
  private var reading: Selector | Null = null
  private var writing: Selector | Null = null
  private var waiters = 0
  private var closed = false

  /** The channel's own selector for writing (connecting, too) or for reading (accepting, too), with the channel
    * registered on it, for an op to wait on until it calls `exitWait`. Throws once the handle is closed.
    */
  def enterWait(write: Boolean): Selector = synchronized {
    if closed then throw new ClosedChannelException()
    val existing = if write then writing else reading
    val selector =
      if existing != null then existing
      else {
        val taken = SelectorPool.take()
        try channel.register(taken, 0)
        catch {
          case t: Throwable =>
            SelectorPool.give(taken)
            throw t
        }
        if write then writing = taken else reading = taken
        taken
      }
    waiters += 1
    selector
  }

  /** An op has stopped waiting; the last one to leave a closed handle gives its selectors back to the pool. */
  def exitWait(): Unit = {
    val released = synchronized {
      waiters -= 1
      if closed && waiters == 0 then releaseSelectors() else Nil
    }
    released.foreach { selector =>
      selector.selectNow(): Unit // drops the closed channel's key, releasing its descriptor
      SelectorPool.give(selector)
    }
  }

  /** Takes the selectors off this handle, for the pool. Holding `this`. */
  private def releaseSelectors(): List[Selector] = {
    val all = List(reading, writing).collect { case s: Selector => s }
    reading = null
    writing = null
    all
  }

  /** Closes the channel, its descriptor released by the time this returns: a registered channel's descriptor is
    * released only once each selector it is registered with has selected, which one blocked with nothing else to do, or
    * a reactor not being run, would not get to, so the reactors' selectors drop its key here (`dropCancelledKeys`), and
    * a listener's port is free, and the other end of a pipe or socket sees the end, as on the other backends. The
    * reactors are then told, so that the commands pending on it there fail, as `Ops.close` says. An op waiting on one
    * of the channel's own selectors is woken, to find it closed, and the selectors go back to the pool once none waits.
    */
  def close(): Unit = {
    val (waking, idle) = synchronized {
      closed = true
      val waking = List(reading, writing).collect { case s: Selector => s }
      (waking, if waiters == 0 then releaseSelectors() else Nil)
    }
    val reactors = scala.jdk.CollectionConverters.IteratorHasAsScala(registrations.iterator).asScala.toList.distinct
    try {
      channel.close()
      // The JDK releases a registered channel's descriptor once each selector it is registered with has selected, so
      // that a listener's port is free, and the peer sees the end, once the close returns, as on the other backends.
      reactors.foreach(_.dropCancelledKeys())
      reactors.foreach(_.closed(this))
    } finally {
      try {
        waking.foreach(_.wakeup())
        idle.foreach { selector =>
          selector.selectNow(): Unit // drops the cancelled key now, not when the pool next lends the selector
          SelectorPool.give(selector)
        }
      } finally if bound != null then bound.remove()
    }
  }

  /** The channel, as the kind an op needs. */
  def as[C <: SelectableChannel](kind: Class[C], op: String): C =
    if kind.isInstance(channel) then kind.cast(channel)
    else throw new IOException(s"$op needs a ${kind.getSimpleName}, not $channel")
}

/** Selectors for `Selectable.enterWait` to reuse: on macOS, opening one makes a kqueue and a wake-up pipe, and closing
  * it closes them, which a connection's ops would otherwise pay for on every connection.
  */
private[nio] object SelectorPool {

  /** Selectors kept at most, beyond which a returned one is closed. */
  private inline val Kept = 256

  private val idle = new java.util.concurrent.ConcurrentLinkedQueue[Selector]()
  private val count = new java.util.concurrent.atomic.AtomicInteger()

  def take(): Selector = {
    val kept = idle.poll()
    if kept == null then Selector.open()
    else {
      count.decrementAndGet()
      kept
    }
  }

  /** Takes back a selector no op waits on, whose channel has been closed. A `selectNow` drops the cancelled key, which
    * also lets the JDK release the channel's descriptor, so the other end sees it close at once, and clears a wake-up
    * left from a cancel or the close.
    */
  def give(selector: Selector): Unit = {
    try {
      selector.selectNow(): Unit
      if count.incrementAndGet() <= Kept then idle.add(selector): Unit
      else {
        count.decrementAndGet()
        selector.close()
      }
    } catch case _: java.io.IOException | _: java.nio.channels.ClosedSelectorException => selector.close()
  }
}

/** A regular file. Like POSIX, which reports a regular file always ready, it never waits: reads and writes go straight
  * to the file.
  */
private[nio] final class RegularFile(val channel: FileChannel) extends Entry {

  def description: String = s"file ${channel.toString}"
  def read(buf: ByteBuffer): Int = RegularFile.failing(channel.read(buf))
  def write(buf: ByteBuffer): Int = RegularFile.failing(channel.write(buf))
  def read(bufs: Array[ByteBuffer]): Long = RegularFile.failing(channel.read(bufs))
  def write(bufs: Array[ByteBuffer]): Long = RegularFile.failing(channel.write(bufs))
  def read(buf: ByteBuffer, offset: Long): Int = RegularFile.failing(channel.read(buf, offset))
  def write(buf: ByteBuffer, offset: Long): Int = RegularFile.failing(channel.write(buf, offset))
  def force(): Unit = RegularFile.failing(channel.force(true)) // the metadata too, as fsync syncs it
  def close(): Unit = channel.close()
}

private[nio] object RegularFile {

  /** Runs a `FileChannel` call with its unchecked failures as the `IOException` the Native backends' `EBADF` gives:
    * reading a file opened for writing, or writing one opened for reading.
    */
  inline def failing[A](inline call: A): A =
    try call
    catch {
      case _: java.nio.channels.NonReadableChannelException =>
        throw new IOException("Failed to read: the file was opened for writing")
      case _: java.nio.channels.NonWritableChannelException =>
        throw new IOException("Failed to write: the file was opened for reading")
    }
}

/** The socket file a Unix socket bound at `path`, known by its device and inode once bound: removed when the socket
  * closes only if it is still that file, not one a later listener bound in its place.
  */
private[nio] final class BoundFile(val path: Path) {
  private val id = BoundFile.idOf(path)

  def remove(): Unit = if id != null && BoundFile.idOf(path) == id then Files.deleteIfExists(path): Unit
}

private[nio] object BoundFile {
  private val noFollow = java.nio.file.LinkOption.NOFOLLOW_LINKS

  /** The file at `path` as its device and inode, from the JDK's `unix` attribute view; null if there is none. */
  def idOf(path: Path): (Long, Long) | Null =
    try
      (
        Files.getAttribute(path, "unix:dev", noFollow).asInstanceOf[Long],
        Files.getAttribute(path, "unix:ino", noFollow).asInstanceOf[Long]
      )
    catch case _: java.nio.file.NoSuchFileException => null

  /** Removes a socket file at `path` for a socket to bind there, as kqueue does; fails if another kind of file is. */
  def replaceSocket(path: Path): Unit =
    if Files.exists(path, noFollow) then {
      val mode = Files.getAttribute(path, "unix:mode", noFollow).asInstanceOf[Int]
      if (mode & 0xf000) != 0xc000 then
        throw new IOException(s"Failed to bind socket: $path exists and is not a socket")
      Files.deleteIfExists(path): Unit
    }
}

/** Either kind of socket address. */
private[nio] type SocketAddress = IpAddress | UnixAddress

/** NIO's handles, each holding the entry of its channel (`NioCapabilities.Handle`), opened by the ops (`NioOps`), which
  * call the openers here.
  *
  * Sockets, listeners, datagram sockets and pipes are NIO's selectable channels in non-blocking mode, Unix stream
  * sockets among them. A regular file is a `FileChannel`, which never waits. The JDK has neither Unix datagram channels
  * nor a way to wait on a FIFO, so NIO has neither capability. Writing to a pipe or socket whose reader has gone fails
  * with an `IOException`: the JVM ignores `SIGPIPE`.
  */
object NioHandles {

  /** Connections a listener queues before it accepts them, as `PosixSockets.maxConnections`: the kernel caps it at its
    * own limit, so a raised limit takes effect.
    */
  private inline val Backlog = 4096

  /** Gives `entry` a handle. */
  private[nio] def add(entry: Entry): Handle = new Handle(entry)

  /** What `handle` names; throws if it is not open. */
  private[nio] def entry(handle: Handle): Entry = {
    if !handle.isOpen then throw gears.async.io.internal.HandleClosedException.notOpen(handle)
    handle.entry
  }

  /** Opens a regular file, blocking for the disk: the `open` op's body, run on the blocker pool, and for code with no
    * reactor.
    */
  def openFile(path: String, write: Boolean): NioKinds.File = NioKinds.file(openFileHandle(path, write))

  private def openFileHandle(path: String, write: Boolean): Handle = {
    val file = Path.of(path)
    if !Files.exists(file) then throw new NoSuchFileException(path)
    if !Files.isRegularFile(file) then throw new IOException(s"Not a regular file: $path")
    add(RegularFile(FileChannel.open(file, if write then StandardOpenOption.WRITE else StandardOpenOption.READ)))
  }

  private[nio] def connectStream(address: SocketAddress, options: SocketOptions = SocketOptions.default): Handle = {
    val channel = SocketChannel.open(familyOf(address))
    selectable(channel, null) {
      channel.configureBlocking(false)
      val kind =
        if address.isInstanceOf[IpAddress] then SocketOption.Kind.Connection else SocketOption.Kind.UnixConnection
      applyOptions(channel, options, kind)
      // A Unix socket usually connects at once; `ConnectTo` checks.
      try channel.connect(socketAddress(address))
      catch case e: java.net.ConnectException => throw NioHandles.connectFailed(e)
    }
  }

  def listen(address: IpAddress, options: SocketOptions = SocketOptions.default): NioKinds.Listener = {
    val channel = ServerSocketChannel.open(familyOf(address))
    val handle = selectable(channel, null) {
      channel.configureBlocking(false)
      channel.setOption(StandardSocketOptions.SO_REUSEADDR, true) // whatever the JDK's default (see `Ops.listen`)
      applyOptions(channel, options, SocketOption.Kind.Listener) // before binding (see `SocketOption.opening`)
      channel.bind(socketAddress(address), Backlog)
    }
    handle.entry.asInstanceOf[Selectable].accepting = options
    NioKinds.listener(handle)
  }

  /** Gives `client`, accepted by the listener `listener`, the listener's options for the connections it accepts
    * (`SocketOption.accepted`): for an IP one `NoDelay` on unless they set it off; for either its send buffer; its
    * receive buffer it has from the listener. A client reset at once may refuse them: it is delivered all the same, its
    * first read failing.
    */
  private[nio] def accepted(listener: Handle, client: SocketChannel): Unit =
    listener.entry match {
      case s: Selectable if s.accepting != null =>
        val kind =
          if s.channel.asInstanceOf[ServerSocketChannel].getLocalAddress.isInstanceOf[UnixDomainSocketAddress] then
            SocketOption.Kind.UnixListener
          else SocketOption.Kind.Listener
        try for (option, value) <- SocketOption.accepted(s.accepting.nn, kind) do setOption(client, option, value)
        catch case _: IOException => ()
      case _ => ()
    }

  /** Whether the JDK has keepalive's timings here (`jdk.net.ExtendedSocketOptions`), which some platforms lack. */
  lazy val keepAliveTimings: Boolean = {
    val probe = SocketChannel.open()
    try probe.supportedOptions().contains(jdk.net.ExtendedSocketOptions.TCP_KEEPIDLE)
    finally probe.close()
  }

  /** Whether the JDK has don't-fragment here (`ExtendedSocketOptions.IP_DONTFRAGMENT`, JDK 19). */
  lazy val dontFragment: Boolean = {
    val probe = DatagramChannel.open()
    try probe.supportedOptions().contains(jdk.net.ExtendedSocketOptions.IP_DONTFRAGMENT)
    finally probe.close()
  }

  /** Whether the system has `SO_REUSEPORT` for the JDK, for listeners and datagram sockets both. */
  lazy val reusePort: Boolean = {
    val listener = ServerSocketChannel.open()
    val datagram = DatagramChannel.open()
    try
      listener.supportedOptions().contains(StandardSocketOptions.SO_REUSEPORT) &&
        datagram.supportedOptions().contains(StandardSocketOptions.SO_REUSEPORT)
    finally {
      listener.close()
      datagram.close()
    }
  }

  /** Applies `options` to a socket's channel, of kind `kind`, as it opens, before it binds (see
    * `SocketOption.opening`); a listener's send buffer, which a `ServerSocketChannel` has none of, goes to each
    * connection it accepts instead (`accepted`).
    */
  private def applyOptions(
      channel: java.nio.channels.NetworkChannel,
      options: SocketOptions,
      kind: SocketOption.Kind
  ): Unit =
    for (option, value) <- SocketOption.opening(options, kind) do
      if !((kind == SocketOption.Kind.Listener || kind == SocketOption.Kind.UnixListener) &&
          option == SocketOption.SendBuffer)
      then setOption(channel, option, value)

  /** Sets `option` on `channel`. */
  private[nio] def setOption(channel: java.nio.channels.NetworkChannel, option: SocketOption[?], value: Any): Unit =
    option match {
      case SocketOption.NoDelay =>
        channel.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.valueOf(value.asInstanceOf[Boolean]))
      case SocketOption.KeepAlive =>
        value.asInstanceOf[SocketOption.KeepAlive.Value] match {
          case SocketOption.KeepAlive.Off =>
            channel.setOption(StandardSocketOptions.SO_KEEPALIVE, java.lang.Boolean.FALSE)
          case SocketOption.KeepAlive.On(idle, interval, count) =>
            channel.setOption(StandardSocketOptions.SO_KEEPALIVE, java.lang.Boolean.TRUE)
            channel.setOption(jdk.net.ExtendedSocketOptions.TCP_KEEPIDLE, Integer.valueOf(idle))
            channel.setOption(jdk.net.ExtendedSocketOptions.TCP_KEEPINTERVAL, Integer.valueOf(interval))
            channel.setOption(jdk.net.ExtendedSocketOptions.TCP_KEEPCOUNT, Integer.valueOf(count))
        }
      case SocketOption.ReceiveBuffer =>
        channel.setOption(StandardSocketOptions.SO_RCVBUF, Integer.valueOf(value.asInstanceOf[Int]))
      case SocketOption.SendBuffer =>
        channel.setOption(StandardSocketOptions.SO_SNDBUF, Integer.valueOf(value.asInstanceOf[Int]))
      case SocketOption.DontFragment =>
        channel.setOption(
          jdk.net.ExtendedSocketOptions.IP_DONTFRAGMENT,
          java.lang.Boolean.valueOf(value.asInstanceOf[Boolean])
        )
      case SocketOption.ReusePort =>
        channel.setOption(StandardSocketOptions.SO_REUSEPORT, java.lang.Boolean.valueOf(value.asInstanceOf[Boolean]))
    }

  /** `option`'s value on `channel`, as the system has it. */
  private[nio] def optionOf(channel: java.nio.channels.NetworkChannel, option: SocketOption[?]): Any = option match {
    case SocketOption.NoDelay   => channel.getOption(StandardSocketOptions.TCP_NODELAY).booleanValue
    case SocketOption.KeepAlive =>
      if !channel.getOption(StandardSocketOptions.SO_KEEPALIVE).booleanValue then SocketOption.KeepAlive.Off
      else
        SocketOption.KeepAlive.On(
          channel.getOption(jdk.net.ExtendedSocketOptions.TCP_KEEPIDLE).intValue,
          channel.getOption(jdk.net.ExtendedSocketOptions.TCP_KEEPINTERVAL).intValue,
          channel.getOption(jdk.net.ExtendedSocketOptions.TCP_KEEPCOUNT).intValue
        )
    case SocketOption.ReceiveBuffer => channel.getOption(StandardSocketOptions.SO_RCVBUF).intValue
    case SocketOption.SendBuffer    => channel.getOption(StandardSocketOptions.SO_SNDBUF).intValue
    case SocketOption.DontFragment  => channel.getOption(jdk.net.ExtendedSocketOptions.IP_DONTFRAGMENT).booleanValue
    case SocketOption.ReusePort     => channel.getOption(StandardSocketOptions.SO_REUSEPORT).booleanValue
  }

  def listen(address: UnixAddress, options: SocketOptions): NioKinds.Listener = {
    val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    val path = Path.of(address.path)
    val handle = selectable(channel, path) {
      channel.configureBlocking(false)
      applyOptions(channel, options, SocketOption.Kind.UnixListener) // before binding (see `SocketOption.opening`)
      BoundFile.replaceSocket(path) // a socket file already there, but no other kind of file
      channel.bind(socketAddress(address), Backlog)
    }
    handle.entry.asInstanceOf[Selectable].accepting = options
    NioKinds.listener(handle)
  }

  /** A datagram socket bound to `local`. */
  def bindDatagram(local: IpAddress): NioKinds.BoundDatagram = NioKinds.boundDatagram(datagram(local, null))

  /** A datagram socket connected to `remote`, bound to `local` if given. */
  def connectDatagram(local: IpAddress | Null, remote: IpAddress): NioKinds.ConnectedDatagram = {
    require(
      local == null || familyOf(local) == familyOf(remote),
      "Local and remote addresses must be of the same family"
    )
    NioKinds.connectedDatagram(datagram(local, remote))
  }

  private[nio] def datagram(
      local: IpAddress | Null,
      remote: IpAddress | Null,
      options: SocketOptions = SocketOptions.default
  ): Handle = {
    val channel = DatagramChannel.open(familyOf(if local != null then local else remote.nn))
    selectable(channel, null) {
      channel.configureBlocking(false)
      applyOptions(
        channel,
        options,
        if remote == null then SocketOption.Kind.BoundDatagram else SocketOption.Kind.ConnectedDatagram
      )
      if local != null then channel.bind(socketAddress(local))
      if remote != null then channel.connect(socketAddress(remote)) // binds an ephemeral local port
    }
  }

  def pipe(): (NioKinds.Readable, NioKinds.Writable) = {
    val pipe = Pipe.open()
    val source = selectable(pipe.source(), null)(pipe.source().configureBlocking(false))
    val sink =
      try selectable(pipe.sink(), null)(pipe.sink().configureBlocking(false))
      catch {
        case t: Throwable =>
          close(source)
          throw t
      }
    (NioKinds.readable(source), NioKinds.writable(sink))
  }

  def close(handle: Handle): Unit = {
    if !handle.markClosed() then throw gears.async.io.internal.HandleClosedException.notOpen(handle)
    handle.entry.close()
  }

  /** Shuts a connected stream socket down for writing (see `StreamSocketOps.shutdownOutput`). */
  def shutdownOutput(handle: Handle): Unit = entry(handle) match {
    case entry: Selectable =>
      entry.channel match {
        case socket: SocketChannel if socket.isConnected => socket.shutdownOutput(): Unit
        case _ => throw new IOException(s"Handle $handle is not a connected stream socket")
      }
    case _ => throw new IOException(s"Handle $handle is not a connected stream socket")
  }

  /** Runs `setup` on `channel` and gives it a handle, closing it again if `setup` fails. */
  private def selectable(channel: SelectableChannel, unixPath: Path | Null)(setup: => Any): Handle = {
    var bound: BoundFile | Null = null
    try {
      setup
      if unixPath != null then bound = BoundFile(unixPath)
      add(Selectable(channel, bound))
    } catch {
      case t: Throwable =>
        channel.close()
        if bound != null then bound.nn.remove()
        throw t
    }
  }

  private[nio] def familyOf(address: SocketAddress): StandardProtocolFamily = address match {
    case UnixAddress(_)       => StandardProtocolFamily.UNIX
    case IpAddress.IPv4(_, _) => StandardProtocolFamily.INET
    case IpAddress.IPv6(_, _) => StandardProtocolFamily.INET6
  }

  /** The JDK's form of `address`. Hosts must be numeric, as `IpAddress` requires, so building one never resolves a
    * name.
    */
  private[nio] def socketAddress(address: SocketAddress): java.net.SocketAddress = address match {
    case UnixAddress(path) => UnixDomainSocketAddress.of(path)
    case ip: IpAddress => ipSocketAddress(gears.async.io.internal.IpText.canonical(ip)) // refused as an op refuses it
  }

  private def ipSocketAddress(address: IpAddress): java.net.SocketAddress = address match {
    case IpAddress.IPv4(host, p) => new InetSocketAddress(numeric(host, ipv6 = false), port(p))
    case IpAddress.IPv6(host, p) => new InetSocketAddress(numeric(host, ipv6 = true), port(p))
  }

  /** A numeric host as an address. `InetAddress.getByName` only checks the form of a literal, but would look up a name,
    * so anything that is not one is refused first, with the kqueue backend's message.
    */
  private def numeric(host: String, ipv6: Boolean): InetAddress = {
    def invalid = new IOException(s"Invalid numeric IP address: $host")
    val literal =
      if ipv6 then host.contains(':') else host.nonEmpty && host.forall(c => c == '.' || (c >= '0' && c <= '9'))
    if !literal then throw invalid
    try InetAddress.getByName(host)
    catch case _: java.net.UnknownHostException => throw invalid
  }

  /** Checks a port as the kqueue backend does, with its message. */
  private[nio] def port(port: Int): Int = {
    require(port >= 0 && port <= 65535, s"Port must be between 0 and 65535: $port")
    port
  }

  /** The address `handle`'s socket is bound to, or with `peer` its peer's, as its channel reports it: a Unix one's
    * path, empty for a socket with none, and an IP one's address as `ipAddress` gives it.
    */
  private[nio] def addressOf(handle: Handle, peer: Boolean): SocketAddress = {
    val address: java.net.SocketAddress | Null = entry(handle) match {
      case s: Selectable =>
        s.channel match {
          case c: SocketChannel       => if peer then c.getRemoteAddress else c.getLocalAddress
          case c: ServerSocketChannel => if peer then null else c.getLocalAddress
          case c: DatagramChannel     => if peer then c.getRemoteAddress else c.getLocalAddress
          case _                      => null
        }
      case _ => null
    }
    address match {
      case null => throw new IOException(s"$handle has no ${if peer then "peer" else "address"}")
      case unix: UnixDomainSocketAddress => UnixAddress(unix.getPath.toString)
      case ip                            => ipAddress(ip.nn)
    }
  }

  /** A failed connection, with the kqueue backend's message. */
  private[nio] def connectFailed(e: IOException): IOException =
    new IOException(s"Failed to connect socket: ${e.getMessage}", e)

  /** The `IpAddress` of a socket address the JDK reports for an IP socket. */
  private[nio] def ipAddress(socket: java.net.SocketAddress): IpAddress = socket match {
    case ip: InetSocketAddress =>
      ip.getAddress match {
        case v4: Inet4Address => IpAddress.IPv4(v4.getHostAddress, ip.getPort)
        case v6 => IpAddress.IPv6(gears.async.io.internal.IpText.ipv6(v6.getAddress), ip.getPort) // as inet_ntop
      }
    case other => throw new IOException(s"Not an IP address: $other")
  }
}
