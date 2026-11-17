package gears.async.io.nio

import gears.async.io.Blocking
import gears.async.io.BufferPool
import gears.async.io.ByteStreams
import gears.async.io.Handles
import gears.async.io.Interface
import gears.async.io.IpAddress
import gears.async.io.IpDatagrams
import gears.async.io.IpSocketAddress
import gears.async.io.IpStreams
import gears.async.io.Pipes
import gears.async.io.Received
import gears.async.io.RegularFiles
import gears.async.io.Repeatable
import gears.async.io.Resolving
import gears.async.io.Slot
import gears.async.io.SocketOption
import gears.async.io.SocketOptionOps
import gears.async.io.SocketOptions
import gears.async.io.StreamSockets
import gears.async.io.UnixAddress
import gears.async.io.UnixStreams
import gears.async.io.shareable.Checks
import gears.async.io.shareable.IpAddresses
import gears.async.io.shareable.LendingPool

import java.nio.ByteBuffer
import scala.concurrent.duration.FiniteDuration

sealed trait NioInterface extends Interface {
  override type Op = NioOp

  override def timer(duration: FiniteDuration): NioTimerOp & Repeatable = {
    if duration.length < 0 then throw new IllegalArgumentException(s"A timer's time is not negative: $duration")
    NioMakeTimer(duration)
  }

  override def deadlineTimer(nanoTime: Long): NioDeadlineOp & Repeatable = NioMakeDeadline(nanoTime)
}

sealed trait NioPipeOps extends Pipes { self: NioByteStreams & NioInterface =>
  override def pipe(read: Slot[Readable], write: Slot[Writable]): NioOp = NioMakePipe(read, write)
}

sealed trait NioHandles extends Handles { self: NioInterface =>
  override type Handle = NioHandle

  override def close(handle: NioHandle): NioCloseOp = NioCloseHandle(handle)
}

sealed trait NioByteStreams extends ByteStreams { self: NioInterface & NioHandles =>
  override type Readable = NioReadable
  override type Writable = NioWritable

  // A file is not waited for: its reads and writes as a stream are file ops, at the position it carries.

  override def read(handle: Readable, buf: ByteBuffer): NioOp & Repeatable = handle match
    case file: NioFile => NioFileRead(file, Array(buf), -1L)
    case _             => NioRead(handle, buf)

  override def write(handle: Writable, buf: ByteBuffer): NioOp & Repeatable = handle match
    case file: NioFile => NioFileWrite(file, Array(buf), -1L)
    case _             => NioWrite(handle, buf)

  override def read(handle: Readable, bufs: Array[ByteBuffer]): NioOp & Repeatable = handle match
    case file: NioFile => NioFileRead(file, bufs, -1L)
    case _             => NioScatterRead(handle, bufs)

  override def write(handle: Writable, bufs: Array[ByteBuffer]): NioOp & Repeatable = handle match
    case file: NioFile => NioFileWrite(file, bufs, -1L)
    case _             => NioGatherWrite(handle, bufs)

  override def read(handle: Readable, pool: BufferPool, into: Slot[Received]): NioOp & Repeatable =
    pool match
      case pool: LendingPool =>
        handle match
          case file: NioFile => NioFilePooledRead(file, pool, into)
          case _             => NioPooledRead(handle, pool, into)
      case _ => throw new IllegalArgumentException("A pooled read takes a pool of this backend's (Backend.pool)")
}

/** Socket options: those the JDK has for each kind of socket, changed at any time but `ReusePort`, which is set before
  * the socket binds.
  */
sealed trait NioSocketOptionOps extends SocketOptionOps { self: NioInterface & NioHandles =>
  override def supports(option: SocketOption[?], kind: SocketOption.Kind): Boolean =
    NioSocketOptions.supports(option, kind)

  override def canChange(option: SocketOption[?], kind: SocketOption.Kind): Boolean =
    NioSocketOptions.canChange(option, kind)

  override def option[V](handle: NioHandle, option: SocketOption[V]): V =
    onSocket(handle, option)(socket => NioSocketOptions.get(socket.network, option).asInstanceOf[V])

  override def setOption[V](handle: NioHandle, option: SocketOption[V], value: V): Unit = {
    option.check(value)
    onSocket(handle, option) { socket =>
      if !canChange(option, socket.kind) then
        throw new IllegalArgumentException(s"$option is given as a socket opens, not changed")
      NioSocketOptions.set(socket.network, option, value)
    }
  }

  /** Runs `body` on `handle`, an open socket that `option` applies to; else an `IllegalArgumentException`. */
  private def onSocket[A](handle: NioHandle, option: SocketOption[?])(body: NioSocket => A): A = handle match
    case socket: NioSocket =>
      socket.claimWork()
      try
        if !SocketOption.onSocket(option, socket.kind) then throw Checks.notFor(option, socket.kind)
        if !NioSocketOptions.onOpen(option, socket.kind) then throw Checks.noOption(option, socket.kind)
        body(socket)
      finally socket.releaseWork()
    case _ => throw new IllegalArgumentException(s"$option applies to sockets, not $handle")
}

sealed trait NioStreamSockets extends StreamSockets {
  self: NioInterface & NioHandles & NioByteStreams =>
  override type Connection = NioConnection
  override type Listener = NioListener

  override def accept(handle: NioListener, into: Slot[NioConnection]): NioOp & Repeatable = NioAccept(handle, into)

  override def acceptAll(handle: NioListener, into: Slot[Array[NioConnection]], max: Int): NioOp & Repeatable =
    NioAcceptAll(handle, into, max)

  override def shutdownOutput(handle: NioConnection): NioOp = NioShutdownOutput(handle)

  override def localAddress(handle: NioListener | NioConnection): IpSocketAddress | UnixAddress =
    NioSockets.addressOf(handle, peer = false)

  override def peerAddress(handle: NioConnection): IpSocketAddress | UnixAddress =
    NioSockets.addressOf(handle, peer = true)
}

sealed trait NioIpStreams extends IpStreams {
  self: NioInterface & NioHandles & NioByteStreams & NioStreamSockets & NioSocketOptionOps =>

  override def connect(address: IpSocketAddress, options: SocketOptions, into: Slot[NioConnection]): NioOp = {
    Checks.checkOptions(options, supports, SocketOption.Kind.Connection)
    NioConnect(address, options, into)
  }

  override def listen(address: IpSocketAddress, options: SocketOptions, into: Slot[NioListener]): NioOp = {
    Checks.checkOptions(options, supports, SocketOption.Kind.Listener)
    NioListen(address, options, into)
  }
}

sealed trait NioUnixStreams extends UnixStreams {
  self: NioInterface & NioHandles & NioByteStreams & NioStreamSockets & NioSocketOptionOps =>

  override def connect(address: UnixAddress, options: SocketOptions, into: Slot[NioConnection]): NioOp = {
    Checks.checkOptions(options, supports, SocketOption.Kind.UnixConnection)
    NioConnect(Checks.unix(address), options, into)
  }

  override def listen(address: UnixAddress, options: SocketOptions, into: Slot[NioListener]): NioOp = {
    Checks.checkOptions(options, supports, SocketOption.Kind.UnixListener)
    NioListen(Checks.unix(address), options, into)
  }
}

sealed trait NioIpDatagrams extends IpDatagrams { self: NioInterface & NioHandles & NioSocketOptionOps =>
  override type BoundDatagram = NioBoundDatagram
  override type ConnectedDatagram = NioConnectedDatagram

  override def bindDatagram(local: IpSocketAddress, options: SocketOptions, into: Slot[NioBoundDatagram]): NioOp = {
    val kind = SocketOption.Kind.BoundDatagram
    Checks.checkOptions(options, supports, kind)
    NioOpenDatagram(local, null, options, kind, into)(new NioBoundDatagram(_, _))
  }

  override def connectDatagram(
      local: IpSocketAddress | Null,
      remote: IpSocketAddress,
      options: SocketOptions,
      into: Slot[NioConnectedDatagram]
  ): NioOp = {
    val kind = SocketOption.Kind.ConnectedDatagram
    Checks.checkOptions(options, supports, kind)
    val (from, to) = IpAddresses.pair(local, remote)
    NioOpenDatagram(from, to, options, kind, into)(new NioConnectedDatagram(_, _))
  }

  override def receive(handle: NioBoundDatagram, buf: ByteBuffer, from: Slot[IpSocketAddress]): NioOp & Repeatable =
    NioReceive(handle, buf, from)

  override def receive(handle: NioConnectedDatagram, buf: ByteBuffer): NioOp & Repeatable =
    NioReceive[IpSocketAddress](handle, buf, null)

  override def send(handle: NioBoundDatagram, buf: ByteBuffer, to: IpSocketAddress): NioOp & Repeatable =
    NioSend(handle, Array(buf), to)

  override def send(handle: NioConnectedDatagram, buf: ByteBuffer): NioOp & Repeatable =
    NioSend(handle, Array(buf), null)

  override def send(handle: NioBoundDatagram, bufs: Array[ByteBuffer], to: IpSocketAddress): NioOp & Repeatable =
    NioSend(handle, bufs, to)

  override def send(handle: NioConnectedDatagram, bufs: Array[ByteBuffer]): NioOp & Repeatable =
    NioSend(handle, bufs, null)

  @annotation.targetName("datagramLocalAddress")
  override def localAddress(handle: NioBoundDatagram | NioConnectedDatagram): IpSocketAddress =
    NioSockets.addressOf(handle, peer = false).asInstanceOf[IpSocketAddress]

  @annotation.targetName("datagramPeerAddress")
  override def peerAddress(handle: NioConnectedDatagram): IpSocketAddress =
    NioSockets.addressOf(handle, peer = true).asInstanceOf[IpSocketAddress]
}

sealed trait NioBlockingOps extends Blocking { self: NioInterface =>
  override def blocking(body: () => Unit): NioOp = NioBlocking(body)
  override def blocking[A <: AnyRef](body: () => A, into: Slot[A]): NioOp = NioBlockingValue(body, into)
}

sealed trait NioRegularFiles extends RegularFiles {
  self: NioInterface & NioHandles & NioByteStreams =>
  override type File = NioFile

  override def open(path: String, write: Boolean, into: Slot[NioFile]): NioOp = NioOpenFile(path, write, into)

  override def read(handle: NioFile, buf: ByteBuffer, offset: Long): NioOp & Repeatable = {
    Checks.checkOffset(offset)
    NioFileRead(handle, Array(buf), offset)
  }

  override def write(handle: NioFile, buf: ByteBuffer, offset: Long): NioOp & Repeatable = {
    Checks.checkOffset(offset)
    NioFileWrite(handle, Array(buf), offset)
  }

  override def sync(handle: NioFile): NioOp & Repeatable = NioFileSync(handle)
}

sealed trait NioResolving extends Resolving { self: NioInterface =>
  override def resolve(host: String, into: Slot[List[IpAddress]]): NioOp = NioResolve(host, into)
}

object NioOps
    extends NioInterface,
      NioHandles,
      NioByteStreams,
      NioPipeOps,
      NioSocketOptionOps,
      NioStreamSockets,
      NioIpStreams,
      NioUnixStreams,
      NioIpDatagrams,
      NioBlockingOps,
      NioRegularFiles,
      NioResolving {}
