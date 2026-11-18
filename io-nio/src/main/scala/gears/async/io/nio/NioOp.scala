package gears.async.io.nio

import gears.async.Async
import gears.async.io.Deadline
import gears.async.io.IpAddress
import gears.async.io.IpSocketAddress
import gears.async.io.Received
import gears.async.io.Slot
import gears.async.io.SocketOption
import gears.async.io.SocketOptions
import gears.async.io.shareable.AbstractAcceptAllOp
import gears.async.io.shareable.AbstractAcceptOp
import gears.async.io.shareable.AbstractBlockingOp
import gears.async.io.shareable.AbstractBlockingValueOp
import gears.async.io.shareable.AbstractCloseOp
import gears.async.io.shareable.AbstractConnectOp
import gears.async.io.shareable.AbstractFilePooledReadOp
import gears.async.io.shareable.AbstractFileReadOp
import gears.async.io.shareable.AbstractFileSyncOp
import gears.async.io.shareable.AbstractFileWriteOp
import gears.async.io.shareable.AbstractGatherWriteOp
import gears.async.io.shareable.AbstractHandleCallOp
import gears.async.io.shareable.AbstractMakeDeadlineOp
import gears.async.io.shareable.AbstractMakePipeOp
import gears.async.io.shareable.AbstractMakeTimerOp
import gears.async.io.shareable.AbstractOpenFileOp
import gears.async.io.shareable.AbstractOpenOp
import gears.async.io.shareable.AbstractPooledReadOp
import gears.async.io.shareable.AbstractReadOp
import gears.async.io.shareable.AbstractReceiveOp
import gears.async.io.shareable.AbstractResolveOp
import gears.async.io.shareable.AbstractScatterReadOp
import gears.async.io.shareable.AbstractSendOp
import gears.async.io.shareable.AbstractWriteOp
import gears.async.io.shareable.HandleOwner
import gears.async.io.shareable.LendingPool
import gears.async.io.shareable.Moved
import gears.async.io.shareable.TimerHeapOp
import gears.async.io.shareable.Worker

import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.Pipe
import scala.concurrent.duration.FiniteDuration

sealed trait NioOp extends TimerHeapOp:

  /** A worker takes any op of this backend's among its timers (see `NioPoller.Timer`). */
  type Source = NioPoller
  final def _holdTimer(worker: NioPoller): Unit = worker.addTimer(this)

/** An op that waits for its handle to be ready, on the worker that listens for it (see `ReadyWaiting`). */
sealed trait NioReadinessOp extends NioOp:
  def performWhenReady(self: NioPoller)(using Async): Unit

/** An op done as it is performed, with nothing to wait for (see `ImmediateOp`). */
sealed trait NioImmediateOp extends NioOp:
  def perform(owner: HandleOwner): Unit

/** An op whose work is done off the workers' threads (see `WorkOffThread`). */
sealed trait NioOffThreadOp extends NioOp:
  def performOffThread(worker: Worker & NioPoller)(using Async): Unit
  def performOnCaller(worker: Worker & NioPoller)(using Async): Unit

sealed trait NioConnectOp extends NioOp:
  def performConnect(self: NioPoller)(using Async): Unit

sealed trait NioTimerOp extends NioOp

sealed trait NioDeadlineOp extends NioOp with Deadline

sealed trait NioCloseOp extends NioOp:
  def performClose(self: NioPoller)(using Async): Unit

/** A stream read's calls on the JDK's channels: mixed into the op. A channel that has nothing to read reads nothing,
  * which is a read that would block, and says -1 at the end of its stream.
  */
trait NioReadOps[H <: NioReadable]:
  final def _readHandle(handle: H, buf: ByteBuffer): Moved = NioReadOps.read(handle.reads.read(buf).toLong)
  final def _readvHandle(handle: H, bufs: Array[ByteBuffer]): Moved = NioReadOps.read(handle.reads.read(bufs))

object NioReadOps:
  private[nio] def read(count: Long): Moved =
    if count > 0 then Moved.of(count) else if count == 0 then Moved.Blocked else Moved.EndOfStream

/** A stream write's calls, as `NioReadOps` has a read's. A channel with no room writes nothing, which is a write that
  * would block.
  */
trait NioWriteOps[H <: NioWritable]:
  final def _writeHandle(handle: H, buf: ByteBuffer): Moved = NioWriteOps.written(handle.writes.write(buf).toLong)
  final def _writevHandle(handle: H, bufs: Array[ByteBuffer]): Moved = NioWriteOps.written(handle.writes.write(bufs))

object NioWriteOps:
  private[nio] def written(count: Long): Moved = if count == 0 then Moved.Blocked else Moved.of(count)

private[nio] final class NioRead(handle: NioChannelReadable, buf: ByteBuffer)
    extends AbstractReadOp(handle, buf),
      NioReadinessOp,
      NioReadOps[NioChannelReadable]

private[nio] final class NioWrite(handle: NioChannelWritable, buf: ByteBuffer)
    extends AbstractWriteOp(handle, buf),
      NioReadinessOp,
      NioWriteOps[NioChannelWritable]

private[nio] final class NioScatterRead(handle: NioChannelReadable, bufs: Array[ByteBuffer])
    extends AbstractScatterReadOp(handle, bufs),
      NioReadinessOp,
      NioReadOps[NioChannelReadable]

private[nio] final class NioPooledRead(handle: NioChannelReadable, pool: LendingPool, into: Slot[Received])
    extends AbstractPooledReadOp(handle, pool, into),
      NioReadinessOp,
      NioReadOps[NioChannelReadable]

private[nio] final class NioGatherWrite(handle: NioChannelWritable, bufs: Array[ByteBuffer])
    extends AbstractGatherWriteOp(handle, bufs),
      NioReadinessOp,
      NioWriteOps[NioChannelWritable]

private[nio] final class NioCloseHandle(handle: NioHandle) extends AbstractCloseOp(handle) with NioCloseOp

private[nio] final class NioMakePipe(readSlot: Slot[NioReadable], writeSlot: Slot[NioWritable])
    extends AbstractMakePipeOp(readSlot, writeSlot),
      NioImmediateOp:
  def _pipe(owner: HandleOwner): (NioReadable, NioWritable) =
    val pipe = Pipe.open()
    try (new PipeRead(pipe.source(), owner), new PipeWrite(pipe.sink(), owner))
    catch
      case failure: Throwable =>
        try pipe.source().close()
        finally pipe.sink().close()
        throw failure

private[nio] final class NioMakeTimer(duration: FiniteDuration) extends AbstractMakeTimerOp(duration) with NioTimerOp

private[nio] final class NioMakeDeadline(initial: Long) extends AbstractMakeDeadlineOp(initial) with NioDeadlineOp

// ---- Stream sockets

private[nio] final class NioConnect(address: SocketAddress, options: SocketOptions, into: Slot[NioConnection])
    extends AbstractConnectOp(into),
      NioConnectOp:
  def _startConnect(owner: HandleOwner): NioConnection = NioSockets.startConnect(address, options, owner)
  def _finishConnect(socket: NioConnection): Boolean = NioSockets.finishConnect(socket)

private[nio] final class NioListen(address: SocketAddress, options: SocketOptions, into: Slot[NioListener])
    extends AbstractOpenOp(into),
      NioImmediateOp:
  def _open(owner: HandleOwner): NioListener = NioSockets.listen(address, options, owner)

private[nio] final class NioAccept(listener: NioListener, into: Slot[NioConnection])
    extends AbstractAcceptOp(listener, into),
      NioReadinessOp:
  def _accept(listener: NioListener): NioConnection | Null = NioSockets.accept(listener)

private[nio] final class NioAcceptAll(listener: NioListener, into: Slot[Array[NioConnection]], max: Int)
    extends AbstractAcceptAllOp(listener, into, max),
      NioReadinessOp:
  def _accept(listener: NioListener): NioConnection | Null = NioSockets.accept(listener)

private[nio] final class NioShutdownOutput(connection: NioConnection)
    extends AbstractHandleCallOp(connection),
      NioImmediateOp:
  def _call(connection: NioConnection): Unit = connection.socket.shutdownOutput(): Unit

// ---- Datagram sockets

/** Opens a datagram socket of kind `kind`, bound to `local` and connected to `remote` where given, as the handle `make`
  * makes of it.
  */
private[nio] final class NioOpenDatagram[D <: NioDatagramSocket](
    local: IpSocketAddress | Null,
    remote: IpSocketAddress | Null,
    options: SocketOptions,
    kind: SocketOption.Kind,
    into: Slot[D]
)(make: (DatagramChannel, HandleOwner) => D)
    extends AbstractOpenOp(into),
      NioImmediateOp:
  def _open(owner: HandleOwner): D = NioDatagrams.open(local, remote, options, kind, owner)(make)

private[nio] final class NioReceive[A <: AnyRef](socket: NioDatagramSocket, buf: ByteBuffer, from: Slot[A] | Null)
    extends AbstractReceiveOp(socket, buf, from),
      NioReadinessOp:
  def _receive(socket: NioDatagramSocket, buf: ByteBuffer, from: Slot[A] | Null): Boolean =
    NioDatagrams.receive(socket.datagrams, buf, from)

/** Sends to `to`, or to the socket's peer if it is null, which a connected socket's send has it. */
private[nio] final class NioSend(socket: NioDatagramSocket, bufs: Array[ByteBuffer], to: IpSocketAddress | Null)
    extends AbstractSendOp(socket, bufs, connected = to == null),
      NioReadinessOp:
  def _send(socket: NioDatagramSocket, bufs: Array[ByteBuffer]): Boolean =
    NioDatagrams.send(socket.datagrams, bufs, to)

// ---- Blocking code

private[nio] final class NioBlocking(body: () => Unit) extends AbstractBlockingOp(body), NioOffThreadOp

private[nio] final class NioBlockingValue[A <: AnyRef](body: () => A, into: Slot[A])
    extends AbstractBlockingValueOp(body, into),
      NioOffThreadOp

// ---- Regular files: `start` is the offset a positional op begins at, or negative for the file's stream position

private[nio] final class NioOpenFile(path: String, write: Boolean, into: Slot[NioFile])
    extends AbstractOpenFileOp(path, write, into),
      NioOffThreadOp:
  def _openFile(path: String, write: Boolean, owner: HandleOwner): NioFile = NioFiles.open(path, write, owner)

private[nio] final class NioFileRead(file: NioFile, bufs: Array[ByteBuffer], start: Long)
    extends AbstractFileReadOp(file, bufs, start),
      NioOffThreadOp:
  def _readAt(file: NioFile, buf: ByteBuffer, offset: Long): Int = NioFiles.readAt(file, buf, offset)

private[nio] final class NioFileWrite(file: NioFile, bufs: Array[ByteBuffer], start: Long)
    extends AbstractFileWriteOp(file, bufs, start),
      NioOffThreadOp:
  def _writeAt(file: NioFile, buf: ByteBuffer, offset: Long): Int = NioFiles.writeAt(file, buf, offset)

private[nio] final class NioFilePooledRead(file: NioFile, pool: LendingPool, into: Slot[Received])
    extends AbstractFilePooledReadOp(file, pool, into),
      NioOffThreadOp:
  def _readAt(file: NioFile, buf: ByteBuffer, offset: Long): Int = NioFiles.readAt(file, buf, offset)

private[nio] final class NioFileSync(file: NioFile) extends AbstractFileSyncOp(file), NioOffThreadOp:
  def _sync(file: NioFile): Unit = NioFiles.sync(file)

// ---- Name resolution

/** The JDK's lookup blocks, so it runs off the workers. An unknown host is the JDK's `UnknownHostException`, which is
  * an `IOException`.
  */
private[nio] final class NioResolve(host: String, into: Slot[List[IpAddress]])
    extends AbstractResolveOp(host, into),
      NioOffThreadOp:
  def _lookup(host: String): List[IpAddress] =
    java.net.InetAddress.getAllByName(host).toList.map(NioAddresses.ip).distinct
