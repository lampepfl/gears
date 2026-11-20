package gears.async.io
package uring

import gears.async.io.posix.PosixKernelHandle
import gears.async.io.posix.PosixListener
import gears.async.io.posix.PosixSocket
import gears.async.io.posix.PosixSocketOpener
import gears.async.io.posix.UnixBinding
import gears.async.io.shareable.BaseReadableHandle
import gears.async.io.shareable.BaseWritableHandle
import gears.async.io.shareable.HandleOwner
import gears.async.io.shareable.HandleState

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import scala.scalanative.posix.fcntl

// - a command claims the handle's work until the kernel has let go of its entry: the descriptor is closed only then
sealed abstract class UringHandle(fd: Int, kind: String, owner: HandleOwner) extends PosixKernelHandle(fd, owner) {
  private val rings = new ConcurrentHashMap[Ring, AtomicInteger]()

  private[uring] def onRing(ring: Ring): Unit = {
    var n = rings.get(ring)
    if n == null then {
      rings.putIfAbsent(ring, new AtomicInteger()): Unit
      n = rings.get(ring)
    }
    n.nn.incrementAndGet(): Unit
  }

  private[uring] def offRing(ring: Ring): Unit = {
    val n = rings.get(ring)
    if n != null then n.decrementAndGet(): Unit
  }

  private[uring] def entriesOnRings: Int = {
    var n = 0
    rings.forEach((_, count) => n += count.get())
    n
  }

  private[uring] val reading = new AtomicBoolean()
  private[uring] val writing = new AtomicBoolean()

  @volatile private[uring] var parked: Command | Null = null

  def isClosing: Boolean = getState != HandleState.OpenState

  private[uring] def triesAtOnce: Boolean = true

  protected def failWaiting(): Unit = ()

  /** Once the handle is closing, with work claimed on it: what waits fails, and the entries on it are cancelled. */
  private[uring] def closing(): Unit = {
    failWaiting()
    val waiting = parked
    if waiting != null && waiting.leaveParking() then waiting.owner.nn.startTurn(waiting)
    rings.forEach { (ring, n) =>
      if n.get() > 0 then {
        ring.cancelFd(fd)
        ring.wake()
      }
    }
  }

  private[uring] def closedFailure: HandleClosedException = new HandleClosedException(this)

  override def toString: String = s"$kind $fd"
}

private[uring] object UringHandle {
  def closeOnExec(fd: Int): Unit =
    if fcntl.fcntl(fd, fcntl.F_SETFD, fcntl.FD_CLOEXEC) < 0 then throw Results.failed("Failed to set FD_CLOEXEC")
}

trait UringReadable extends UringHandle with BaseReadableHandle

trait UringWritable extends UringHandle with BaseWritableHandle

final class PipeSource private[uring] (fd: Int, owner: HandleOwner)
    extends UringHandle(fd, "pipe's read end", owner)
    with UringReadable

final class PipeSink private[uring] (fd: Int, owner: HandleOwner)
    extends UringHandle(fd, "pipe's write end", owner)
    with UringWritable

final class FifoSource private[uring] (fd: Int, owner: HandleOwner)
    extends UringHandle(fd, "FIFO's read end", owner)
    with UringReadable

final class FifoSink private[uring] (fd: Int, owner: HandleOwner)
    extends UringHandle(fd, "FIFO's write end", owner)
    with UringWritable

final class ConnectionHandle private[uring] (
    fd: Int,
    val kind: SocketOption.Kind,
    val ipv6: Boolean,
    owner: HandleOwner
) extends UringHandle(fd, if kind.unix then "Unix connection" else "connection", owner)
    with UringReadable
    with UringWritable
    with PosixSocket

final class ListenerHandle private[uring] (
    fd: Int,
    val kind: SocketOption.Kind,
    val ipv6: Boolean,
    val accepting: SocketOptions,
    bound: UnixBinding | Null,
    owner: HandleOwner
) extends UringHandle(fd, if kind.unix then "Unix listener" else "listener", owner)
    with PosixListener {
  private[uring] val accepts = new Turns

  override protected def failWaiting(): Unit = accepts.failWaiting()

  override protected def beforeDescriptorCloses(): Unit = if bound != null then bound.remove()
}

sealed abstract class DatagramHandle(
    fd: Int,
    val kind: SocketOption.Kind,
    val ipv6: Boolean,
    bound: UnixBinding | Null,
    owner: HandleOwner
) extends UringHandle(fd, kind.description, owner)
    with PosixSocket {
  def unix: Boolean = kind.unix

  private[uring] val receives = new Turns
  private[uring] val sends = new Turns

  override protected def failWaiting(): Unit = {
    receives.failWaiting()
    sends.failWaiting()
  }

  override protected def beforeDescriptorCloses(): Unit = if bound != null then bound.remove()
}

final class BoundDatagramHandle private[uring] (fd: Int, ipv6: Boolean, owner: HandleOwner)
    extends DatagramHandle(fd, SocketOption.Kind.BoundDatagram, ipv6, null, owner)

final class ConnectedDatagramHandle private[uring] (fd: Int, ipv6: Boolean, owner: HandleOwner)
    extends DatagramHandle(fd, SocketOption.Kind.ConnectedDatagram, ipv6, null, owner)

final class UnixBoundDatagramHandle private[uring] (fd: Int, bound: UnixBinding | Null, owner: HandleOwner)
    extends DatagramHandle(fd, SocketOption.Kind.UnixBoundDatagram, false, bound, owner)

final class UnixConnectedDatagramHandle private[uring] (fd: Int, bound: UnixBinding | Null, owner: HandleOwner)
    extends DatagramHandle(fd, SocketOption.Kind.UnixConnectedDatagram, false, bound, owner)

final class FileHandle private[uring] (fd: Int, path: String, owner: HandleOwner)
    extends UringHandle(fd, s"file $path", owner)
    with UringReadable
    with UringWritable {
  override private[uring] def triesAtOnce: Boolean = false
}

// - Jamie's openers make the descriptors: each is made close-on-exec as its handle is
private[uring] object UringSockets extends PosixSocketOpener {
  type Connection = ConnectionHandle
  type Listener = ListenerHandle

  def makeConnection(fd: Int, kind: SocketOption.Kind, ipv6: Boolean, owner: HandleOwner): ConnectionHandle = {
    UringHandle.closeOnExec(fd)
    new ConnectionHandle(fd, kind, ipv6, owner)
  }

  def makeListener(
      fd: Int,
      kind: SocketOption.Kind,
      ipv6: Boolean,
      accepting: SocketOptions,
      bound: UnixBinding | Null,
      owner: HandleOwner
  ): ListenerHandle = {
    UringHandle.closeOnExec(fd)
    new ListenerHandle(fd, kind, ipv6, accepting, bound, owner)
  }

  /** The connection `fd`, accepted on `listener` close-on-exec and non-blocking, with the options it gives. */
  def accepted(listener: ListenerHandle, fd: Int): ConnectionHandle = {
    try
      for (option, value) <- SocketOption.accepted(listener.accepting, listener.kind) do
        gears.async.io.posix.PosixSocketOptions.set(fd, listener.ipv6, option, value)
    catch case _: java.io.IOException => ()
    val kind = if listener.kind.unix then SocketOption.Kind.UnixConnection else SocketOption.Kind.Connection
    val connection = new ConnectionHandle(fd, kind, listener.ipv6, listener.owner)
    connection.open()
    connection
  }
}

private[uring] final class Turns {
  private val queue = new java.util.ArrayDeque[Command]()

  def enter(command: Command): Boolean = synchronized {
    queue.addLast(command)
    queue.size == 1
  }

  def leaveWaiting(command: Command): Boolean = synchronized {
    if queue.peekFirst() eq command then false else queue.remove(command)
  }

  def pass(command: Command): Command | Null = synchronized {
    if queue.peekFirst() eq command then {
      queue.pollFirst()
      queue.peekFirst()
    } else {
      queue.remove(command)
      null
    }
  }

  def failWaiting(): Unit = {
    val waiting = synchronized {
      val all = queue.toArray(new Array[Command](0)).toList
      all.drop(1).foreach(queue.remove)
      all.drop(1)
    }
    waiting.foreach(c => c.owner.nn.startTurn(c))
  }
}
