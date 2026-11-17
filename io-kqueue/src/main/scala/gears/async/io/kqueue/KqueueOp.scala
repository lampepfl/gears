package gears.async.io.kqueue

import gears.async.Async
import gears.async.io.Deadline
import gears.async.io.Received
import gears.async.io.Slot
import gears.async.io.SocketOption
import gears.async.io.SocketOptions
import gears.async.io.posix.PosixDatagrams
import gears.async.io.posix.PosixReadOps
import gears.async.io.posix.PosixSockets
import gears.async.io.posix.PosixWriteOps
import gears.async.io.posix.SocketAddress
import gears.async.io.posix.UnixBinding
import gears.async.io.shareable.AbstractAcceptAllOp
import gears.async.io.shareable.AbstractAcceptOp
import gears.async.io.shareable.AbstractCloseOp
import gears.async.io.shareable.AbstractConnectOp
import gears.async.io.shareable.AbstractGatherWriteOp
import gears.async.io.shareable.AbstractHandleCallOp
import gears.async.io.shareable.AbstractMakeDeadlineOp
import gears.async.io.shareable.AbstractMakePipeOp
import gears.async.io.shareable.AbstractMakeTimerOp
import gears.async.io.shareable.AbstractOpenOp
import gears.async.io.shareable.AbstractPooledReadOp
import gears.async.io.shareable.AbstractReadOp
import gears.async.io.shareable.AbstractReceiveOp
import gears.async.io.shareable.AbstractScatterReadOp
import gears.async.io.shareable.AbstractSendOp
import gears.async.io.shareable.AbstractWriteOp
import gears.async.io.shareable.HandleOwner
import gears.async.io.shareable.LendingPool
import gears.async.io.shareable.TimerHeapOp

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

sealed trait KqueueDatagramOp extends KqueueReadinessOp

sealed trait KqueueConnectOp extends KqueueOp:
  def performConnect(self: KqueuePoller)(using Async): Unit

sealed trait KqueueTimerOp extends KqueueOp

sealed trait KqueuePipeOp extends KqueueImmediateOp

sealed trait KqueueDeadlineOp extends KqueueOp with Deadline

sealed trait KqueueCloseOp extends KqueueOp:
  def performClose(self: KqueuePoller)(using Async): Unit

private[kqueue] final class KqueueRead(handle: KqueueReadable, buf: ByteBuffer)
    extends AbstractReadOp(handle, buf),
      KqueueReadableOp,
      PosixReadOps[KqueueReadable]

private[kqueue] final class KqueueWrite(handle: KqueueWritable, buf: ByteBuffer)
    extends AbstractWriteOp(handle, buf),
      KqueueWritableOp,
      PosixWriteOps[KqueueWritable]

private[kqueue] final class KqueueScatterRead(handle: KqueueReadable, bufs: Array[ByteBuffer])
    extends AbstractScatterReadOp(handle, bufs),
      KqueueReadableOp,
      PosixReadOps[KqueueReadable]

private[kqueue] final class KqueuePooledRead(handle: KqueueReadable, pool: LendingPool, into: Slot[Received])
    extends AbstractPooledReadOp(handle, pool, into),
      KqueueReadableOp,
      PosixReadOps[KqueueReadable]

private[kqueue] final class KqueueGatherWrite(handle: KqueueWritable, bufs: Array[ByteBuffer])
    extends AbstractGatherWriteOp(handle, bufs),
      KqueueWritableOp,
      PosixWriteOps[KqueueWritable]

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
