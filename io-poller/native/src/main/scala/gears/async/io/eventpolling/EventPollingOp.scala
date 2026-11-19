package gears.async.io.eventpolling

import gears.async.Async
import gears.async.io.Deadline
import gears.async.io.IpAddress
import gears.async.io.Received
import gears.async.io.Slot
import gears.async.io.SocketOption
import gears.async.io.SocketOptions
import gears.async.io.posix.Descriptors
import gears.async.io.posix.PosixDatagrams
import gears.async.io.posix.PosixFiles
import gears.async.io.posix.PosixReadOps
import gears.async.io.posix.PosixResolver
import gears.async.io.posix.PosixSockets
import gears.async.io.posix.PosixWriteOps
import gears.async.io.posix.SocketAddress
import gears.async.io.posix.UnixBinding
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
import gears.async.io.shareable.BackendOp
import gears.async.io.shareable.Worker

import java.nio.ByteBuffer
import scala.concurrent.duration.FiniteDuration

/** An op of this backend's, of one of the kinds a backend has (see `BackendOp`). */
sealed trait EventPollingOp extends BackendOp[EventPoller]:

  /** A worker takes any op of this backend's among its timers (see `EventPoller.Timer`). */
  final def _holdTimer(worker: EventPoller): Unit = worker.addTimer(this)

sealed trait EventPollingReadinessOp extends EventPollingOp, BackendOp.Readiness[EventPoller]

sealed trait EventPollingImmediateOp extends EventPollingOp, BackendOp.Immediate[EventPoller]

sealed trait EventPollingReadableOp extends EventPollingReadinessOp

sealed trait EventPollingWritableOp extends EventPollingReadinessOp

sealed trait EventPollingAcceptOp extends EventPollingReadinessOp

sealed trait EventPollingOffThreadOp extends EventPollingOp, BackendOp.OffThread[EventPoller]

sealed trait EventPollingDatagramOp extends EventPollingReadinessOp

sealed trait EventPollingConnectOp extends EventPollingOp, BackendOp.Connect[EventPoller]

sealed trait EventPollingTimerOp extends EventPollingOp, BackendOp.Timed[EventPoller]

sealed trait EventPollingPipeOp extends EventPollingImmediateOp

sealed trait EventPollingDeadlineOp extends EventPollingOp, BackendOp.Timed[EventPoller], Deadline

sealed trait EventPollingCloseOp extends EventPollingOp, BackendOp.Close[EventPoller]

private[eventpolling] final class EventPollingRead(handle: EventPollingWaitingReadable, buf: ByteBuffer)
    extends AbstractReadOp(handle, buf),
      EventPollingReadableOp,
      PosixReadOps[EventPollingWaitingReadable]

private[eventpolling] final class EventPollingWrite(handle: EventPollingWaitingWritable, buf: ByteBuffer)
    extends AbstractWriteOp(handle, buf),
      EventPollingWritableOp,
      PosixWriteOps[EventPollingWaitingWritable]

private[eventpolling] final class EventPollingScatterRead(handle: EventPollingWaitingReadable, bufs: Array[ByteBuffer])
    extends AbstractScatterReadOp(handle, bufs),
      EventPollingReadableOp,
      PosixReadOps[EventPollingWaitingReadable]

private[eventpolling] final class EventPollingPooledRead(handle: EventPollingWaitingReadable, pool: LendingPool, into: Slot[Received])
    extends AbstractPooledReadOp(handle, pool, into),
      EventPollingReadableOp,
      PosixReadOps[EventPollingWaitingReadable]

private[eventpolling] final class EventPollingGatherWrite(handle: EventPollingWaitingWritable, bufs: Array[ByteBuffer])
    extends AbstractGatherWriteOp(handle, bufs),
      EventPollingWritableOp,
      PosixWriteOps[EventPollingWaitingWritable]

private[eventpolling] final class EventPollingCloseHandle(handle: EventPollingHandle) extends AbstractCloseOp(handle) with EventPollingCloseOp

private[eventpolling] final class EventPollingMakePipe(readSlot: Slot[EventPollingReadable], writeSlot: Slot[EventPollingWritable])
    extends AbstractMakePipeOp(readSlot, writeSlot)
    with EventPollingPipeOpener
    with EventPollingPipeOp

private[eventpolling] final class EventPollingMakeTimer(duration: FiniteDuration)
    extends AbstractMakeTimerOp(duration)
    with EventPollingTimerOp

private[eventpolling] final class EventPollingMakeDeadline(initial: Long)
    extends AbstractMakeDeadlineOp(initial)
    with EventPollingDeadlineOp

// ---- Stream sockets

private[eventpolling] final class EventPollingConnect(address: SocketAddress, options: SocketOptions, into: Slot[EventPollingConnection])
    extends AbstractConnectOp(into),
      EventPollingConnectOp:
  def _startConnect(owner: HandleOwner): EventPollingConnection = EventPollingSockets.startConnect(address, options, owner)
  def _finishConnect(socket: EventPollingConnection): Boolean = EventPollingSockets.finishConnect(socket)

private[eventpolling] final class EventPollingListen(address: SocketAddress, options: SocketOptions, into: Slot[EventPollingListener])
    extends AbstractOpenOp(into),
      EventPollingImmediateOp:
  def _open(owner: HandleOwner): EventPollingListener = EventPollingSockets.listen(address, options, owner)

private[eventpolling] final class EventPollingAccept(listener: EventPollingListener, into: Slot[EventPollingConnection])
    extends AbstractAcceptOp(listener, into),
      EventPollingAcceptOp:
  def _accept(listener: EventPollingListener): EventPollingConnection | Null = EventPollingSockets.accept(listener)

private[eventpolling] final class EventPollingAcceptAll(listener: EventPollingListener, into: Slot[Array[EventPollingConnection]], max: Int)
    extends AbstractAcceptAllOp(listener, into, max),
      EventPollingAcceptOp:
  def _accept(listener: EventPollingListener): EventPollingConnection | Null = EventPollingSockets.accept(listener)

private[eventpolling] final class EventPollingShutdownOutput(connection: EventPollingConnection)
    extends AbstractHandleCallOp(connection),
      EventPollingImmediateOp:
  def _call(connection: EventPollingConnection): Unit = PosixSockets.shutdownOutput(connection.fd)

// ---- Datagram sockets

/** Opens a datagram socket of kind `kind`, bound to `local` and connected to `remote` where given, as the handle `make`
  * makes of it.
  */
private[eventpolling] final class EventPollingOpenDatagram[D <: EventPollingDatagramSocket](
    local: SocketAddress | Null,
    remote: SocketAddress | Null,
    options: SocketOptions,
    kind: SocketOption.Kind,
    into: Slot[D]
)(make: (Int, Boolean, UnixBinding | Null, HandleOwner) => D)
    extends AbstractOpenOp(into),
      EventPollingImmediateOp:
  def _open(owner: HandleOwner): D = EventPollingSockets.openDatagram(local, remote, options, kind, owner)(make)

private[eventpolling] final class EventPollingReceive[A <: AnyRef](
    socket: EventPollingDatagramSocket,
    buf: ByteBuffer,
    from: Slot[A] | Null
) extends AbstractReceiveOp(socket, buf, from),
      EventPollingDatagramOp:
  def _receive(socket: EventPollingDatagramSocket, buf: ByteBuffer, from: Slot[A] | Null): Boolean =
    PosixDatagrams.receive(socket.fd, buf, from)

/** Sends to `to`, or to the socket's peer if it is null, which a connected socket's send has it. */
private[eventpolling] final class EventPollingSend(socket: EventPollingDatagramSocket, bufs: Array[ByteBuffer], to: SocketAddress | Null)
    extends AbstractSendOp(socket, bufs, connected = to == null),
      EventPollingDatagramOp:
  def _send(socket: EventPollingDatagramSocket, bufs: Array[ByteBuffer]): Boolean =
    PosixDatagrams.send(socket.fd, socket.ipv6, bufs, to)

// ---- Blocking code

private[eventpolling] final class EventPollingBlocking(body: () => Unit) extends AbstractBlockingOp(body), EventPollingOffThreadOp

private[eventpolling] final class EventPollingBlockingValue[A <: AnyRef](body: () => A, into: Slot[A])
    extends AbstractBlockingValueOp(body, into),
      EventPollingOffThreadOp

// ---- Regular files: `start` is the offset a positional op begins at, or negative for the file's stream position

private[eventpolling] final class EventPollingOpenFile(path: String, write: Boolean, into: Slot[EventPollingFile])
    extends AbstractOpenFileOp(path, write, into),
      EventPollingOffThreadOp:
  def _openFile(path: String, write: Boolean, owner: HandleOwner): EventPollingFile =
    new EventPollingFile(Descriptors.openFile(path, write), owner)

private[eventpolling] final class EventPollingFileRead(file: EventPollingFile, bufs: Array[ByteBuffer], start: Long)
    extends AbstractFileReadOp(file, bufs, start),
      EventPollingOffThreadOp:
  def _readAt(file: EventPollingFile, buf: ByteBuffer, offset: Long): Int = PosixFiles.readAt(file.fd, buf, offset)

private[eventpolling] final class EventPollingFileWrite(file: EventPollingFile, bufs: Array[ByteBuffer], start: Long)
    extends AbstractFileWriteOp(file, bufs, start),
      EventPollingOffThreadOp:
  def _writeAt(file: EventPollingFile, buf: ByteBuffer, offset: Long): Int = PosixFiles.writeAt(file.fd, buf, offset)

private[eventpolling] final class EventPollingFilePooledRead(file: EventPollingFile, pool: LendingPool, into: Slot[Received])
    extends AbstractFilePooledReadOp(file, pool, into),
      EventPollingOffThreadOp:
  def _readAt(file: EventPollingFile, buf: ByteBuffer, offset: Long): Int = PosixFiles.readAt(file.fd, buf, offset)

private[eventpolling] final class EventPollingFileSync(file: EventPollingFile) extends AbstractFileSyncOp(file), EventPollingOffThreadOp:
  def _sync(file: EventPollingFile): Unit = PosixFiles.sync(file.fd)

// ---- Name resolution

/** There is no `getaddrinfo` that does not block, so the lookup runs off the workers, the way libuv and tokio do it. */
private[eventpolling] final class EventPollingResolve(host: String, into: Slot[List[IpAddress]])
    extends AbstractResolveOp(host, into),
      EventPollingOffThreadOp:
  def _lookup(host: String): List[IpAddress] = PosixResolver.lookup(host) match
    case Right(addresses) => addresses
    case Left(error)      => throw new java.io.IOException(s"Failed to resolve $host: $error")

// ---- FIFOs

/** Opens the FIFO at `path`, without waiting for its other end, as the handle `make` makes of its descriptor. */
private[eventpolling] final class EventPollingOpenFifo[K <: EventPollingHandle](path: String, write: Boolean, into: Slot[K])(
    make: (Int, HandleOwner) => K
) extends AbstractOpenOp(into),
      EventPollingImmediateOp:
  gears.async.io.shareable.Checks.checkPath(path)
  def _open(owner: HandleOwner): K =
    val fd = Descriptors.openFifo(path, write)
    try make(fd, owner)
    catch
      case failure: Throwable =>
        Descriptors.closeHandle(fd)
        throw failure
