package gears.async.io.kqueue

import gears.async.io.SocketOption
import gears.async.io.SocketOptions
import gears.async.io.posix.FifoWatcher
import gears.async.io.posix.PosixKernelHandle
import gears.async.io.posix.PosixListener
import gears.async.io.posix.PosixPipeOpener
import gears.async.io.posix.PosixSocket
import gears.async.io.posix.PosixSocketOpener
import gears.async.io.posix.UnixBinding
import gears.async.io.shareable.BaseHandle
import gears.async.io.shareable.BaseReadableHandle
import gears.async.io.shareable.BaseWritableHandle
import gears.async.io.shareable.EventSource
import gears.async.io.shareable.FilePosition
import gears.async.io.shareable.HandleOwner

sealed trait KqueueHandle extends PosixKernelHandle, BaseHandle:
  type Source = KqueuePoller

sealed trait KqueueReadable extends KqueueHandle, BaseReadableHandle
sealed trait KqueueWritable extends KqueueHandle, BaseWritableHandle

final class PipeRead(fd: Int, owner: HandleOwner) extends PosixKernelHandle(fd, owner) with KqueueReadable
final class PipeWrite(fd: Int, owner: HandleOwner) extends PosixKernelHandle(fd, owner) with KqueueWritable

/** A regular file: read and written at offsets, and as a stream at the position it carries. Its ops are done off the
  * workers, never waited for here: the system reports a file ready whatever it would do.
  */
final class KqueueFile(fd: Int, owner: HandleOwner)
    extends PosixKernelHandle(fd, owner),
      KqueueReadable,
      KqueueWritable,
      FilePosition

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

/** A FIFO's read end, read as a pipe's is. On macOS a FIFO whose writer closes is reported to neither kqueue nor
  * `poll`, so a read waiting for it would never see the end of the stream: the descriptor is watched with `select` as
  * well (`FifoWatcher`), which does report it, and tells the handle's listener.
  *
  * It is watched only once a read has waited `WatchAfterNanos`, not as each begins to wait: a watch costs a system call
  * and two turns of the watcher's thread, which a read answered in less never needed, as the bytes are the kqueue's to
  * report. The end of a FIFO is so seen at most that much later than it came.
  */
final class FifoRead(fd: Int, owner: HandleOwner) extends PosixKernelHandle(fd, owner) with KqueueReadable:

  /** Whether the listener has been asked to look, in a while, at whether a read still waits. */
  private val lookPending = new java.util.concurrent.atomic.AtomicBoolean(false)

  override def waiterWaits(read: Boolean, listener: EventSource): Unit =
    if read && lookPending.compareAndSet(false, true) then listener.runAfter(FifoRead.WatchAfterNanos, () => look())

  /** A read that waits now, whether or not it is the one that asked, has the descriptor watched. */
  private def look(): Unit =
    lookPending.set(false)
    if readiness(true).isWaitedOn then
      val open =
        try
          claimWork() // a close waits, so that the descriptor is not watched once it is released
          true
        catch case _: java.io.IOException => false
      if open then
        try FifoWatcher.watch(fd, this, () => tell())
        finally releaseWork()

  /** From the watcher's thread: whoever listens for the handle now reports it. */
  private def tell(): Unit =
    val listening = readiness(true).currentListener
    if listening != null then listening.reportLater(this, read = true)

  // Before the descriptor is released, so that its number is not watched once it is another's.
  override protected def beforeDescriptorCloses(): Unit = FifoWatcher.forget(fd)

object FifoRead:

  /** How long a read waits before its descriptor is watched with `select`. */
  inline val WatchAfterNanos = 1_000_000L

/** A FIFO's write end, written as a pipe's is. */
final class FifoWrite(fd: Int, owner: HandleOwner) extends PosixKernelHandle(fd, owner) with KqueueWritable

trait KqueuePipeOpener extends PosixPipeOpener[KqueueReadable, KqueueWritable]:
  final def makeReadableHandle(fd: Int, owner: HandleOwner): PipeRead = new PipeRead(fd, owner)
  final def makeWritableHandle(fd: Int, owner: HandleOwner): PipeWrite = new PipeWrite(fd, owner)
