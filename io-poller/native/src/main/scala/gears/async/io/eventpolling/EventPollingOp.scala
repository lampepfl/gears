package gears.async.io.kqueue

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
import gears.async.io.shareable.TimerHeapOp
import gears.async.io.shareable.Worker

import java.nio.ByteBuffer
import scala.concurrent.duration.FiniteDuration

sealed trait KqueueOp extends TimerHeapOp:

  /** A kqueue worker takes any kqueue op among its timers (see `KqueuePoller.Timer`). */
  type Source = KqueuePoller
  final def _holdTimer(worker: KqueuePoller): Unit = worker.addTimer(this)

/** An op that waits for its handle to be ready, on the worker that listens for it (see `ReadyWaiting`). */
sealed trait KqueueReadinessOp extends KqueueOp:
  def performWhenReady(self: KqueuePoller)(using Async): Unit

/** An op done as it is performed, with nothing to wait for (see `ImmediateOp`). */
sealed trait KqueueImmediateOp extends KqueueOp:
  def perform(owner: HandleOwner): Unit

sealed trait KqueueReadableOp extends KqueueReadinessOp

sealed trait KqueueWritableOp extends KqueueReadinessOp

sealed trait KqueueAcceptOp extends KqueueReadinessOp

/** An op whose work is done off the workers' threads (see `WorkOffThread`). */
sealed trait KqueueOffThreadOp extends KqueueOp:
  def performOffThread(worker: Worker & KqueuePoller)(using Async): Unit
  def performInPlace(worker: Worker & KqueuePoller)(using Async): Unit

sealed trait KqueueDatagramOp extends KqueueReadinessOp

sealed trait KqueueConnectOp extends KqueueOp:
  def performConnect(self: KqueuePoller)(using Async): Unit

sealed trait KqueueTimerOp extends KqueueOp

sealed trait KqueuePipeOp extends KqueueImmediateOp

sealed trait KqueueDeadlineOp extends KqueueOp with Deadline

sealed trait KqueueCloseOp extends KqueueOp:
  def performClose(self: KqueuePoller)(using Async): Unit

private[kqueue] final class KqueueRead(handle: KqueueWaitingReadable, buf: ByteBuffer)
    extends AbstractReadOp(handle, buf),
      KqueueReadableOp,
      PosixReadOps[KqueueWaitingReadable]

private[kqueue] final class KqueueWrite(handle: KqueueWaitingWritable, buf: ByteBuffer)
    extends AbstractWriteOp(handle, buf),
      KqueueWritableOp,
      PosixWriteOps[KqueueWaitingWritable]

private[kqueue] final class KqueueScatterRead(handle: KqueueWaitingReadable, bufs: Array[ByteBuffer])
    extends AbstractScatterReadOp(handle, bufs),
      KqueueReadableOp,
      PosixReadOps[KqueueWaitingReadable]

private[kqueue] final class KqueuePooledRead(handle: KqueueWaitingReadable, pool: LendingPool, into: Slot[Received])
    extends AbstractPooledReadOp(handle, pool, into),
      KqueueReadableOp,
      PosixReadOps[KqueueWaitingReadable]

private[kqueue] final class KqueueGatherWrite(handle: KqueueWaitingWritable, bufs: Array[ByteBuffer])
    extends AbstractGatherWriteOp(handle, bufs),
      KqueueWritableOp,
      PosixWriteOps[KqueueWaitingWritable]

private[kqueue] final class KqueueCloseHandle(handle: KqueueHandle) extends AbstractCloseOp(handle) with KqueueCloseOp

private[kqueue] final class KqueueMakePipe(readSlot: Slot[KqueueReadable], writeSlot: Slot[KqueueWritable])
    extends AbstractMakePipeOp(readSlot, writeSlot)
    with KqueuePipeOpener
    with KqueuePipeOp

private[kqueue] final class KqueueMakeTimer(duration: FiniteDuration)
    extends AbstractMakeTimerOp(duration)
    with KqueueTimerOp

private[kqueue] final class KqueueMakeDeadline(initial: Long)
    extends AbstractMakeDeadlineOp(initial)
    with KqueueDeadlineOp

// ---- Stream sockets

private[kqueue] final class KqueueConnect(address: SocketAddress, options: SocketOptions, into: Slot[KqueueConnection])
    extends AbstractConnectOp(into),
      KqueueConnectOp:
  def _startConnect(owner: HandleOwner): KqueueConnection = KqueueSockets.startConnect(address, options, owner)
  def _finishConnect(socket: KqueueConnection): Boolean = KqueueSockets.finishConnect(socket)

private[kqueue] final class KqueueListen(address: SocketAddress, options: SocketOptions, into: Slot[KqueueListener])
    extends AbstractOpenOp(into),
      KqueueImmediateOp:
  def _open(owner: HandleOwner): KqueueListener = KqueueSockets.listen(address, options, owner)

private[kqueue] final class KqueueAccept(listener: KqueueListener, into: Slot[KqueueConnection])
    extends AbstractAcceptOp(listener, into),
      KqueueAcceptOp:
  def _accept(listener: KqueueListener): KqueueConnection | Null = KqueueSockets.accept(listener)

private[kqueue] final class KqueueAcceptAll(listener: KqueueListener, into: Slot[Array[KqueueConnection]], max: Int)
    extends AbstractAcceptAllOp(listener, into, max),
      KqueueAcceptOp:
  def _accept(listener: KqueueListener): KqueueConnection | Null = KqueueSockets.accept(listener)

private[kqueue] final class KqueueShutdownOutput(connection: KqueueConnection)
    extends AbstractHandleCallOp(connection),
      KqueueImmediateOp:
  def _call(connection: KqueueConnection): Unit = PosixSockets.shutdownOutput(connection.fd)

// ---- Datagram sockets

/** Opens a datagram socket of kind `kind`, bound to `local` and connected to `remote` where given, as the handle `make`
  * makes of it.
  */
private[kqueue] final class KqueueOpenDatagram[D <: KqueueDatagramSocket](
    local: SocketAddress | Null,
    remote: SocketAddress | Null,
    options: SocketOptions,
    kind: SocketOption.Kind,
    into: Slot[D]
)(make: (Int, Boolean, UnixBinding | Null, HandleOwner) => D)
    extends AbstractOpenOp(into),
      KqueueImmediateOp:
  def _open(owner: HandleOwner): D = KqueueSockets.openDatagram(local, remote, options, kind, owner)(make)

private[kqueue] final class KqueueReceive[A <: AnyRef](
    socket: KqueueDatagramSocket,
    buf: ByteBuffer,
    from: Slot[A] | Null
) extends AbstractReceiveOp(socket, buf, from),
      KqueueDatagramOp:
  def _receive(socket: KqueueDatagramSocket, buf: ByteBuffer, from: Slot[A] | Null): Boolean =
    PosixDatagrams.receive(socket.fd, buf, from)

/** Sends to `to`, or to the socket's peer if it is null, which a connected socket's send has it. */
private[kqueue] final class KqueueSend(socket: KqueueDatagramSocket, bufs: Array[ByteBuffer], to: SocketAddress | Null)
    extends AbstractSendOp(socket, bufs, connected = to == null),
      KqueueDatagramOp:
  def _send(socket: KqueueDatagramSocket, bufs: Array[ByteBuffer]): Boolean =
    PosixDatagrams.send(socket.fd, socket.ipv6, bufs, to)

// ---- Blocking code

private[kqueue] final class KqueueBlocking(body: () => Unit) extends AbstractBlockingOp(body), KqueueOffThreadOp

private[kqueue] final class KqueueBlockingValue[A <: AnyRef](body: () => A, into: Slot[A])
    extends AbstractBlockingValueOp(body, into),
      KqueueOffThreadOp

// ---- Regular files: `start` is the offset a positional op begins at, or negative for the file's stream position

private[kqueue] final class KqueueOpenFile(path: String, write: Boolean, into: Slot[KqueueFile])
    extends AbstractOpenFileOp(path, write, into),
      KqueueOffThreadOp:
  def _openFile(path: String, write: Boolean, owner: HandleOwner): KqueueFile =
    new KqueueFile(Descriptors.openFile(path, write), owner)

private[kqueue] final class KqueueFileRead(file: KqueueFile, bufs: Array[ByteBuffer], start: Long)
    extends AbstractFileReadOp(file, bufs, start),
      KqueueOffThreadOp:
  def _readAt(file: KqueueFile, buf: ByteBuffer, offset: Long): Int = PosixFiles.readAt(file.fd, buf, offset)

private[kqueue] final class KqueueFileWrite(file: KqueueFile, bufs: Array[ByteBuffer], start: Long)
    extends AbstractFileWriteOp(file, bufs, start),
      KqueueOffThreadOp:
  def _writeAt(file: KqueueFile, buf: ByteBuffer, offset: Long): Int = PosixFiles.writeAt(file.fd, buf, offset)

private[kqueue] final class KqueueFilePooledRead(file: KqueueFile, pool: LendingPool, into: Slot[Received])
    extends AbstractFilePooledReadOp(file, pool, into),
      KqueueOffThreadOp:
  def _readAt(file: KqueueFile, buf: ByteBuffer, offset: Long): Int = PosixFiles.readAt(file.fd, buf, offset)

private[kqueue] final class KqueueFileSync(file: KqueueFile) extends AbstractFileSyncOp(file), KqueueOffThreadOp:
  def _sync(file: KqueueFile): Unit = PosixFiles.sync(file.fd)

// ---- Name resolution

/** There is no `getaddrinfo` that does not block, so the lookup runs off the workers, the way libuv and tokio do it. */
private[kqueue] final class KqueueResolve(host: String, into: Slot[List[IpAddress]])
    extends AbstractResolveOp(host, into),
      KqueueOffThreadOp:
  def _lookup(host: String): List[IpAddress] = PosixResolver.lookup(host) match
    case Right(addresses) => addresses
    case Left(error)      => throw new java.io.IOException(s"Failed to resolve $host: $error")

// ---- FIFOs

/** Opens the FIFO at `path`, without waiting for its other end, as the handle `make` makes of its descriptor. */
private[kqueue] final class KqueueOpenFifo[K <: KqueueHandle](path: String, write: Boolean, into: Slot[K])(
    make: (Int, HandleOwner) => K
) extends AbstractOpenOp(into),
      KqueueImmediateOp:
  gears.async.io.shareable.Checks.checkPath(path)
  def _open(owner: HandleOwner): K =
    val fd = Descriptors.openFifo(path, write)
    try make(fd, owner)
    catch
      case failure: Throwable =>
        Descriptors.closeHandle(fd)
        throw failure
