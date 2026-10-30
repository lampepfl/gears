package gears.async.io
package reference

import gears.async.Async

import java.io.IOException
import java.net.InetAddress
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.FileChannel
import java.nio.channels.NonReadableChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.Pipe
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey.OP_ACCEPT
import java.nio.channels.SelectionKey.OP_CONNECT
import java.nio.channels.SelectionKey.OP_READ
import java.nio.channels.SelectionKey.OP_WRITE
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

private[reference] object Operations {

  def full(buf: ByteBuffer) = new IllegalArgumentException(
    s"The buffer is full, so a read into it can read nothing: $buf"
  )

  def allFull(bufs: Array[ByteBuffer]) =
    new IllegalArgumentException(s"No buffer has room, so a read into them can read nothing: ${bufs.mkString(", ")}")

  /** Closes a channel an opener opened, once the opener has failed: what it opened never reaches the caller. */
  def abandon(channel: SelectableChannel, wait: Wait): Unit =
    try Poller.close(channel, wait)
    catch case _: IOException => ()

  /** A file's channel used the way it was not opened fails as I/O does, not as a program's error. */
  def asIo[T](body: => T): T =
    try body
    catch {
      case _: NonReadableChannelException => throw new IOException("The file was opened for writing, not reading")
      case _: NonWritableChannelException => throw new IOException("The file was opened for reading, not writing")
    }

  /** After a call on a file, which runs to its end once begun: if the file was closed meanwhile, or the op cancelled or
    * out of time, its outcome is dropped, `restore` putting its buffers back as they were, and the op fails.
    */
  def settle(handle: RefHandle, ctx: Ctx, op: AnyRef)(restore: => Unit)(using Async): Unit =
    try {
      handle.checkOpen()
      ctx.checkStop(op)
    } catch {
      case e: Throwable =>
        restore
        throw e
    }
}

import Operations.*

// ---- Streams

final class ReadOp(h: ReadEnd, buf: ByteBuffer) extends RefOp with Repeatable {
  override protected def handle = h
  override protected def check(): Unit = if !buf.hasRemaining() then throw full(buf)
  protected def body(ctx: Ctx)(using Async): Unit = h.exclusively(write = false, this) {
    val before = buf.position()
    while asIo(h.in.read(buf)) == 0 do ctx.ready(this, h, h.inReady.nn, OP_READ)
    if h.inReady == null then settle(h, ctx, this)(buf.position(before))
  }
}

final class ScatterOp(h: ReadEnd, bufs: Array[ByteBuffer]) extends RefOp with Repeatable {
  override protected def handle = h
  override protected def check(): Unit = if !bufs.exists(_.hasRemaining()) then throw allFull(bufs)
  protected def body(ctx: Ctx)(using Async): Unit = h.exclusively(write = false, this) {
    val before = bufs.map(_.position())
    while asIo(h.in.read(bufs)) == 0 do ctx.ready(this, h, h.inReady.nn, OP_READ)
    if h.inReady == null then settle(h, ctx, this)(bufs.zip(before).foreach(_.position(_)))
  }
}

final class WriteOp(h: WriteEnd, buf: ByteBuffer) extends RefOp with Repeatable {
  override protected def handle = h
  protected def body(ctx: Ctx)(using Async): Unit = h.exclusively(write = true, this) {
    if buf.hasRemaining() then {
      val before = buf.position()
      while asIo(h.out.write(buf)) == 0 do ctx.ready(this, h, h.outReady.nn, OP_WRITE)
      if h.outReady == null then settle(h, ctx, this)(buf.position(before))
    }
  }
}

final class GatherOp(h: WriteEnd, bufs: Array[ByteBuffer]) extends RefOp with Repeatable {
  override protected def handle = h
  protected def body(ctx: Ctx)(using Async): Unit = h.exclusively(write = true, this) {
    if bufs.exists(_.hasRemaining()) then {
      val before = bufs.map(_.position())
      while asIo(h.out.write(bufs)) == 0 do ctx.ready(this, h, h.outReady.nn, OP_WRITE)
      if h.outReady == null then settle(h, ctx, this)(bufs.zip(before).foreach(_.position(_)))
    }
  }
}

/** A read into a buffer of `pool`'s, taken only once there are bytes to read, so that one waiting holds none. */
final class PooledReadOp(h: ReadEnd, pool: RefPool, into: Slot[Received]) extends RefOp with Repeatable {
  override protected def handle = h
  override protected def slots = List(into)
  protected def body(ctx: Ctx)(using Async): Unit = h.exclusively(write = false, this) {
    var done = false
    while !done do {
      val buf = pool.take()
      buf.clear()
      val n =
        try asIo(h.in.read(buf))
        catch {
          case e: Throwable =>
            pool.give(buf)
            throw e
        }
      if n == 0 then {
        pool.give(buf)
        ctx.ready(this, h, h.inReady.nn, OP_READ)
      } else {
        if n < 0 then buf.limit(0) else buf.flip()
        into.set(pool.received(buf))
        done = true
      }
    }
  }
}

// ---- Timers

final class TimerOp(nanos: Long) extends RefOp with Repeatable {
  protected def body(ctx: Ctx)(using Async): Unit = Timers.waitUntil(ctx, this, () => ctx.start + nanos)
}

final class DeadlineOp(initial: Long) extends RefOp with Repeatable with Deadline {
  @volatile private var at = initial
  @volatile private var waiting: Wait | Null = null

  def deadline: Long = at

  def moveTo(nanoTime: Long): Unit = {
    at = nanoTime
    val wait = waiting
    if wait != null then wait.signal()
  }

  protected def body(ctx: Ctx)(using Async): Unit = {
    waiting = ctx.waiter
    try Timers.waitUntil(ctx, this, () => at)
    finally waiting = null
  }
}

private[reference] object Timers {

  /** Waits until `due()`, read again whenever woken, unless the time limit comes first. */
  def waitUntil(ctx: Ctx, op: AnyRef, due: () => Long)(using Async): Unit =
    while {
      ctx.waiter.reset()
      System.nanoTime() - due() < 0
    } do {
      if ctx.expired then throw new TimeoutException(s"$op timed out")
      val at = due()
      ctx.waiter.park(if ctx.deadline != Long.MaxValue && ctx.deadline - at < 0 then ctx.deadline else at)
      if ctx.waiter.cancelled then throw new CancellationException()
    }
}

// ---- Closing

final class CloseOp(h: RefHandle) extends RefOp {
  protected def body(ctx: Ctx)(using Async): Unit = h.close(ctx.waiter)
}

// ---- Stream sockets

final class ConnectOp(address: IpAddress | UnixAddress, options: SocketOptions, into: Slot[ConnectionHandle])
    extends RefOp {
  override protected def slots = List(into)
  protected def body(ctx: Ctx)(using Async): Unit = {
    val (socket, target, unix) = address match {
      case ip: IpAddress =>
        val family =
          if ip.isInstanceOf[IpAddress.IPv4] then StandardProtocolFamily.INET else StandardProtocolFamily.INET6
        (SocketChannel.open(family), Addresses.jdk(ip), false)
      case path: UnixAddress => (SocketChannel.open(StandardProtocolFamily.UNIX), Addresses.jdk(path), true)
    }
    try {
      socket.configureBlocking(false)
      val kind = if unix then SocketOption.Kind.UnixConnection else SocketOption.Kind.Connection
      SocketOption.opening(options, kind).foreach((o, v) => Options.set(socket, o, v))
      if !socket.connect(target) then while !socket.finishConnect() do ctx.ready(this, null, socket, OP_CONNECT)
      into.set(new ConnectionHandle(socket, unix))
    } catch {
      case e: Throwable =>
        abandon(socket, ctx.waiter)
        throw e
    }
  }
}

final class ListenOp(address: IpAddress | UnixAddress, options: SocketOptions, into: Slot[ListenerHandle])
    extends RefOp {
  override protected def slots = List(into)
  protected def body(ctx: Ctx)(using Async): Unit = {
    val server = address match {
      case _: IpAddress.IPv4 => ServerSocketChannel.open(StandardProtocolFamily.INET)
      case _: IpAddress.IPv6 => ServerSocketChannel.open(StandardProtocolFamily.INET6)
      case _: UnixAddress    => ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    }
    try {
      server.configureBlocking(false)
      val (kind, path) = address match {
        case ip: IpAddress =>
          server.setOption(StandardSocketOptions.SO_REUSEADDR, true)
          (SocketOption.Kind.Listener, null)
        case unix: UnixAddress =>
          val path = Path.of(unix.path)
          Listening.replaceSocket(path)
          (SocketOption.Kind.UnixListener, path)
      }
      // A listener sets those it has itself; the rest it gives the connections it accepts.
      SocketOption
        .opening(options, kind)
        .foreach((o, v) => if Options.on(server, o) then Options.set(server, o, v))
      address match {
        case ip: IpAddress     => server.bind(Addresses.jdk(ip), 1024)
        case unix: UnixAddress => server.bind(Addresses.jdk(unix), 1024)
      }
      into.set(new ListenerHandle(server, path != null, options, path))
    } catch {
      case e: Throwable =>
        server.close()
        throw e
    }
  }
}

private[reference] object Listening {

  /** Removes a socket file at `path`, as a restarted server's would be; fails, leaving it, on any other kind of file.
    */
  def replaceSocket(path: Path): Unit =
    if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then {
      val mode = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS).asInstanceOf[Int]
      if (mode & 0xf000) == 0xc000 then Files.deleteIfExists(path): Unit
      else throw new IOException(s"$path exists and is not a socket")
    }

  /** An accepted connection, with the options its listener gives it. */
  def accepted(listener: ListenerHandle, socket: SocketChannel): ConnectionHandle = {
    try {
      socket.configureBlocking(false)
      val kind = if listener.unix then SocketOption.Kind.UnixListener else SocketOption.Kind.Listener
      SocketOption.accepted(listener.options, kind).foreach((o, v) => Options.set(socket, o, v))
    } catch {
      case e: Throwable =>
        socket.close()
        throw e
    }
    new ConnectionHandle(socket, listener.unix)
  }
}

final class AcceptOp(l: ListenerHandle, into: Slot[ConnectionHandle]) extends RefOp with Repeatable {
  override protected def handle = l
  override protected def slots = List(into)
  protected def body(ctx: Ctx)(using Async): Unit = {
    l.accepts.take(ctx, l, this)
    try {
      var socket = l.server.accept()
      while socket == null do {
        ctx.ready(this, l, l.server, OP_ACCEPT)
        socket = l.server.accept()
      }
      into.set(Listening.accepted(l, socket))
    } finally l.accepts.release(ctx.waiter)
  }
}

final class AcceptAllOp(l: ListenerHandle, into: Slot[Array[ConnectionHandle]], max: Int)
    extends RefOp
    with Repeatable {
  override protected def handle = l
  override protected def slots = List(into)
  protected def body(ctx: Ctx)(using Async): Unit = {
    l.accepts.take(ctx, l, this)
    try {
      var socket = l.server.accept()
      while socket == null do {
        ctx.ready(this, l, l.server, OP_ACCEPT)
        socket = l.server.accept()
      }
      val accepted = scala.collection.mutable.ArrayBuffer(Listening.accepted(l, socket))
      while accepted.size < max && { socket = l.server.accept(); socket != null } do
        accepted += Listening.accepted(l, socket)
      into.set(accepted.toArray)
    } finally l.accepts.release(ctx.waiter)
  }
}

final class ShutdownOutputOp(c: ConnectionHandle) extends RefOp {
  override protected def handle = c
  protected def body(ctx: Ctx)(using Async): Unit = c.socket.shutdownOutput(): Unit
}

// ---- Datagram sockets

final class OpenDatagramOp(
    local: IpAddress | Null,
    remote: IpAddress | Null,
    options: SocketOptions,
    into: Slot[? <: DatagramHandle]
) extends RefOp {
  override protected def slots = List(into)
  protected def body(ctx: Ctx)(using Async): Unit = {
    val family = (if local != null then local else remote) match {
      case _: IpAddress.IPv4 => StandardProtocolFamily.INET
      case _                 => StandardProtocolFamily.INET6
    }
    val datagrams = DatagramChannel.open(family)
    try {
      datagrams.configureBlocking(false)
      val kind = if remote == null then SocketOption.Kind.BoundDatagram else SocketOption.Kind.ConnectedDatagram
      SocketOption.opening(options, kind).foreach((o, v) => Options.set(datagrams, o, v))
      if local != null then datagrams.bind(Addresses.jdk(local))
      if remote != null then {
        datagrams.connect(Addresses.jdk(remote))
        into.asInstanceOf[Slot[DatagramHandle]].set(new ConnectedDatagramHandle(datagrams))
      } else into.asInstanceOf[Slot[DatagramHandle]].set(new BoundDatagramHandle(datagrams))
    } catch {
      case e: Throwable =>
        datagrams.close()
        throw e
    }
  }
}

final class ReceiveOp(h: DatagramHandle, buf: ByteBuffer, from: Slot[IpAddress] | Null) extends RefOp with Repeatable {
  override protected def handle = h
  override protected def slots = if from == null then Nil else List(from)
  override protected def check(): Unit =
    if buf.capacity() == 0 then
      throw new IllegalArgumentException(
        s"The buffer has no capacity, so a packet received into it would be dropped: $buf"
      )
  protected def body(ctx: Ctx)(using Async): Unit = {
    h.receives.take(ctx, h, this)
    try {
      // Through a heap buffer of the same capacity, as JDK 21's datagram channels refuse a buffer of a shared arena.
      val packet = ByteBuffer.allocate(buf.capacity())
      var sender = h.datagrams.receive(packet)
      while sender == null do {
        ctx.ready(this, h, h.datagrams, OP_READ)
        sender = h.datagrams.receive(packet)
      }
      buf.clear()
      buf.put(packet.flip())
      buf.flip()
      if from != null then from.set(Addresses.ip(sender))
    } finally h.receives.release(ctx.waiter)
  }
}

final class SendOp(h: DatagramHandle, bufs: Array[ByteBuffer], to: IpAddress | Null) extends RefOp with Repeatable {
  override protected def handle = h
  override protected def check(): Unit =
    if to == null && !bufs.exists(_.hasRemaining()) then
      throw new IllegalArgumentException(
        s"The buffer is empty, and an empty packet is not sent on a connected socket: ${bufs.mkString(", ")}"
      )
  protected def body(ctx: Ctx)(using Async): Unit = {
    h.sends.take(ctx, h, this)
    try {
      // Copied whole into a heap buffer, as JDK 21's datagram channels refuse a buffer of a shared arena.
      val packet = ByteBuffer.allocate(bufs.foldLeft(0)(_ + _.remaining()))
      bufs.foreach(b => packet.put(b.duplicate()))
      packet.flip()
      val size = packet.remaining()
      val target = if to == null then null else Addresses.jdk(to)
      def attempt(): Int = if target == null then h.datagrams.write(packet) else h.datagrams.send(packet, target)
      // An empty packet sent and one with no room both count 0: an empty one is taken as sent.
      while attempt() == 0 && size > 0 do ctx.ready(this, h, h.datagrams, OP_WRITE)
      bufs.foreach(b => b.position(b.limit()))
    } finally h.sends.release(ctx.waiter)
  }
}

// ---- Pipes

final class PipeOp(read: Slot[ReadEnd], write: Slot[WriteEnd]) extends RefOp {
  override protected def slots = List(read, write)
  protected def body(ctx: Ctx)(using Async): Unit = {
    val pipe = Pipe.open()
    pipe.source().configureBlocking(false)
    pipe.sink().configureBlocking(false)
    read.set(new PipeSourceHandle(pipe.source()))
    write.set(new PipeSinkHandle(pipe.sink()))
  }
}

// ---- Files: each call made by the caller, and run to its end once begun

final class OpenFileOp(path: String, write: Boolean, into: Slot[FileHandle]) extends RefOp {
  override protected def slots = List(into)
  protected def body(ctx: Ctx)(using Async): Unit = {
    val p = Path.of(path)
    if !Files.isRegularFile(p) then throw new IOException(s"$path is not a regular file")
    val file = FileChannel.open(p, if write then StandardOpenOption.WRITE else StandardOpenOption.READ)
    try ctx.checkStop(this)
    catch {
      case e: Throwable =>
        file.close()
        throw e
    }
    into.set(new FileHandle(file, path))
  }
}

/** A read at an offset, which goes on from where it left off when performed again. */
final class FileReadOp(h: FileHandle, buf: ByteBuffer, offset: Long) extends RefOp with Repeatable {
  private var at = offset
  override protected def handle = h
  override protected def check(): Unit = if !buf.hasRemaining() then throw full(buf)
  protected def body(ctx: Ctx)(using Async): Unit = {
    val before = buf.position()
    asIo(h.file.read(buf, at))
    settle(h, ctx, this)(buf.position(before))
    at += buf.position() - before
  }
}

/** A write at an offset, which goes on from where it left off when performed again. */
final class FileWriteOp(h: FileHandle, buf: ByteBuffer, offset: Long) extends RefOp with Repeatable {
  private var at = offset
  override protected def handle = h
  protected def body(ctx: Ctx)(using Async): Unit =
    if buf.hasRemaining() then {
      val before = buf.position()
      asIo(h.file.write(buf, at))
      settle(h, ctx, this)(buf.position(before))
      at += buf.position() - before
    }
}

final class SyncOp(h: FileHandle) extends RefOp with Repeatable {
  override protected def handle = h
  protected def body(ctx: Ctx)(using Async): Unit = {
    h.file.force(true)
    settle(h, ctx, this)(())
  }
}

// ---- Work on threads of its own: blocking code, and name lookups

/** Runs `work` on a thread of its own, writing its value with `commit` unless the op was cancelled first. A cancel or
  * the time limit before the commit stops the op: `interrupt` says whether the thread is interrupted and waited for, as
  * blocking code is, or left to run on, its outcome dropped, as a name lookup is.
  */
private[reference] final class OnThread[A](op: AnyRef, work: () => A, commit: A => Unit, interrupt: Boolean) {
  private val state = new AtomicInteger() // 0 running, 1 committed, 2 stopped
  @volatile private var finished = false
  @volatile private var failure: Throwable | Null = null

  def run(ctx: Ctx)(using Async): Unit = {
    val thread = Thread.ofVirtual().unstarted { () =>
      try {
        val value = work()
        if state.compareAndSet(0, 1) then commit(value)
      } catch case e: Throwable => failure = e
      finally {
        finished = true
        ctx.waiter.signal()
      }
    }
    thread.start()
    while {
      ctx.waiter.reset()
      !finished
    } do {
      ctx.park()
      if !finished && (ctx.waiter.cancelled || ctx.expired) && state.compareAndSet(0, 2) then {
        if !interrupt then throw stopped(ctx)
        thread.interrupt()
        while !finished do ctx.waiter.parkUntilSignalled() // returns only once the code has
        throw stopped(ctx)
      }
    }
    if state.get() == 2 then throw stopped(ctx)
    if state.get() != 1 then throw failure.nn
  }

  private def stopped(ctx: Ctx): Throwable =
    if ctx.waiter.cancelled then new CancellationException() else new TimeoutException(s"$op timed out")
}

final class BlockingOp[A <: AnyRef](work: () => A, into: Slot[A] | Null) extends RefOp {
  override protected def slots = if into == null then Nil else List(into)
  protected def body(ctx: Ctx)(using Async): Unit =
    OnThread[A](this, work, v => if into != null then into.set(v), interrupt = true).run(ctx)
}

final class ResolveOp(host: String, into: Slot[List[ResolvedAddress]]) extends RefOp {
  override protected def slots = List(into)
  protected def body(ctx: Ctx)(using Async): Unit = {
    val lookup = () => {
      val found = InetAddress.getAllByName(host).toList.map(Addresses.resolved).distinct
      if found.isEmpty then throw new IOException(s"Failed to resolve $host: it has no IPv4 or IPv6 address")
      found
    }
    OnThread[List[ResolvedAddress]](this, lookup, into.set, interrupt = false).run(ctx)
  }
}
