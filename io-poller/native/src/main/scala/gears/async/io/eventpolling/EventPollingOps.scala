package gears.async.io.eventpolling

import gears.async.io.Blocking
import gears.async.io.BufferPool
import gears.async.io.ByteStreams
import gears.async.io.Fifos
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
import gears.async.io.UnixDatagrams
import gears.async.io.UnixStreams
import gears.async.io.posix.PosixSocketOptions
import gears.async.io.shareable.Checks
import gears.async.io.shareable.HandleOwner
import gears.async.io.shareable.IpAddresses
import gears.async.io.shareable.LendingPool

import java.nio.ByteBuffer
import scala.concurrent.duration.FiniteDuration

sealed trait EventPollingInterface extends Interface {
  override type Op = EventPollingOp

  override def timer(duration: FiniteDuration): EventPollingTimerOp & Repeatable = {
    if duration.length < 0 then throw new IllegalArgumentException(s"A timer's time is not negative: $duration")
    EventPollingMakeTimer(duration)
  }

  override def deadlineTimer(nanoTime: Long): EventPollingDeadlineOp & Repeatable = EventPollingMakeDeadline(nanoTime)
}

sealed trait EventPollingPipeOps extends Pipes { self: EventPollingByteStreams & EventPollingInterface =>
  override def pipe(read: Slot[Readable], write: Slot[Writable]): EventPollingPipeOp = EventPollingMakePipe(read, write)
}

sealed trait EventPollingHandles extends Handles { self: EventPollingInterface =>
  override type Handle = EventPollingHandle

  override def close(handle: EventPollingHandle): EventPollingCloseOp = EventPollingCloseHandle(handle)
}

sealed trait EventPollingByteStreams extends ByteStreams { self: EventPollingInterface & EventPollingHandles =>
  override type Readable = EventPollingReadable
  override type Writable = EventPollingWritable

  // A file is not waited for: its reads and writes as a stream are file ops, at the position it carries.

  override def read(handle: Readable, buf: ByteBuffer): EventPollingOp & Repeatable = handle match
    case file: EventPollingFile              => EventPollingFileRead(file, Array(buf), -1L)
    case waited: EventPollingWaitingReadable => EventPollingRead(waited, buf)

  override def write(handle: Writable, buf: ByteBuffer): EventPollingOp & Repeatable = handle match
    case file: EventPollingFile              => EventPollingFileWrite(file, Array(buf), -1L)
    case waited: EventPollingWaitingWritable => EventPollingWrite(waited, buf)

  override def read(handle: Readable, bufs: Array[ByteBuffer]): EventPollingOp & Repeatable = handle match
    case file: EventPollingFile              => EventPollingFileRead(file, bufs, -1L)
    case waited: EventPollingWaitingReadable => EventPollingScatterRead(waited, bufs)

  override def write(handle: Writable, bufs: Array[ByteBuffer]): EventPollingOp & Repeatable = handle match
    case file: EventPollingFile              => EventPollingFileWrite(file, bufs, -1L)
    case waited: EventPollingWaitingWritable => EventPollingGatherWrite(waited, bufs)

  override def read(handle: Readable, pool: BufferPool, into: Slot[Received]): EventPollingOp & Repeatable =
    pool match
      case pool: LendingPool =>
        handle match
          case file: EventPollingFile              => EventPollingFilePooledRead(file, pool, into)
          case waited: EventPollingWaitingReadable => EventPollingPooledRead(waited, pool, into)
      case _ => throw new IllegalArgumentException("A pooled read takes a pool of this backend's (Backend.pool)")
}

/** Socket options: every one that applies to a kind of socket, IP or Unix, changed at any time but `ReusePort`, which
  * is set before the socket binds.
  */
sealed trait EventPollingSocketOptions extends SocketOptionOps { self: EventPollingInterface & EventPollingHandles =>
  override def supports(option: SocketOption[?], kind: SocketOption.Kind): Boolean = option.appliesTo(kind)

  override def canChange(option: SocketOption[?], kind: SocketOption.Kind): Boolean =
    SocketOption.onSocket(option, kind) && option != SocketOption.ReusePort

  override def option[V](handle: EventPollingHandle, option: SocketOption[V]): V =
    onSocket(handle, option)(socket => PosixSocketOptions.get(socket.fd, socket.ipv6, option).asInstanceOf[V])

  override def setOption[V](handle: EventPollingHandle, option: SocketOption[V], value: V): Unit = {
    option.check(value)
    onSocket(handle, option) { socket =>
      if !canChange(option, socket.kind) then
        throw new IllegalArgumentException(s"$option is given as a socket opens, not changed")
      PosixSocketOptions.set(socket.fd, socket.ipv6, option, value)
    }
  }

  /** Runs `body` on `handle`, an open socket that `option` applies to; else an `IllegalArgumentException`. */
  private def onSocket[A](handle: EventPollingHandle, option: SocketOption[?])(body: EventPollingSocket => A): A =
    handle match
      case socket: EventPollingSocket =>
        socket.claimWork()
        try
          if !SocketOption.onSocket(option, socket.kind) then throw Checks.notFor(option, socket.kind)
          body(socket)
        finally socket.releaseWork()
      case _ => throw new IllegalArgumentException(s"$option applies to sockets, not $handle")
}

sealed trait EventPollingStreamSockets extends StreamSockets {
  self: EventPollingInterface & EventPollingHandles & EventPollingByteStreams =>
  override type Connection = EventPollingConnection
  override type Listener = EventPollingListener

  override def accept(handle: EventPollingListener, into: Slot[EventPollingConnection]): EventPollingAcceptOp &
    Repeatable =
    EventPollingAccept(handle, into)

  override def acceptAll(
      handle: EventPollingListener,
      into: Slot[Array[EventPollingConnection]],
      max: Int
  ): EventPollingAcceptOp & Repeatable = EventPollingAcceptAll(handle, into, max)

  override def shutdownOutput(handle: EventPollingConnection): EventPollingOp = EventPollingShutdownOutput(handle)

  override def localAddress(handle: EventPollingListener | EventPollingConnection): IpSocketAddress | UnixAddress =
    EventPollingSockets.addressOf(handle, peer = false)

  override def peerAddress(handle: EventPollingConnection): IpSocketAddress | UnixAddress =
    EventPollingSockets.addressOf(handle, peer = true)
}

sealed trait EventPollingIpStreams extends IpStreams {
  self: EventPollingInterface & EventPollingHandles & EventPollingByteStreams & EventPollingStreamSockets &
    EventPollingSocketOptions =>

  override def connect(
      address: IpSocketAddress,
      options: SocketOptions,
      into: Slot[EventPollingConnection]
  ): EventPollingOp = {
    Checks.checkOptions(options, supports, SocketOption.Kind.Connection)
    EventPollingConnect(address, options, into)
  }

  override def listen(
      address: IpSocketAddress,
      options: SocketOptions,
      into: Slot[EventPollingListener]
  ): EventPollingOp = {
    Checks.checkOptions(options, supports, SocketOption.Kind.Listener)
    EventPollingListen(address, options, into)
  }
}

sealed trait EventPollingUnixStreams extends UnixStreams {
  self: EventPollingInterface & EventPollingHandles & EventPollingByteStreams & EventPollingStreamSockets &
    EventPollingSocketOptions =>

  override def connect(
      address: UnixAddress,
      options: SocketOptions,
      into: Slot[EventPollingConnection]
  ): EventPollingOp = {
    Checks.checkOptions(options, supports, SocketOption.Kind.UnixConnection)
    EventPollingConnect(Checks.unix(address), options, into)
  }

  override def listen(
      address: UnixAddress,
      options: SocketOptions,
      into: Slot[EventPollingListener]
  ): EventPollingOp = {
    Checks.checkOptions(options, supports, SocketOption.Kind.UnixListener)
    EventPollingListen(Checks.unix(address), options, into)
  }
}

sealed trait EventPollingIpDatagrams extends IpDatagrams {
  self: EventPollingInterface & EventPollingHandles & EventPollingSocketOptions =>
  override type BoundDatagram = EventPollingBoundDatagram
  override type ConnectedDatagram = EventPollingConnectedDatagram

  override def bindDatagram(
      local: IpSocketAddress,
      options: SocketOptions,
      into: Slot[EventPollingBoundDatagram]
  ): EventPollingOp = {
    val kind = SocketOption.Kind.BoundDatagram
    Checks.checkOptions(options, supports, kind)
    EventPollingOpenDatagram(local, null, options, kind, into)((fd, ipv6, _, owner) =>
      new EventPollingBoundDatagram(fd, ipv6, owner)
    )
  }

  override def connectDatagram(
      local: IpSocketAddress | Null,
      remote: IpSocketAddress,
      options: SocketOptions,
      into: Slot[EventPollingConnectedDatagram]
  ): EventPollingOp = {
    val kind = SocketOption.Kind.ConnectedDatagram
    Checks.checkOptions(options, supports, kind)
    val (from, to) = IpAddresses.pair(local, remote)
    EventPollingOpenDatagram(from, to, options, kind, into)((fd, ipv6, _, owner) =>
      new EventPollingConnectedDatagram(fd, ipv6, owner)
    )
  }

  override def receive(
      handle: EventPollingBoundDatagram,
      buf: ByteBuffer,
      from: Slot[IpSocketAddress]
  ): EventPollingDatagramOp & Repeatable = EventPollingReceive(handle, buf, from)

  override def receive(handle: EventPollingConnectedDatagram, buf: ByteBuffer): EventPollingDatagramOp & Repeatable =
    EventPollingReceive[IpSocketAddress](handle, buf, null)

  override def send(handle: EventPollingBoundDatagram, buf: ByteBuffer, to: IpSocketAddress): EventPollingDatagramOp &
    Repeatable =
    EventPollingSend(handle, Array(buf), to)

  override def send(handle: EventPollingConnectedDatagram, buf: ByteBuffer): EventPollingDatagramOp & Repeatable =
    EventPollingSend(handle, Array(buf), null)

  override def send(
      handle: EventPollingBoundDatagram,
      bufs: Array[ByteBuffer],
      to: IpSocketAddress
  ): EventPollingDatagramOp & Repeatable = EventPollingSend(handle, bufs, to)

  override def send(handle: EventPollingConnectedDatagram, bufs: Array[ByteBuffer]): EventPollingDatagramOp &
    Repeatable =
    EventPollingSend(handle, bufs, null)

  @annotation.targetName("datagramLocalAddress")
  override def localAddress(handle: EventPollingBoundDatagram | EventPollingConnectedDatagram): IpSocketAddress =
    EventPollingSockets.addressOf(handle, peer = false).asInstanceOf[IpSocketAddress]

  @annotation.targetName("datagramPeerAddress")
  override def peerAddress(handle: EventPollingConnectedDatagram): IpSocketAddress =
    EventPollingSockets.addressOf(handle, peer = true).asInstanceOf[IpSocketAddress]
}

sealed trait EventPollingUnixDatagrams extends UnixDatagrams {
  self: EventPollingInterface & EventPollingHandles & EventPollingSocketOptions =>
  override type UnixBoundDatagram = EventPollingUnixBoundDatagram
  override type UnixConnectedDatagram = EventPollingUnixConnectedDatagram

  override def bindDatagram(
      local: UnixAddress,
      options: SocketOptions,
      into: Slot[EventPollingUnixBoundDatagram]
  ): EventPollingOp = {
    val kind = SocketOption.Kind.UnixBoundDatagram
    Checks.checkOptions(options, supports, kind)
    EventPollingOpenDatagram(Checks.unix(local), null, options, kind, into)((fd, _, bound, owner) =>
      new EventPollingUnixBoundDatagram(fd, bound, owner)
    )
  }

  override def connectDatagram(
      local: UnixAddress | Null,
      remote: UnixAddress,
      options: SocketOptions,
      into: Slot[EventPollingUnixConnectedDatagram]
  ): EventPollingOp = {
    val kind = SocketOption.Kind.UnixConnectedDatagram
    Checks.checkOptions(options, supports, kind)
    val from = if local == null then null else Checks.unix(local)
    EventPollingOpenDatagram(from, Checks.unix(remote), options, kind, into)((fd, _, bound, owner) =>
      new EventPollingUnixConnectedDatagram(fd, bound, owner)
    )
  }

  @annotation.targetName("receiveUnix")
  override def receive(
      handle: EventPollingUnixBoundDatagram,
      buf: ByteBuffer,
      from: Slot[UnixAddress]
  ): EventPollingDatagramOp & Repeatable = EventPollingReceive(handle, buf, from)

  @annotation.targetName("receiveUnixConnected")
  override def receive(handle: EventPollingUnixConnectedDatagram, buf: ByteBuffer): EventPollingDatagramOp &
    Repeatable =
    EventPollingReceive[UnixAddress](handle, buf, null)

  override def send(handle: EventPollingUnixBoundDatagram, buf: ByteBuffer, to: UnixAddress): EventPollingDatagramOp &
    Repeatable =
    EventPollingSend(handle, Array(buf), Checks.unix(to))

  @annotation.targetName("sendUnixConnected")
  override def send(handle: EventPollingUnixConnectedDatagram, buf: ByteBuffer): EventPollingDatagramOp & Repeatable =
    EventPollingSend(handle, Array(buf), null)

  override def send(
      handle: EventPollingUnixBoundDatagram,
      bufs: Array[ByteBuffer],
      to: UnixAddress
  ): EventPollingDatagramOp & Repeatable = EventPollingSend(handle, bufs, Checks.unix(to))

  @annotation.targetName("sendUnixConnectedMany")
  override def send(handle: EventPollingUnixConnectedDatagram, bufs: Array[ByteBuffer]): EventPollingDatagramOp &
    Repeatable =
    EventPollingSend(handle, bufs, null)

  @annotation.targetName("unixDatagramLocalAddress")
  override def localAddress(handle: EventPollingUnixBoundDatagram | EventPollingUnixConnectedDatagram): UnixAddress =
    EventPollingSockets.addressOf(handle, peer = false).asInstanceOf[UnixAddress]

  @annotation.targetName("unixDatagramPeerAddress")
  override def peerAddress(handle: EventPollingUnixConnectedDatagram): UnixAddress =
    EventPollingSockets.addressOf(handle, peer = true).asInstanceOf[UnixAddress]
}

sealed trait EventPollingBlockingOps extends Blocking { self: EventPollingInterface =>
  override def blocking(body: () => Unit): EventPollingOp = EventPollingBlocking(body)
  override def blocking[A <: AnyRef](body: () => A, into: Slot[A]): EventPollingOp =
    EventPollingBlockingValue(body, into)
}

sealed trait EventPollingRegularFiles extends RegularFiles {
  self: EventPollingInterface & EventPollingHandles & EventPollingByteStreams =>
  override type File = EventPollingFile

  override def open(path: String, write: Boolean, into: Slot[EventPollingFile]): EventPollingOp =
    EventPollingOpenFile(path, write, into)

  override def read(handle: EventPollingFile, buf: ByteBuffer, offset: Long): EventPollingOp & Repeatable = {
    Checks.checkOffset(offset)
    EventPollingFileRead(handle, Array(buf), offset)
  }

  override def write(handle: EventPollingFile, buf: ByteBuffer, offset: Long): EventPollingOp & Repeatable = {
    Checks.checkOffset(offset)
    EventPollingFileWrite(handle, Array(buf), offset)
  }

  override def sync(handle: EventPollingFile): EventPollingOp & Repeatable = EventPollingFileSync(handle)
}

sealed trait EventPollingResolving extends Resolving { self: EventPollingInterface =>
  override def resolve(host: String, into: Slot[List[IpAddress]]): EventPollingOp = EventPollingResolve(host, into)
}

sealed trait EventPollingFifos extends Fifos {
  self: EventPollingInterface & EventPollingHandles & EventPollingByteStreams =>
  override def openFifoForReading(path: String, into: Slot[EventPollingReadable]): EventPollingOp =
    EventPollingOpenFifo[EventPollingReadable](path, write = false, into)(new FifoRead(_, _))

  override def openFifoForWriting(path: String, into: Slot[EventPollingWritable]): EventPollingOp =
    EventPollingOpenFifo[EventPollingWritable](path, write = true, into)(new FifoWrite(_, _))
}

object EventPollingOps
    extends EventPollingInterface,
      EventPollingHandles,
      EventPollingByteStreams,
      EventPollingPipeOps,
      EventPollingSocketOptions,
      EventPollingStreamSockets,
      EventPollingIpStreams,
      EventPollingUnixStreams,
      EventPollingIpDatagrams,
      EventPollingUnixDatagrams,
      EventPollingBlockingOps,
      EventPollingRegularFiles,
      EventPollingResolving,
      EventPollingFifos {}
