package gears.async.io
package posix

import gears.async.io.shareable.BaseHandle
import gears.async.io.shareable.Checks
import gears.async.io.shareable.HandleOwner

import java.io.IOException
import java.nio.charset.StandardCharsets
import scala.scalanative.unsafe.*

import Sockets.Flavor
import Sockets.Transport

/** Either kind of socket address, as the system calls take them. */
type SocketAddress = IpSocketAddress | UnixAddress

/** What a POSIX backend's socket handle says of its socket: what the options and the address calls ask. */
trait PosixSocket extends BaseHandle {
  def fd: Int

  /** The kind of socket it is, which says what options apply to it. */
  def kind: SocketOption.Kind

  /** Whether it is an IPv6 socket, which is then dual stack. */
  def ipv6: Boolean
}

/** A listener's part of `PosixSocket`: the options it was opened with, of which it gives each connection it accepts
  * those that are a connection's.
  */
trait PosixListener extends PosixSocket {
  def accepting: SocketOptions
}

/** The socket file a Unix socket bound, to remove as the socket closes, if it is still that file: a listener that took
  * the path since replaced it with its own, which must stay.
  */
final class UnixBinding(val path: String, file: PosixFileOps.FileId) {
  def remove(): Unit = Zone.acquire { implicit z =>
    PosixFileOps.unlinkIfStill(toCString(path, StandardCharsets.UTF_8), file)
  }
}

/** Opens a POSIX backend's sockets, non-blocking, each as the backend's own kind of handle, which the backend makes
  * from the descriptor (`makeConnection` and the rest): the socket calls are here, and what a handle is is the
  * backend's. Writing to a socket whose peer has gone fails with `EPIPE` rather than raising `SIGPIPE`.
  */
trait PosixSocketOpener {
  type Connection <: PosixSocket
  type Listener <: PosixListener

  def makeConnection(fd: Int, kind: SocketOption.Kind, ipv6: Boolean, owner: HandleOwner): Connection
  def makeListener(
      fd: Int,
      kind: SocketOption.Kind,
      ipv6: Boolean,
      accepting: SocketOptions,
      bound: UnixBinding | Null,
      owner: HandleOwner
  ): Listener

  /** A stream socket whose connect to `address` has begun, and may have gone through (`finishConnect`). */
  final def startConnect(address: SocketAddress, options: SocketOptions, owner: HandleOwner): Connection = {
    val kind = if isIp(address) then SocketOption.Kind.Connection else SocketOption.Kind.UnixConnection
    val ipv6 = flavorOf(address) == Flavor.IPv6
    val fd = socketFor(address, Transport.Stream) { fd =>
      applyOptions(fd, ipv6, options, kind)
      connectTo(fd, address)
    }
    handleOf(fd)(makeConnection(fd, kind, ipv6, owner))
  }

  /** Whether `socket`'s connect has gone through; throws its failure if it has failed. */
  final def finishConnect(socket: Connection): Boolean = {
    PosixSockets.checkConnect(socket.fd)
    PosixSockets.isConnected(socket.fd)
  }

  /** A stream socket bound to `address` and listening. An IP one binds as `SO_REUSEADDR` does, a port whose earlier
    * connections linger in `TIME_WAIT` taken (see `IpStreams.listen`); a Unix one replaces a socket file at its path.
    */
  final def listen(address: SocketAddress, options: SocketOptions, owner: HandleOwner): Listener = {
    val ip = isIp(address)
    val kind = if ip then SocketOption.Kind.Listener else SocketOption.Kind.UnixListener
    val ipv6 = flavorOf(address) == Flavor.IPv6
    var bound: UnixBinding | Null = null
    val fd = socketFor(address, Transport.Stream) { fd =>
      if ip then PosixSockets.setReuseAddress(fd)
      applyOptions(fd, ipv6, options, kind) // before binding and listening (see `SocketOption`)
      bound = bindTo(fd, address)
      try PosixSockets.listen(fd, PosixSockets.maxConnections)
      catch
        case failure: Throwable =>
          if bound != null then bound.nn.remove()
          throw failure
    }
    handleOf(fd, bound)(makeListener(fd, kind, ipv6, options, bound, owner))
  }

  /** A connection pending on `listener`, accepted, with the options the listener gives its connections, and of its
    * listener's run; null if there is none.
    */
  final def accept(listener: Listener): Connection | Null = {
    val fd = NonBlocking.accept(listener.fd)
    if fd < 0 then null
    else {
      val unix = listener.kind.unix
      // A connection its client reset at once refuses options (EINVAL on macOS): it is delivered all the same, and
      // its first read fails, as a reset connection's does, rather than failing the accept.
      try
        for (option, value) <- SocketOption.accepted(listener.accepting, listener.kind) do
          PosixSocketOptions.set(fd, listener.ipv6, option, value)
      catch case _: IOException => ()
      val kind = if unix then SocketOption.Kind.UnixConnection else SocketOption.Kind.Connection
      handleOf(fd)(makeConnection(fd, kind, listener.ipv6, listener.owner))
    }
  }

  /** A datagram socket bound to `local`, if given, and connected to `remote`, if given, of kind `kind`, as the handle
    * `make` makes of its descriptor, whether it is IPv6, and the socket file it bound, if any.
    */
  final def openDatagram[D](
      local: SocketAddress | Null,
      remote: SocketAddress | Null,
      options: SocketOptions,
      kind: SocketOption.Kind,
      owner: HandleOwner
  )(make: (Int, Boolean, UnixBinding | Null, HandleOwner) => D): D = {
    val either = if local != null then local else remote
    if either == null then throw new IllegalArgumentException("A datagram socket needs a local or a remote address")
    val ipv6 = flavorOf(either) == Flavor.IPv6
    var bound: UnixBinding | Null = null
    val fd = socketFor(either, Transport.Datagram) { fd =>
      applyOptions(fd, ipv6, options, kind)
      if local != null then bound = bindTo(fd, local)
      try if remote != null then connectTo(fd, remote) // assigns an IP socket an ephemeral local port
      catch
        case failure: Throwable =>
          if bound != null then bound.nn.remove()
          throw failure
    }
    handleOf(fd, bound)(make(fd, ipv6, bound, owner))
  }

  /** The address `socket` is bound to, or with `peer` its peer's. Fails on a closed handle, as its ops do. */
  final def addressOf(socket: PosixSocket, peer: Boolean): SocketAddress = {
    socket.claimWork()
    try PosixSockets.addressOf(socket.fd, peer)
    finally socket.releaseWork()
  }

  // ---- what the openers share

  protected final def isIp(address: SocketAddress): Boolean = address.isInstanceOf[IpSocketAddress]

  protected final def flavorOf(address: SocketAddress): Flavor = address match {
    case UnixAddress(_)      => Flavor.Unix
    case ip: IpSocketAddress =>
      ip.address.protocol match {
        case Protocol.IPv4 => Flavor.IPv4
        case Protocol.IPv6 => Flavor.IPv6
      }
  }

  /** Applies `options` to the socket `fd`, of kind `kind`, as it opens, before it binds (see `SocketOption.opening`).
    */
  protected final def applyOptions(fd: Int, ipv6: Boolean, options: SocketOptions, kind: SocketOption.Kind): Unit =
    for (option, value) <- SocketOption.opening(options, kind) do PosixSocketOptions.set(fd, ipv6, option, value)

  /** Opens a non-blocking socket and runs `setup` on it, closing it again if `setup` fails. */
  protected final def socketFor(address: SocketAddress, transport: Transport)(setup: Int => Unit): Int = {
    val flavor = flavorOf(address)
    val fd = PosixSockets.open(flavor, transport)
    try {
      Descriptors.setNonBlocking(fd)
      if flavor == Flavor.IPv6 then PosixSockets.setDualStack(fd)
      setup(fd)
      fd
    } catch {
      case t: Throwable =>
        scala.scalanative.posix.unistd.close(fd)
        throw t
    }
  }

  /** The handle `make` makes of the open socket `fd`; if it can't be made, the socket is closed, and what it bound
    * removed.
    */
  protected final def handleOf[H](fd: Int, bound: UnixBinding | Null = null)(make: => H): H =
    try make
    catch {
      case t: Throwable =>
        if bound != null then bound.remove()
        scala.scalanative.posix.unistd.close(fd)
        throw t
    }

  /** Binds `fd`, and returns the socket file it bound if `address` is a Unix path. */
  protected final def bindTo(fd: Int, address: SocketAddress): UnixBinding | Null = address match {
    case UnixAddress(path) =>
      Checks.unix(UnixAddress(path))
      new UnixBinding(path, PosixSockets.bindUnixAddr(fd, path, removeExisting = true))
    case ip: IpSocketAddress =>
      PosixSockets.bindIp(fd, ip)
      null
  }

  protected final def connectTo(fd: Int, address: SocketAddress): Unit = address match {
    case UnixAddress(path)   => PosixSockets.connectUnixAddr(fd, path)
    case ip: IpSocketAddress => PosixSockets.connectIp(fd, ip)
  }
}
