package gears.async.io
package internal

import java.io.IOException
import java.nio.charset.StandardCharsets
import scala.scalanative.annotation.alwaysinline
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.arpa.inet
import scala.scalanative.posix.errno
import scala.scalanative.posix.fcntl
import scala.scalanative.posix.netinet.in
import scala.scalanative.posix.netinet.inOps.{*, given}
import scala.scalanative.posix.string
import scala.scalanative.posix.sys.socket
import scala.scalanative.posix.sys.socketOps.{*, given}
import scala.scalanative.posix.sys.uio
import scala.scalanative.posix.sys.uioOps.{*, given}
import scala.scalanative.posix.sys.un
import scala.scalanative.posix.sys.unOps.{*, given}
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import Sockets.Flavor
import Sockets.Transport
import PosixErr.cError

object PosixSockets {
  def open(flavor: Flavor, transport: Transport): Int = {
    val posixFlavor = flavor match {
      case Flavor.Unix => socket.AF_UNIX
      case Flavor.IPv4 => socket.AF_INET
      case Flavor.IPv6 => socket.AF_INET6
    }
    val posixTransport = transport match {
      case Transport.Stream   => socket.SOCK_STREAM
      case Transport.Datagram => socket.SOCK_DGRAM
    }
    val fd = socket.socket(posixFlavor, posixTransport, 0)
    if (fd < 0) {
      throw new IOException(
        s"Failed to open socket: ${cError()}"
      )
    }
    try setNoSigPipe(fd)
    catch {
      case e: IOException =>
        unistd.close(fd)
        throw e
    }
    fd
  }

  /** posixlib does not expose `SO_NOSIGPIPE`; these are its values on macOS and FreeBSD. */
  private val SO_NOSIGPIPE: CInt = if (scala.scalanative.meta.LinktimeInfo.isFreeBSD) 0x0800 else 0x1022

  /** Makes writing to a socket whose peer has gone fail with `EPIPE` instead of raising `SIGPIPE`, which would end the
    * whole process. Every socket created here gets it: on macOS and FreeBSD as the socket's own `SO_NOSIGPIPE`;
    * elsewhere, Linux among them, which has no such option, by ignoring the signal for the process (`SigPipe`).
    */
  def setNoSigPipe(fd: Int): Unit =
    if LinktimeInfo.isMac || LinktimeInfo.isFreeBSD then {
      val on = stackalloc[CInt]()
      !on = 1
      if (socket.setsockopt(fd, socket.SOL_SOCKET, SO_NOSIGPIPE, on.asInstanceOf[Ptr[Byte]], sizeOf[CInt].toUInt) < 0)
        throw new IOException(s"Failed to set SO_NOSIGPIPE: ${cError()}")
    } else SigPipe.ignored: Unit

  /** The `ioctl` request that sets or clears non-blocking mode, `_IOW('f', 126, int)` on macOS and FreeBSD. posixlib
    * does not define it.
    */
  private inline val FIONBIO = 0x8004667eL

  /** Puts `fd` in non-blocking mode: on macOS and FreeBSD with one `ioctl(FIONBIO)`, elsewhere with `fcntl`, which
    * takes two calls, to read the flags and to set them again.
    */
  def setNonBlocking(fd: Int): Unit = {
    val failed =
      if LinktimeInfo.isMac || LinktimeInfo.isFreeBSD then {
        val on = stackalloc[CInt]()
        !on = 1
        Unblocked.ioctl(fd, FIONBIO.toUSize, on) < 0
      } else {
        val flags = fcntl.fcntl(fd, fcntl.F_GETFL, 0)
        flags < 0 || fcntl.fcntl(fd, fcntl.F_SETFL, flags | fcntl.O_NONBLOCK) < 0
      }
    if failed then throw new IOException(s"Failed to set socket descriptor to non-blocking: ${cError()}")
  }

  /** The backlog listeners ask for. The kernel caps it at its own limit (`kern.ipc.somaxconn` on macOS, 128 unless
    * raised; `net.core.somaxconn` on Linux), so asking for more than `SOMAXCONN` lets a raised limit take effect:
    * connections that arrive while the queue is full are reset, which a burst of clients sees.
    */
  def maxConnections: Int = 4096

  @alwaysinline
  private def unixSocketAddrLen(pathLength: Int): socket.socklen_t =
    (sizeOf[socket.sa_family_t].toInt + pathLength + 1).toUInt

  /** Binds `fd` to the Unix path `sock`, replacing a socket file there if `removeExisting`, but no other kind of file,
    * which fails the bind; returns the bound file's identity, for removing it on close only if it is still that file.
    */
  def bindUnixAddr(fd: Int, sock: String, removeExisting: Boolean): PosixFileOps.FileId = {
    Sockets.checkUnixPathLength(sock)
    Zone.acquire { implicit z =>
      val path = toCString(sock, StandardCharsets.UTF_8)
      if (removeExisting) {
        val existing = PosixFileOps.fileId(path)
        if (existing != null) {
          if (!existing.nn.socket) throw new IOException(s"Failed to bind socket: $sock exists and is not a socket")
          PosixFileOps.safeUnlink(path)
        }
      }
      val pathLength = string.strlen(path).toInt
      val addr = createUnixSocketAddr(path, pathLength)
      val err = socket.bind(fd, addr, unixSocketAddrLen(pathLength))
      if (err < 0) {
        throw new IOException(
          s"Failed to bind socket: ${cError()}"
        )
      }
      val bound = PosixFileOps.fileId(path)
      if (bound == null) throw new IOException(s"Failed to bind socket: $sock is gone")
      bound.nn
    }
  }

  /** Lets a listening socket bind a port that still has connections in `TIME_WAIT`, such as those a server closed just
    * before it was stopped. Without it, restarting the server fails with `EADDRINUSE` for up to a minute. Call it
    * before `bind`.
    */
  def setReuseAddress(fd: Int): Unit = {
    val on = stackalloc[CInt]()
    !on = 1
    if (
      socket.setsockopt(fd, socket.SOL_SOCKET, socket.SO_REUSEADDR, on.asInstanceOf[Ptr[Byte]], sizeOf[CInt].toUInt) < 0
    )
      throw new IOException(s"Failed to set SO_REUSEADDR: ${cError()}")
  }

  /** `IPPROTO_TCP`, and its options, which posixlib does not all have: the keepalive ones differ between macOS and
    * Linux.
    */
  inline val IPPROTO_TCP = 6
  inline val TCP_NODELAY = 1
  def TCP_KEEPIDLE: Int = if LinktimeInfo.isMac || LinktimeInfo.isFreeBSD then 0x10 else 4 // TCP_KEEPALIVE on macOS
  def TCP_KEEPINTVL: Int = if LinktimeInfo.isMac || LinktimeInfo.isFreeBSD then 0x101 else 5
  def TCP_KEEPCNT: Int = if LinktimeInfo.isMac || LinktimeInfo.isFreeBSD then 0x102 else 6

  /** Don't-fragment: `IP_DONTFRAG` on macOS and FreeBSD; on Linux `IP_MTU_DISCOVER`, `IP_PMTUDISC_DO` for on and
    * `IP_PMTUDISC_DONT` for off. `IPV6_DONTFRAG` is the same on both.
    */
  inline val IPPROTO_IP = 0
  inline val IPPROTO_IPV6 = 41
  def IP_DONTFRAG: Int = 28
  inline val IP_MTU_DISCOVER = 10
  inline val IP_PMTUDISC_DONT = 0
  inline val IP_PMTUDISC_DO = 2
  inline val IPV6_DONTFRAG = 62
  def SO_REUSEPORT: Int = if LinktimeInfo.isMac || LinktimeInfo.isFreeBSD then 0x200 else 15

  /** Sets an `int` socket option, failing with an `IOException` that names it as `what`. */
  def setIntOption(fd: Int, level: Int, name: Int, value: Int, what: String): Unit = {
    val v = stackalloc[CInt]()
    !v = value
    if socket.setsockopt(fd, level, name, v.asInstanceOf[Ptr[Byte]], sizeOf[CInt].toUInt) < 0 then
      throw new IOException(s"Failed to set $what: ${cError()}")
  }

  /** Reads an `int` socket option, failing with an `IOException` that names it as `what`. */
  def intOption(fd: Int, level: Int, name: Int, what: String): Int = {
    val v = stackalloc[CInt]()
    val length = stackalloc[socket.socklen_t]()
    !length = sizeOf[CInt].toUInt
    if socket.getsockopt(fd, level, name, v.asInstanceOf[Ptr[Byte]], length) < 0 then
      throw new IOException(s"Failed to read $what: ${cError()}")
    !v
  }

  /** Makes an IPv6 socket dual stack, taking IPv4 peers as IPv4-mapped addresses, as the JDK's are: the default on
    * macOS and Linux, set all the same, since FreeBSD's is the other way. Call it before `bind`.
    */
  def setDualStack(fd: Int): Unit = {
    val off = stackalloc[CInt]()
    !off = 0
    if socket.setsockopt(fd, in.IPPROTO_IPV6, in.IPV6_V6ONLY, off.asInstanceOf[Ptr[Byte]], sizeOf[CInt].toUInt) < 0 then
      throw new IOException(s"Failed to clear IPV6_V6ONLY: ${cError()}")
  }

  def listen(fd: Int, backlog: Int): Unit = {
    if (socket.listen(fd, backlog) < 0) {
      throw new IOException(
        s"Failed to listen on socket: ${cError()}"
      )
    }
  }

  def connectUnixAddr(fd: Int, sock: String): Unit = {
    Sockets.checkUnixPathLength(sock)
    Zone.acquire { implicit z =>
      val path = toCString(sock, StandardCharsets.UTF_8)
      val pathLength = string.strlen(path).toInt
      val addr = createUnixSocketAddr(path, pathLength)
      val err = socket.connect(fd, addr, unixSocketAddrLen(pathLength))
      if (err < 0 && errno.errno != errno.EINPROGRESS) {
        throw new IOException(s"Failed to connect socket: ${cError()}")
      }
    }
  }

  /** Bind a numeric IPv4 address. Port zero asks the OS to choose a free port. */
  def bindIPv4Addr(fd: Int, host: String, port: Int): Unit = {
    Zone.acquire { implicit z =>
      bindAddr(fd, createIPv4SocketAddr(host, port), sizeOf[in.sockaddr_in].toUInt)
    }
  }

  /** Bind a numeric IPv6 address (without brackets or a scope suffix). */
  def bindIPv6Addr(fd: Int, host: String, port: Int): Unit = {
    Zone.acquire { implicit z =>
      bindAddr(fd, createIPv6SocketAddr(host, port), sizeOf[in.sockaddr_in6].toUInt)
    }
  }

  def connectIPv4Addr(fd: Int, host: String, port: Int): Unit = {
    Zone.acquire { implicit z =>
      connectAddr(fd, createIPv4SocketAddr(host, port), sizeOf[in.sockaddr_in].toUInt)
    }
  }

  def connectIPv6Addr(fd: Int, host: String, port: Int): Unit = {
    Zone.acquire { implicit z =>
      connectAddr(fd, createIPv6SocketAddr(host, port), sizeOf[in.sockaddr_in6].toUInt)
    }
  }

  private def bindAddr(fd: Int, addr: Ptr[socket.sockaddr], length: socket.socklen_t): Unit = {
    if (socket.bind(fd, addr, length) < 0) {
      throw new IOException(s"Failed to bind socket: ${cError()}")
    }
  }

  private def connectAddr(fd: Int, addr: Ptr[socket.sockaddr], length: socket.socklen_t): Unit = {
    if (socket.connect(fd, addr, length) < 0 && errno.errno != errno.EINPROGRESS) {
      throw new IOException(s"Failed to connect socket: ${cError()}")
    }
  }

  /** Check SO_ERROR after a non-blocking connection becomes writable. */
  def checkConnect(fd: Int): Unit = {
    val error = stackalloc[CInt]()
    val length = stackalloc[socket.socklen_t]()
    !length = sizeOf[CInt].toUInt
    if (socket.getsockopt(fd, socket.SOL_SOCKET, socket.SO_ERROR, error, length) < 0) {
      throw new IOException(s"Failed to check socket connection: ${cError()}")
    }
    if (!error != 0) {
      throw new IOException(s"Failed to connect socket: ${fromCString(string.strerror(!error))}")
    }
  }

  private def parseAddress(family: Int, host: String, dest: Ptr[Byte]): Unit = {
    require(!host.contains('\u0000'), "IP address must not contain a NUL character")
    Zone.acquire { implicit z =>
      val result = inet.inet_pton(family, toCString(host), dest)
      if (result == 0) throw new IOException(s"Invalid numeric IP address: $host")
      if (result < 0) throw new IOException(s"Failed to parse IP address: ${cError()}")
    }
  }

  private def createIPv4SocketAddr(host: String, port: Int)(implicit z: Zone): Ptr[socket.sockaddr] = {
    gears.async.io.internal.IpText.canonical(gears.async.io.IpAddress.IPv4(host, port)) // refused as an op refuses it
    val addr = alloc[in.sockaddr_in]()
    addr.sin_len = sizeOf[in.sockaddr_in].toUByte
    addr.sin_family = socket.AF_INET.toUShort
    addr.sin_port = inet.htons(port.toUShort)
    parseAddress(socket.AF_INET, host, addr.at3.asInstanceOf[Ptr[Byte]])
    addr.asInstanceOf[Ptr[socket.sockaddr]]
  }

  private def createIPv6SocketAddr(host: String, port: Int)(implicit z: Zone): Ptr[socket.sockaddr] = {
    gears.async.io.internal.IpText.canonical(gears.async.io.IpAddress.IPv6(host, port))
    val addr = alloc[in.sockaddr_in6]()
    addr.sin6_len = sizeOf[in.sockaddr_in6].toUByte
    addr.sin6_family = socket.AF_INET6.toUShort
    addr.sin6_port = inet.htons(port.toUShort)
    parseAddress(socket.AF_INET6, host, addr.at4.asInstanceOf[Ptr[Byte]])
    addr.asInstanceOf[Ptr[socket.sockaddr]]
  }

  /** Accept a stream socket connection of any address family.
    *
    * If the socket is non-blocking, it will throw if a connection is un-available. Use within an event loop to be
    * notified when a connection is available.
    */
  def accept(fd: Int): Int = {
    var clientFd = socket.accept(fd, null, null)
    while (clientFd < 0 && errno.errno == errno.ECONNABORTED)
      clientFd = socket.accept(fd, null, null) // reset while queued
    if (clientFd < 0) {
      if (errno.errno == errno.EAGAIN || errno.errno == errno.EWOULDBLOCK)
        // TODO: perhaps between the event and calling accept the socket was closed?
        // So test this.
        throw new IOException("No pending connections to accept")
      else
        throw new IOException(s"Failed to accept connection: ${cError()}")
    }
    clientFd
  }

  def acceptUnix(fd: Int): Int = accept(fd)

  /** Receive one packet and its sender. Returns -1 if a non-blocking socket has no packet ready; zero is a valid empty
    * datagram, not EOF. Initialize senderLength to the capacity of sender before each call. A packet larger than
    * capacity fills it, the rest being dropped, and the count returned is the bytes that fitted.
    */
  def receiveDatagram(
      fd: Int,
      buffer: Ptr[Byte],
      capacity: Int,
      sender: Ptr[socket.sockaddr],
      senderLength: Ptr[socket.socklen_t]
  ): Int = {
    require(capacity > 0, "Datagram buffer capacity must be positive")
    val iov = stackalloc[uio.iovec]()
    iov.iov_base = buffer
    iov.iov_len = capacity.toCSize
    val msg = stackalloc[socket.msghdr]()
    msg.msg_name = sender.asInstanceOf[Ptr[Byte]]
    msg.msg_namelen = !senderLength
    msg.msg_iov = iov
    msg.msg_iovlen = 1
    msg.msg_control = null
    msg.msg_controllen = 0.toUInt
    msg.msg_flags = 0
    var read = Unblocked.recvmsg(fd, msg, 0) // datagram sockets here are non-blocking
    while (read < 0 && errno.errno == errno.EINTR) {
      read = Unblocked.recvmsg(fd, msg, 0)
    }
    if (read < 0) {
      if (errno.errno == errno.EAGAIN || errno.errno == errno.EWOULDBLOCK) -1
      else throw new IOException(s"Failed to receive datagram: ${cError()}")
    } else {
      // A packet larger than the buffer fills it, the rest being dropped (MSG_TRUNC), as `recvfrom`, the JDK and
      // Network.framework drop it: the packet is consumed either way, so failing would only lose what fitted.
      !senderLength = msg.msg_namelen
      read.toInt
    }
  }

  /** Send exactly one packet, returning false if the socket would block. A null destination sends to the peer of a
    * connected datagram socket.
    */
  /** Sends `bufs`, in order, as one datagram, with one `sendmsg`, to `destination` if given: true once sent, consuming
    * them, false if it would block, as `sendDatagram`.
    */
  def sendDatagram(
      fd: Int,
      bufs: Array[java.nio.ByteBuffer],
      destination: Ptr[socket.sockaddr],
      destinationLength: socket.socklen_t
  ): Boolean =
    NativeBuffer.staged(bufs, copyIn = true, copyOut = false)(
      sendDatagramAddressable(fd, _, destination, destinationLength)
    )

  private def sendDatagramAddressable(
      fd: Int,
      bufs: Array[java.nio.ByteBuffer],
      destination: Ptr[socket.sockaddr],
      destinationLength: socket.socklen_t
  ): Boolean = {
    val vectors = stackalloc[uio.iovec](math.min(bufs.length, NonBlocking.MaxBuffers))
    val count = NonBlocking.fill(vectors, bufs, room = false)
    val length = gears.async.io.internal.Checks.remaining(bufs)
    val message = stackalloc[socket.msghdr]()
    message.msg_name = destination.asInstanceOf[Ptr[Byte]]
    message.msg_namelen = destinationLength
    message.msg_iov = vectors
    message.msg_iovlen = count
    message.msg_control = null
    message.msg_controllen = 0.toUInt
    message.msg_flags = 0
    var written = Unblocked.sendmsg(fd, message, 0)
    while (written < 0 && errno.errno == errno.EINTR) written = Unblocked.sendmsg(fd, message, 0)
    if (written < 0) {
      if (errno.errno == errno.EAGAIN || errno.errno == errno.EWOULDBLOCK) false
      else throw new IOException(s"Failed to send datagram: ${cError()}")
    } else {
      if (written.toLong != length) throw new IOException(s"Incomplete datagram: sent $written of $length bytes")
      bufs.foreach(b => b.position(b.limit()))
      true
    }
  }

  def sendDatagram(
      fd: Int,
      buffer: Ptr[Byte],
      length: Int,
      destination: Ptr[socket.sockaddr] = null,
      destinationLength: socket.socklen_t = 0.toUInt
  ): Boolean = {
    require(length >= 0, "Datagram length must be non-negative")
    var written = Unblocked.sendto(fd, buffer, length.toCSize, 0, destination, destinationLength)
    while (written < 0 && errno.errno == errno.EINTR) {
      written = Unblocked.sendto(fd, buffer, length.toCSize, 0, destination, destinationLength)
    }
    if (written < 0) {
      if (errno.errno == errno.EAGAIN || errno.errno == errno.EWOULDBLOCK) false
      else throw new IOException(s"Failed to send datagram: ${cError()}")
    } else {
      if (written.toInt != length) throw new IOException(s"Incomplete datagram: sent $written of $length bytes")
      true
    }
  }

  @alwaysinline
  private def createUnixSocketAddr(path: CString, length: Int)(implicit z: Zone): Ptr[socket.sockaddr] = {
    val addr = alloc[un.sockaddr_un]()
    addr.sun_len = unixSocketAddrLen(length).toUByte
    addr.sun_family = socket.AF_UNIX.toUShort
    string.memcpy(addr.sun_path.at(0), path, length.toCSize)
    addr.sun_path(length) = 0.toByte // null-terminate the path
    addr.asInstanceOf[Ptr[socket.sockaddr]]
  }

  /** Builds a Unix socket address for `path` and passes it to `use`, valid only during the call. */
  def withUnixSocketAddr[A](path: String)(use: (Ptr[socket.sockaddr], socket.socklen_t) => A): A = {
    Sockets.checkUnixPathLength(path)
    Zone.acquire { implicit z =>
      val cPath = toCString(path, StandardCharsets.UTF_8)
      val length = string.strlen(cPath).toInt
      use(createUnixSocketAddr(cPath, length), unixSocketAddrLen(length))
    }
  }

  /** Builds an IPv4 socket address and passes it to `use`, valid only during the call. */
  def withIPv4SocketAddr[A](host: String, port: Int)(use: (Ptr[socket.sockaddr], socket.socklen_t) => A): A =
    Zone.acquire { implicit z => use(createIPv4SocketAddr(host, port), sizeOf[in.sockaddr_in].toUInt) }

  /** Builds an IPv6 socket address and passes it to `use`, valid only during the call. */
  def withIPv6SocketAddr[A](host: String, port: Int)(use: (Ptr[socket.sockaddr], socket.socklen_t) => A): A =
    Zone.acquire { implicit z => use(createIPv6SocketAddr(host, port), sizeOf[in.sockaddr_in6].toUInt) }

  /** Shuts down `fd` for writing, leaving it open for reading (see `StreamSocketOps.shutdownOutput`). */
  def shutdownOutput(fd: Int): Unit = {
    if (socket.shutdown(fd, socket.SHUT_WR) < 0) {
      throw new IOException(
        s"Failed to shut down socket for writing: ${cError()}"
      )
    }
  }

  /** Closes `fd`. A descriptor a reactor may know is closed through `KqueueHandles.close`, which tells the reactors. */
  def close(fd: Int): Unit = {
    if (unistd.close(fd) < 0) {
      throw new IOException(
        s"Failed to close socket: ${cError()}"
      )
    }
  }

  def maxPathLength: Int = {
    // Scala Native reserves 108 bytes, but Darwin's sun_path holds only 104.
    if (scala.scalanative.meta.LinktimeInfo.isMac) 104
    else sizeOf[CArray[CChar, un._108]]
  }
}
