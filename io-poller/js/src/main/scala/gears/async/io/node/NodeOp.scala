package gears.async.io
package node

import gears.async.io.node.base.BaseOp
import gears.async.io.node.base.NodeSource
import gears.async.io.shareable.AbstractCloseOp
import gears.async.io.shareable.AbstractMakeDeadlineOp
import gears.async.io.shareable.AbstractMakeTimerOp
import gears.async.io.shareable.BackendOp
import gears.async.io.shareable.HandleOwner
import gears.async.io.shareable.ImmediateOp

import java.io.IOException
import java.nio.ByteBuffer
import scala.concurrent.duration.DurationLong
import scala.scalajs.js

/** An op of the Node.js backend: one of the classes below, each the whole of an op as a program holds it, built on what
  * does its kind of work: the shared layer's ops, and `base` for what is Node's own.
  */
sealed trait NodeOp extends BaseOp

sealed trait NodeTimerOp extends NodeOp with BackendOp.Timed[NodeSource.type]
sealed trait NodeDeadlineOp extends NodeOp with BackendOp.Timed[NodeSource.type] with Deadline
sealed trait NodeReadableOp extends NodeOp
sealed trait NodeWritableOp extends NodeOp
sealed trait NodeCloseOp extends NodeOp with BackendOp.Close[NodeSource.type]
sealed trait NodeOpenOp extends NodeOp
sealed trait NodeAcceptOp extends NodeOp
sealed trait NodeShutdownOp extends NodeOp with BackendOp.Immediate[NodeSource.type]
sealed trait NodeReceiveOp extends NodeOp
sealed trait NodeSendOp extends NodeOp
sealed trait NodeSyncOp extends NodeOp
sealed trait NodeResolveOp extends NodeOp

private[node] final class NodeMakeTimer(nanos: Long) extends AbstractMakeTimerOp(nanos.nanos) with NodeTimerOp

private[node] final class NodeMakeDeadline(initial: Long) extends AbstractMakeDeadlineOp(initial) with NodeDeadlineOp

private[node] final class NodeRead(handle: StreamReadable, into: ReadInto)
    extends AbstractStreamReadOp(handle, into)
    with NodeReadableOp

private[node] final class NodeWrite(handle: StreamWritable, bufs: Array[ByteBuffer])
    extends AbstractStreamWriteOp(handle, bufs)
    with NodeWritableOp

private[node] final class NodeFileRead(handle: FileHandle, into: ReadInto, offset: Long)
    extends AbstractFileReadOp(handle, into, offset)
    with NodeReadableOp

private[node] final class NodeFileWrite(handle: FileHandle, bufs: Array[ByteBuffer], offset: Long)
    extends AbstractFileWriteOp(handle, bufs, offset)
    with NodeWritableOp

private[node] final class NodeSync(handle: FileHandle) extends AbstractSyncOp(handle) with NodeSyncOp

private[node] final class NodeCloseHandle(handle: NodeHandle) extends AbstractCloseOp(handle) with NodeCloseOp

private[node] final class NodeConnect(
    address: IpSocketAddress | UnixAddress,
    options: SocketOptions,
    into: Slot[ConnectionHandle]
) extends AbstractConnectOp(address, options, into)
    with NodeOpenOp

private[node] final class NodeListen(
    address: IpSocketAddress | UnixAddress,
    options: SocketOptions,
    into: Slot[ListenerHandle]
) extends AbstractListenOp(address, options, into)
    with NodeOpenOp

private[node] final class NodeAccept(
    listener: ListenerHandle,
    max: Int,
    slot: Slot[?],
    deliver: Array[ConnectionHandle] => Unit
) extends AbstractAcceptOp(listener, max, slot, deliver)
    with NodeAcceptOp

/** Shuts a connection down for writing: its end is queued behind what was written, at once. */
private[node] final class NodeShutdownOutput(connection: ConnectionHandle) extends ImmediateOp with NodeShutdownOp {
  def perform(owner: HandleOwner): Unit = {
    owner.check(connection)
    connection.checkOpen()
    connection.shutdownOutput()
  }

}

private[node] final class NodeOpenDatagram[H <: DatagramHandle](
    local: IpSocketAddress | Null,
    remote: IpSocketAddress | Null,
    options: SocketOptions,
    into: Slot[H]
) extends AbstractOpenDatagramOp[H](local, remote, options, into)
    with NodeOpenOp

private[node] final class NodeReceive(socket: DatagramHandle, buf: ByteBuffer, from: Slot[IpSocketAddress] | Null)
    extends AbstractReceiveOp(socket, buf, from)
    with NodeReceiveOp

private[node] final class NodeSend(socket: DatagramHandle, bufs: Array[ByteBuffer], to: IpSocketAddress | Null)
    extends AbstractSendOp(socket, bufs, to)
    with NodeSendOp

/** Opens a regular file, refusing anything else: a FIFO, opened without blocking, is refused like the rest. */
private[node] final class NodeOpenFile(path: String, write: Boolean, into: Slot[FileHandle])
    extends AbstractOpenPathOp[FileHandle](path, write, into)
    with NodeOpenOp {
  protected def opened(fd: Int, stats: js.Dynamic): FileHandle = {
    if !stats.isFile().asInstanceOf[Boolean] then throw new IOException(s"$path is not a regular file")
    new FileHandle(runOwner, fd, path, write)
  }
}

/** Opens a FIFO for reading, without waiting for a writer, refusing anything that is not a FIFO. */
private[node] final class NodeOpenFifoForReading(path: String, into: Slot[NodeReadable])
    extends AbstractOpenPathOp[NodeReadable](path, write = false, into)
    with NodeOpenOp {
  protected def opened(fd: Int, stats: js.Dynamic): NodeReadable = {
    if !stats.isFIFO().asInstanceOf[Boolean] then throw new IOException(s"$path is not a FIFO")
    Fifos.readEnd(fd, runOwner)
  }
}

/** Opens a FIFO for writing, without waiting for a reader: it fails while the FIFO has none, as the system fails it.
  */
private[node] final class NodeOpenFifoForWriting(path: String, into: Slot[NodeWritable])
    extends AbstractOpenPathOp[NodeWritable](path, write = true, into)
    with NodeOpenOp {
  protected def opened(fd: Int, stats: js.Dynamic): NodeWritable = {
    if !stats.isFIFO().asInstanceOf[Boolean] then throw new IOException(s"$path is not a FIFO")
    Fifos.writeEnd(fd, runOwner)
  }
}

private[node] final class NodeResolve(name: String, into: Slot[List[IpAddress]])
    extends AbstractResolveOp(name, into)
    with NodeResolveOp
