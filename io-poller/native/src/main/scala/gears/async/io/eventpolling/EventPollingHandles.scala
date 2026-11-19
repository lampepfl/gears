package gears.async.io.eventpolling

import gears.async.io.SocketOption
import gears.async.io.SocketOptions
import gears.async.io.posix.FifoWatcher
import gears.async.io.posix.PosixKernelHandle
import gears.async.io.posix.PosixListener
import gears.async.io.posix.PosixPipeOpener
import gears.async.io.posix.PosixSocket
import gears.async.io.posix.PosixSocketOpener
import gears.async.io.posix.UnixBinding
import gears.async.io.shareable.AbstractReadinessHandle
import gears.async.io.shareable.BaseHandle
import gears.async.io.shareable.BaseReadableHandle
import gears.async.io.shareable.BaseWritableHandle
import gears.async.io.shareable.FilePosition
import gears.async.io.shareable.HandleOwner

import scala.scalanative.meta.LinktimeInfo

sealed trait EventPollingHandle extends PosixKernelHandle, BaseHandle

/** A handle whose ops wait for a worker's kqueue to report it ready: every kind but a file. */
sealed trait EventPollingWaitable extends EventPollingHandle, AbstractReadinessHandle:
  type Source = EventPoller

sealed trait EventPollingReadable extends EventPollingHandle, BaseReadableHandle
sealed trait EventPollingWritable extends EventPollingHandle, BaseWritableHandle

/** A stream that is waited for: a pipe's end, a FIFO's or a connection, not a file. */
private[eventpolling] type EventPollingWaitingReadable = EventPollingReadable & EventPollingWaitable
private[eventpolling] type EventPollingWaitingWritable = EventPollingWritable & EventPollingWaitable

final class PipeRead(fd: Int, owner: HandleOwner) extends PosixKernelHandle(fd, owner), EventPollingReadable, EventPollingWaitable
final class PipeWrite(fd: Int, owner: HandleOwner) extends PosixKernelHandle(fd, owner), EventPollingWritable, EventPollingWaitable

/** A regular file: read and written at offsets, and as a stream at the position it carries. Its ops are done off the
  * workers, never waited for here: the system reports a file ready whatever it would do.
  */
final class EventPollingFile(fd: Int, owner: HandleOwner)
    extends PosixKernelHandle(fd, owner),
      EventPollingReadable,
      EventPollingWritable,
      FilePosition

/** A socket: what its options and addresses are asked of. */
sealed trait EventPollingSocket extends EventPollingHandle, PosixSocket

/** A connected stream socket, IP or Unix: one read and one write at a time, as any stream. */
final class EventPollingConnection(fd: Int, val kind: SocketOption.Kind, val ipv6: Boolean, owner: HandleOwner)
    extends PosixKernelHandle(fd, owner),
      EventPollingReadable,
      EventPollingWritable,
      EventPollingSocket,
      EventPollingWaitable

/** A listening stream socket, IP or Unix, whose accepts take turns. A Unix one removes the socket file it bound as it
  * closes.
  */
final class EventPollingListener(
    fd: Int,
    val kind: SocketOption.Kind,
    val ipv6: Boolean,
    val accepting: SocketOptions,
    bound: UnixBinding | Null,
    owner: HandleOwner
) extends PosixKernelHandle(fd, owner),
      EventPollingSocket,
      PosixListener,
      EventPollingWaitable:
  override protected def queuesWaiters(read: Boolean): Boolean = true
  override protected def beforeDescriptorCloses(): Unit = if bound != null then bound.remove()

/** A datagram socket, whose receives take turns, as do its sends. A Unix one removes the socket file it bound as it
  * closes.
  */
sealed abstract class EventPollingDatagramSocket(
    fd: Int,
    val kind: SocketOption.Kind,
    val ipv6: Boolean,
    bound: UnixBinding | Null,
    owner: HandleOwner
) extends PosixKernelHandle(fd, owner),
      EventPollingSocket,
      EventPollingWaitable:
  override protected def queuesWaiters(read: Boolean): Boolean = true
  override protected def beforeDescriptorCloses(): Unit = if bound != null then bound.remove()

final class EventPollingBoundDatagram(fd: Int, ipv6: Boolean, owner: HandleOwner)
    extends EventPollingDatagramSocket(fd, SocketOption.Kind.BoundDatagram, ipv6, null, owner)

final class EventPollingConnectedDatagram(fd: Int, ipv6: Boolean, owner: HandleOwner)
    extends EventPollingDatagramSocket(fd, SocketOption.Kind.ConnectedDatagram, ipv6, null, owner)

final class EventPollingUnixBoundDatagram(fd: Int, bound: UnixBinding | Null, owner: HandleOwner)
    extends EventPollingDatagramSocket(fd, SocketOption.Kind.UnixBoundDatagram, false, bound, owner)

final class EventPollingUnixConnectedDatagram(fd: Int, bound: UnixBinding | Null, owner: HandleOwner)
    extends EventPollingDatagramSocket(fd, SocketOption.Kind.UnixConnectedDatagram, false, bound, owner)

/** Opens this backend's sockets: the POSIX calls, with the handles made here. */
object EventPollingSockets extends PosixSocketOpener:
  type Connection = EventPollingConnection
  type Listener = EventPollingListener

  def makeConnection(fd: Int, kind: SocketOption.Kind, ipv6: Boolean, owner: HandleOwner): EventPollingConnection =
    new EventPollingConnection(fd, kind, ipv6, owner)

  def makeListener(
      fd: Int,
      kind: SocketOption.Kind,
      ipv6: Boolean,
      accepting: SocketOptions,
      bound: UnixBinding | Null,
      owner: HandleOwner
  ): EventPollingListener = new EventPollingListener(fd, kind, ipv6, accepting, bound, owner)

/** A FIFO's read end, read as a pipe's is. On macOS a FIFO whose writer closes is reported to neither kqueue nor
  * `poll`, so a read waiting for it would never see the end of the stream: the descriptor is watched with `select` as
  * well (`FifoWatcher`), which does report it, and tells the handle's listener. On other systems the event queue
  * reports it, and nothing is watched.
  *
  * It is watched only once a read has waited `WatchAfterNanos`, not as each begins to wait: a watch costs a system call
  * and two turns of the watcher's thread, which a read answered in less never needed, as the bytes are the kqueue's to
  * report. The end of a FIFO is so seen at most that much later than it came.
  */
final class FifoRead(fd: Int, owner: HandleOwner) extends PosixKernelHandle(fd, owner), EventPollingReadable, EventPollingWaitable:

  /** Whether the listener has been asked to look, in a while, at whether a read still waits. */
  private val lookPending = new java.util.concurrent.atomic.AtomicBoolean(false)

  // On macOS alone, decided as the program is linked: elsewhere the watcher, and the `select` it calls, are not in it.
  override def waiterWaits(read: Boolean, listener: EventPoller): Unit =
    if LinktimeInfo.isMac then
      if read && lookPending.compareAndSet(false, true) then listener.runAfter(FifoRead.WatchAfterNanos, () => look())

  /** A read that waits now, whether or not it is the one that asked, has the descriptor watched. */
  private def look(): Unit =
    if LinktimeInfo.isMac then
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
  override protected def beforeDescriptorCloses(): Unit = if LinktimeInfo.isMac then FifoWatcher.forget(fd)

object FifoRead:

  /** How long a read waits before its descriptor is watched with `select`. */
  inline val WatchAfterNanos = 1_000_000L

/** A FIFO's write end, written as a pipe's is. */
final class FifoWrite(fd: Int, owner: HandleOwner) extends PosixKernelHandle(fd, owner), EventPollingWritable, EventPollingWaitable

trait EventPollingPipeOpener extends PosixPipeOpener[EventPollingReadable, EventPollingWritable]:
  final def makeReadableHandle(fd: Int, owner: HandleOwner): PipeRead = new PipeRead(fd, owner)
  final def makeWritableHandle(fd: Int, owner: HandleOwner): PipeWrite = new PipeWrite(fd, owner)
