package gears.async.io.kqueue

import gears.async.io.SocketOption
import gears.async.io.SocketOptions
import gears.async.io.posix.PosixKernelHandle
import gears.async.io.posix.PosixListener
import gears.async.io.posix.PosixPipeOpener
import gears.async.io.posix.PosixSocket
import gears.async.io.posix.PosixSocketOpener
import gears.async.io.posix.UnixBinding
import gears.async.io.shareable.BaseHandle
import gears.async.io.shareable.BaseReadableHandle
import gears.async.io.shareable.BaseWritableHandle
import gears.async.io.shareable.HandleOwner

sealed trait KqueueHandle extends PosixKernelHandle, BaseHandle:
  type Source = KqueuePoller

sealed trait KqueueReadable extends KqueueHandle, BaseReadableHandle
sealed trait KqueueWritable extends KqueueHandle, BaseWritableHandle

final class PipeRead(fd: Int, owner: HandleOwner) extends PosixKernelHandle(fd, owner) with KqueueReadable
final class PipeWrite(fd: Int, owner: HandleOwner) extends PosixKernelHandle(fd, owner) with KqueueWritable

/** A socket: what its options and addresses are asked of. */
sealed trait KqueueSocket extends KqueueHandle, PosixSocket

/** A connected stream socket, IP or Unix: one read and one write at a time, as any stream. */
final class KqueueConnection(fd: Int, val kind: SocketOption.Kind, val ipv6: Boolean, owner: HandleOwner)
    extends PosixKernelHandle(fd, owner),
      KqueueReadable,
      KqueueWritable,
      KqueueSocket

/** A listening stream socket, IP or Unix, whose accepts take turns. A Unix one removes the socket file it bound as it
  * closes.
  */
final class KqueueListener(
    fd: Int,
    val kind: SocketOption.Kind,
    val ipv6: Boolean,
    val accepting: SocketOptions,
    bound: UnixBinding | Null,
    owner: HandleOwner
) extends PosixKernelHandle(fd, owner),
      KqueueSocket,
      PosixListener:
  override protected def queuesWaiters(read: Boolean): Boolean = true
  override protected def beforeDescriptorCloses(): Unit = if bound != null then bound.remove()

/** A datagram socket, whose receives take turns, as do its sends. A Unix one removes the socket file it bound as it
  * closes.
  */
sealed abstract class KqueueDatagramSocket(
    fd: Int,
    val kind: SocketOption.Kind,
    val ipv6: Boolean,
    bound: UnixBinding | Null,
    owner: HandleOwner
) extends PosixKernelHandle(fd, owner),
      KqueueSocket:
  override protected def queuesWaiters(read: Boolean): Boolean = true
  override protected def beforeDescriptorCloses(): Unit = if bound != null then bound.remove()

final class KqueueBoundDatagram(fd: Int, ipv6: Boolean, owner: HandleOwner)
    extends KqueueDatagramSocket(fd, SocketOption.Kind.BoundDatagram, ipv6, null, owner)

final class KqueueConnectedDatagram(fd: Int, ipv6: Boolean, owner: HandleOwner)
    extends KqueueDatagramSocket(fd, SocketOption.Kind.ConnectedDatagram, ipv6, null, owner)

final class KqueueUnixBoundDatagram(fd: Int, bound: UnixBinding | Null, owner: HandleOwner)
    extends KqueueDatagramSocket(fd, SocketOption.Kind.UnixBoundDatagram, false, bound, owner)

final class KqueueUnixConnectedDatagram(fd: Int, bound: UnixBinding | Null, owner: HandleOwner)
    extends KqueueDatagramSocket(fd, SocketOption.Kind.UnixConnectedDatagram, false, bound, owner)

/** Opens this backend's sockets: the POSIX calls, with the handles made here. */
object KqueueSockets extends PosixSocketOpener:
  type Connection = KqueueConnection
  type Listener = KqueueListener

  def makeConnection(fd: Int, kind: SocketOption.Kind, ipv6: Boolean, owner: HandleOwner): KqueueConnection =
    new KqueueConnection(fd, kind, ipv6, owner)

  def makeListener(
      fd: Int,
      kind: SocketOption.Kind,
      ipv6: Boolean,
      accepting: SocketOptions,
      bound: UnixBinding | Null,
      owner: HandleOwner
  ): KqueueListener = new KqueueListener(fd, kind, ipv6, accepting, bound, owner)

trait KqueuePipeOpener extends PosixPipeOpener[KqueueReadable, KqueueWritable]:
  final def makeReadableHandle(fd: Int, owner: HandleOwner): PipeRead = new PipeRead(fd, owner)
  final def makeWritableHandle(fd: Int, owner: HandleOwner): PipeWrite = new PipeWrite(fd, owner)
