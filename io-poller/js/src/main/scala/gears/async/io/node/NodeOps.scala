package gears.async.io
package node

import gears.async.io.node.host.Addresses
import gears.async.io.node.host.Options

import java.nio.ByteBuffer
import scala.annotation.targetName
import scala.concurrent.duration.FiniteDuration

import SocketOption.Kind

/** What every backend's ops have: the type they are built as, and timers. */
sealed trait NodeInterface extends Interface {
  override type Op = NodeOp

  override def timer(duration: FiniteDuration): NodeTimerOp & Repeatable = {
    if duration.length < 0 then throw new IllegalArgumentException(s"A timer's time is not negative: $duration")
    new NodeMakeTimer(duration.toNanos)
  }

  override def deadlineTimer(nanoTime: Long): NodeDeadlineOp & Repeatable = new NodeMakeDeadline(nanoTime)
}

sealed trait NodeHandles extends Handles { self: NodeInterface =>
  override type Handle = NodeHandle

  override def close(handle: NodeHandle): NodeCloseOp = new NodeCloseHandle(handle)
}

/** Streams, whatever they are over: a handle read through a source of Node's waits for it, and a file's read is a call,
  * at the position its handle carries.
  */
sealed trait NodeByteStreams extends ByteStreams { self: NodeInterface & NodeHandles =>
  override type Readable = NodeReadable
  override type Writable = NodeWritable

  protected final def fillable(buf: ByteBuffer): ByteBuffer = {
    if buf.isReadOnly() then throw new IllegalArgumentException(s"A read-only buffer cannot be read into: $buf")
    buf
  }

  protected final def several(bufs: Array[ByteBuffer]): Array[ByteBuffer] = {
    if bufs.isEmpty then throw new IllegalArgumentException("A vectored op takes at least one buffer")
    bufs
  }

  private def reading(handle: NodeReadable, into: ReadInto): NodeReadableOp & Repeatable = handle match {
    case file: FileHandle       => new NodeFileRead(file, into, FileOps.AtStreamPosition)
    case stream: StreamReadable => new NodeRead(stream, into)
  }

  private def writing(handle: NodeWritable, bufs: Array[ByteBuffer]): NodeWritableOp & Repeatable = handle match {
    case file: FileHandle       => new NodeFileWrite(file, bufs, FileOps.AtStreamPosition)
    case stream: StreamWritable => new NodeWrite(stream, bufs)
  }

  override def read(handle: NodeReadable, buf: ByteBuffer): NodeReadableOp & Repeatable =
    reading(handle, new IntoBuffers(Array(fillable(buf))))

  override def write(handle: NodeWritable, buf: ByteBuffer): NodeWritableOp & Repeatable =
    writing(handle, Array(buf))

  override def read(handle: NodeReadable, bufs: Array[ByteBuffer]): NodeReadableOp & Repeatable = {
    several(bufs).foreach(fillable)
    reading(handle, new IntoBuffers(bufs))
  }

  override def write(handle: NodeWritable, bufs: Array[ByteBuffer]): NodeWritableOp & Repeatable =
    writing(handle, several(bufs))

  override def read(handle: NodeReadable, pool: BufferPool, into: Slot[Received]): NodeReadableOp & Repeatable =
    pool match {
      case ours: NodePool => reading(handle, new IntoPool(ours, into))
      case other          => throw new IllegalArgumentException(s"Not a pool of this backend's: $other")
    }
}

sealed trait NodeStreamSockets extends StreamSockets { self: NodeInterface & NodeHandles & NodeByteStreams =>
  override type Connection = ConnectionHandle
  override type Listener = ListenerHandle

  override def accept(handle: ListenerHandle, into: Slot[ConnectionHandle]): NodeAcceptOp & Repeatable =
    new NodeAccept(handle, 1, into, accepted => into.set(accepted(0)))

  override def acceptAll(
      handle: ListenerHandle,
      into: Slot[Array[ConnectionHandle]],
      max: Int
  ): NodeAcceptOp & Repeatable = {
    if max <= 0 then throw new IllegalArgumentException(s"acceptAll takes at least one connection, not $max")
    new NodeAccept(handle, max, into, accepted => into.set(accepted))
  }

  override def shutdownOutput(handle: ConnectionHandle): NodeShutdownOp = new NodeShutdownOutput(handle)

  override def localAddress(handle: ListenerHandle | ConnectionHandle): IpSocketAddress | UnixAddress = {
    handle.checkOpen()
    handle match {
      case listener: ListenerHandle     => listener.local
      case connection: ConnectionHandle => connection.local
    }
  }

  override def peerAddress(handle: ConnectionHandle): IpSocketAddress | UnixAddress = {
    handle.checkOpen()
    handle.peer
  }
}

/** The socket options, as Node has them (see `Options`). */
sealed trait NodeSocketOptions extends SocketOptionOps { self: NodeInterface & NodeHandles =>
  override def supports(option: SocketOption[?], kind: Kind): Boolean = Options.supports(option, kind)

  override def canChange(option: SocketOption[?], kind: Kind): Boolean = Options.canChange(option, kind)

  /** The kind of socket `handle` names. */
  private def kindOf(handle: NodeHandle): Kind = handle match {
    case connection: ConnectionHandle => connection.kind
    case listener: ListenerHandle     => listener.kind
    case datagram: DatagramHandle     => datagram.kind
    case other                        => throw new IllegalArgumentException(s"$other is not a socket")
  }

  override def option[V](handle: NodeHandle, option: SocketOption[V]): V = {
    val kind = kindOf(handle)
    if !Options.onOpen(option, kind) then
      throw new IllegalArgumentException(s"$option is not read on a ${kind.description} by this backend")
    handle.checkOpen()
    (handle match {
      case connection: ConnectionHandle => connection.option(option)
      case datagram: DatagramHandle     => datagram.option(option)
      case other                        => throw new IllegalArgumentException(s"$option is not read on $other")
    }).asInstanceOf[V]
  }

  override def setOption[V](handle: NodeHandle, option: SocketOption[V], value: V): Unit = {
    val kind = kindOf(handle)
    if !Options.canChange(option, kind) then
      throw new IllegalArgumentException(s"$option is not changed on an open ${kind.description} by this backend")
    option.check(value)
    handle.checkOpen()
    handle match {
      case connection: ConnectionHandle => connection.setOption(option, value)
      case datagram: DatagramHandle     => datagram.setOption(option, value)
      case other                        => throw new IllegalArgumentException(s"$option is not changed on $other")
    }
  }
}

sealed trait NodeIpStreams extends IpStreams {
  self: NodeInterface & NodeHandles & NodeByteStreams & NodeStreamSockets & NodeSocketOptions =>

  override def connect(address: IpSocketAddress, options: SocketOptions, into: Slot[ConnectionHandle]): NodeOpenOp = {
    Options.check(options, Kind.Connection)
    new NodeConnect(address, options, into)
  }

  override def listen(address: IpSocketAddress, options: SocketOptions, into: Slot[ListenerHandle]): NodeOpenOp = {
    Options.check(options, Kind.Listener)
    new NodeListen(address, options, into)
  }
}

sealed trait NodeUnixStreams extends UnixStreams {
  self: NodeInterface & NodeHandles & NodeByteStreams & NodeStreamSockets & NodeSocketOptions =>

  override def connect(address: UnixAddress, options: SocketOptions, into: Slot[ConnectionHandle]): NodeOpenOp = {
    val to = Addresses.checked(address)
    Options.check(options, Kind.UnixConnection)
    new NodeConnect(to, options, into)
  }

  override def listen(address: UnixAddress, options: SocketOptions, into: Slot[ListenerHandle]): NodeOpenOp = {
    val on = Addresses.checked(address)
    Options.check(options, Kind.UnixListener)
    new NodeListen(on, options, into)
  }
}

sealed trait NodeIpDatagrams extends IpDatagrams {
  self: NodeInterface & NodeHandles & NodeByteStreams & NodeSocketOptions =>
  override type BoundDatagram = BoundDatagramHandle
  override type ConnectedDatagram = ConnectedDatagramHandle

  override def bindDatagram(
      local: IpSocketAddress,
      options: SocketOptions,
      into: Slot[BoundDatagramHandle]
  ): NodeOpenOp = {
    Options.check(options, Kind.BoundDatagram)
    new NodeOpenDatagram(local, null, options, into)
  }

  override def connectDatagram(
      local: IpSocketAddress | Null,
      remote: IpSocketAddress,
      options: SocketOptions,
      into: Slot[ConnectedDatagramHandle]
  ): NodeOpenOp = {
    val (from, to) = Addresses.pair(local, remote)
    Options.check(options, Kind.ConnectedDatagram)
    new NodeOpenDatagram(from, to, options, into)
  }

  override def receive(
      handle: BoundDatagramHandle,
      buf: ByteBuffer,
      from: Slot[IpSocketAddress]
  ): NodeReceiveOp & Repeatable = new NodeReceive(handle, fillable(buf), from)

  override def receive(handle: ConnectedDatagramHandle, buf: ByteBuffer): NodeReceiveOp & Repeatable =
    new NodeReceive(handle, fillable(buf), null)

  override def send(handle: BoundDatagramHandle, buf: ByteBuffer, to: IpSocketAddress): NodeSendOp & Repeatable =
    new NodeSend(handle, Array(buf), to)

  override def send(handle: ConnectedDatagramHandle, buf: ByteBuffer): NodeSendOp & Repeatable =
    new NodeSend(handle, Array(buf), null)

  override def send(
      handle: BoundDatagramHandle,
      bufs: Array[ByteBuffer],
      to: IpSocketAddress
  ): NodeSendOp & Repeatable = new NodeSend(handle, several(bufs), to)

  override def send(handle: ConnectedDatagramHandle, bufs: Array[ByteBuffer]): NodeSendOp & Repeatable =
    new NodeSend(handle, several(bufs), null)

  @targetName("datagramLocalAddress")
  override def localAddress(handle: BoundDatagramHandle | ConnectedDatagramHandle): IpSocketAddress = {
    handle.checkOpen()
    handle.local
  }

  @targetName("datagramPeerAddress")
  override def peerAddress(handle: ConnectedDatagramHandle): IpSocketAddress = {
    handle.checkOpen()
    handle.peer
  }
}

sealed trait NodeRegularFiles extends RegularFiles { self: NodeInterface & NodeHandles & NodeByteStreams =>
  override type File = FileHandle

  private def offset(at: Long): Long = {
    if at < 0 then throw new IllegalArgumentException(s"A file offset must not be negative: $at")
    at
  }

  override def open(path: String, write: Boolean, into: Slot[FileHandle]): NodeOpenOp = {
    Addresses.checkPath(path)
    new NodeOpenFile(path, write, into)
  }

  override def read(handle: FileHandle, buf: ByteBuffer, at: Long): NodeReadableOp & Repeatable =
    new NodeFileRead(handle, new IntoBuffers(Array(fillable(buf))), offset(at))

  override def write(handle: FileHandle, buf: ByteBuffer, at: Long): NodeWritableOp & Repeatable =
    new NodeFileWrite(handle, Array(buf), offset(at))

  override def sync(handle: FileHandle): NodeSyncOp & Repeatable = new NodeSync(handle)
}

sealed trait NodeFifos extends Fifos { self: NodeInterface & NodeHandles & NodeByteStreams =>
  override def openFifoForReading(path: String, into: Slot[NodeReadable]): NodeOpenOp = {
    Addresses.checkPath(path)
    new NodeOpenFifoForReading(path, into)
  }

  override def openFifoForWriting(path: String, into: Slot[NodeWritable]): NodeOpenOp = {
    Addresses.checkPath(path)
    new NodeOpenFifoForWriting(path, into)
  }
}

sealed trait NodeResolving extends Resolving { self: NodeInterface =>
  override def resolve(host: String, into: Slot[List[IpAddress]]): NodeResolveOp = {
    if host.isEmpty then throw new IllegalArgumentException("An empty host name names no host")
    Addresses.checkPath(host)
    new NodeResolve(host, into)
  }
}

/** The Node.js backend's ops, each checked as it is built: one trait for each capability the backend has. */
object NodeOps
    extends NodeInterface,
      NodeHandles,
      NodeByteStreams,
      NodeStreamSockets,
      NodeSocketOptions,
      NodeIpStreams,
      NodeUnixStreams,
      NodeIpDatagrams,
      NodeRegularFiles,
      NodeFifos,
      NodeResolving
