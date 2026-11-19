package gears.async.io
package posix

import gears.async.io.shareable.Checks
import gears.async.io.shareable.IpAddresses

import java.io.IOException
import java.nio.charset.StandardCharsets
import scala.scalanative.annotation.alwaysinline
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.arpa.inet
import scala.scalanative.posix.errno
import scala.scalanative.posix.netinet.in
import scala.scalanative.posix.netinet.inOps.*
import scala.scalanative.posix.string
import scala.scalanative.posix.sys.socket
import scala.scalanative.posix.sys.socketOps.*
import scala.scalanative.posix.sys.uio
import scala.scalanative.posix.sys.uioOps.*
import scala.scalanative.posix.sys.un
import scala.scalanative.posix.sys.unOps.*
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

  /** Binds `address`. Port zero asks the OS to choose a free port. */
  def bindIp(fd: Int, address: IpSocketAddress): Unit = withIpSocketAddr(address)(bindAddr(fd, _, _))

  /** Starts connecting `fd` to `address`, as `connectUnixAddr` does. */
  def connectIp(fd: Int, address: IpSocketAddress): Unit = withIpSocketAddr(address)(connectAddr(fd, _, _))

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

  private def copyAddress(address: IpAddress, dest: Ptr[Byte]): Unit = {
    val bytes = address.bytes
    var i = 0
    while i < bytes.length do {
      dest(i) = bytes(i)
      i += 1
    }
  }

  private def createIPv4SocketAddr(address: IpSocketAddress)(implicit z: Zone): Ptr[socket.sockaddr] = {
    val addr = alloc[in.sockaddr_in]()
    addr.sin_len = sizeOf[in.sockaddr_in].toUByte
    addr.sin_family = socket.AF_INET.toUShort
    addr.sin_port = inet.htons(address.port.toUShort)
    copyAddress(address.address, addr.at3.asInstanceOf[Ptr[Byte]])
    addr.asInstanceOf[Ptr[socket.sockaddr]]
  }

  private def createIPv6SocketAddr(address: IpSocketAddress)(implicit z: Zone): Ptr[socket.sockaddr] = {
    val addr = alloc[in.sockaddr_in6]()
    addr.sin6_len = sizeOf[in.sockaddr_in6].toUByte
    addr.sin6_family = socket.AF_INET6.toUShort
    addr.sin6_port = inet.htons(address.port.toUShort)
    copyAddress(address.address, addr.at4.asInstanceOf[Ptr[Byte]])
    addr.sin6_scope_id = address.address.scopeId.toUInt
    addr.asInstanceOf[Ptr[socket.sockaddr]]
  }

  /** Decodes an IP socket address filled in by the kernel: an IPv4 peer of a dual-stack socket, IPv4-mapped and with no
    * scope, as the IPv4 address it is, as the JDK reports it.
    */
  def decodeIp(addr: Ptr[socket.sockaddr]): IpSocketAddress = {
    val family = addr.sa_family.toInt
    if family == socket.AF_INET then {
      val v4 = addr.asInstanceOf[Ptr[in.sockaddr_in]]
      val bytes = new Array[Byte](4)
      val from = v4.at3.asInstanceOf[Ptr[Byte]]
      for i <- 0 until 4 do bytes(i) = from(i)
      IpSocketAddress(IpAddress.fromBytes(Protocol.IPv4, bytes), inet.ntohs(v4.sin_port).toInt)
    } else if family == socket.AF_INET6 then {
      val v6 = addr.asInstanceOf[Ptr[in.sockaddr_in6]]
      val bytes = new Array[Byte](16)
      val from = v6.at4.asInstanceOf[Ptr[Byte]]
      for i <- 0 until 16 do bytes(i) = from(i)
      val address = IpAddress.fromBytes(Protocol.IPv6, bytes, v6.sin6_scope_id.toInt)
      IpSocketAddress(IpAddresses.unmapped(address), inet.ntohs(v6.sin6_port).toInt)
    } else throw new IOException(s"Unsupported address family $family")
  }

  /** Receive one packet, and its sender if `sender` is given, with `senderLength` set to its capacity before each
    * call; both are null for a caller that does not want it. Returns -1 if a non-blocking socket has no packet ready;
    * zero is a valid empty datagram, not EOF. A packet larger than capacity fills it, the rest being dropped, as the
    * JDK and Network.framework drop it, and the count returned is the bytes that fitted: the packet is consumed either
    * way, so failing would only lose what fitted.
    *
    * With `recvfrom`, whose arguments are the call's own: a `recvmsg` needs a message header and a vector built for
    * it, each try, which is most of what a try that finds nothing costs here.
    */
  def receiveDatagram(
      fd: Int,
      buffer: Ptr[Byte],
      capacity: Int,
      sender: Ptr[socket.sockaddr],
      senderLength: Ptr[socket.socklen_t]
  ): Int = {
    require(capacity > 0, "Datagram buffer capacity must be positive")
    var read = Unblocked.recvfrom(fd, buffer, capacity.toCSize, 0, sender, senderLength) // non-blocking sockets here
    while (read < 0 && errno.errno == errno.EINTR) {
      read = Unblocked.recvfrom(fd, buffer, capacity.toCSize, 0, sender, senderLength)
    }
    if (read < 0) {
      if (errno.errno == errno.EAGAIN || errno.errno == errno.EWOULDBLOCK) -1
      else throw new IOException(s"Failed to receive datagram: ${cError()}")
    } else read.toInt
  }

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
    val count = NonBlocking.fill(vectors, bufs)
    val length = Checks.remaining(bufs)
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

  /** Sends exactly one packet, returning false if the socket would block. A null destination sends to the peer of a
    * connected datagram socket.
    */
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

  /** Builds a socket address for `address` and passes it to `use`, valid only during the call. */
  def withIpSocketAddr[A](address: IpSocketAddress)(use: (Ptr[socket.sockaddr], socket.socklen_t) => A): A =
    Zone.acquire { implicit z =>
      address.address.protocol match {
        case Protocol.IPv4 => use(createIPv4SocketAddr(address), sizeOf[in.sockaddr_in].toUInt)
        case Protocol.IPv6 => use(createIPv6SocketAddr(address), sizeOf[in.sockaddr_in6].toUInt)
      }
    }

  /** Whether the stream socket `fd` has a peer: so once its connect, begun without blocking, has gone through. */
  def isConnected(fd: Int): Boolean = {
    val storage = stackalloc[socket.sockaddr_storage]()
    val length = stackalloc[socket.socklen_t]()
    !length = sizeOf[socket.sockaddr_storage].toUInt
    socket.getpeername(fd, storage.asInstanceOf[Ptr[socket.sockaddr]], length) == 0
  }

  /** The address the socket `fd` is bound to, or with `peer` its peer's, as the system has it (`getsockname`,
    * `getpeername`): an IPv4 peer of a dual-stack socket as IPv4, as `decode` gives it.
    */
  def addressOf(fd: Int, peer: Boolean): IpSocketAddress | UnixAddress = {
    val storage = stackalloc[socket.sockaddr_storage]()
    val length = stackalloc[socket.socklen_t]()
    !length = sizeOf[socket.sockaddr_storage].toUInt
    val address = storage.asInstanceOf[Ptr[socket.sockaddr]]
    val result = if peer then socket.getpeername(fd, address, length) else socket.getsockname(fd, address, length)
    if result != 0 then
      throw new IOException(s"Failed to find the ${if peer then "peer" else "address"} of descriptor $fd: ${cError()}")
    decode(address, !length)
  }

  /** Decodes a socket address filled in by the kernel. An unnamed Unix socket has the empty path: macOS gives it an
    * address with no path, and Linux gives a packet from one no address at all, of no length, which no IP socket's is.
    */
  def decode(addr: Ptr[socket.sockaddr], length: socket.socklen_t): IpSocketAddress | UnixAddress = {
    val family = if length.toInt == 0 then socket.AF_UNIX else addr.sa_family.toInt
    if family == socket.AF_UNIX then {
      val unix = addr.asInstanceOf[Ptr[un.sockaddr_un]]
      val headerLength = sizeOf[un.sockaddr_un] - sizeOf[CArray[CChar, un._108]]
      if length.toInt <= headerLength.toInt then UnixAddress("")
      else UnixAddress(fromCString(unix.sun_path.at(0), StandardCharsets.UTF_8))
    } else decodeIp(addr)
  }

  /** Shuts down `fd` for writing, leaving it open for reading (see `StreamSockets.shutdownOutput`). */
  def shutdownOutput(fd: Int): Unit = {
    if (socket.shutdown(fd, socket.SHUT_WR) < 0) {
      throw new IOException(
        s"Failed to shut down socket for writing: ${cError()}"
      )
    }
  }

  def maxPathLength: Int = {
    // Scala Native reserves 108 bytes, but Darwin's sun_path holds only 104.
    if (scala.scalanative.meta.LinktimeInfo.isMac) 104
    else sizeOf[CArray[CChar, un._108]]
  }
}
