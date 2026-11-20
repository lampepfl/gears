package gears.async.io
package uring

import gears.async.Async
import gears.async.io.posix.Descriptors
import gears.async.io.posix.PosixResolver
import gears.async.io.posix.PosixSocketOptions
import gears.async.io.posix.PosixSockets
import gears.async.io.posix.SocketAddress
import gears.async.io.posix.UnixBinding
import gears.async.io.shareable.AbstractBlockingOp
import gears.async.io.shareable.AbstractBlockingValueOp
import gears.async.io.shareable.AbstractHandleCallOp
import gears.async.io.shareable.AbstractMakeDeadlineOp
import gears.async.io.shareable.AbstractMakePipeOp
import gears.async.io.shareable.AbstractMakeTimerOp
import gears.async.io.shareable.AbstractOpenFileOp
import gears.async.io.shareable.AbstractOpenOp
import gears.async.io.shareable.AbstractResolveOp
import gears.async.io.shareable.BackendOp
import gears.async.io.shareable.Checks as Shared
import gears.async.io.shareable.HandleOwner
import gears.async.io.shareable.IpAddresses
import gears.async.io.shareable.PoolRun
import gears.async.io.shareable.Starvation

import java.nio.ByteBuffer
import scala.concurrent.duration.FiniteDuration
import scala.scalanative.posix.fcntl

/** The io_uring backend (Linux 6.0 or later, Scala Native), through liburing, on io-poller's pool: each worker's poller
  * is a ring of its own, which its commands' entries go to, and blocking code, name lookups and opening files are done
  * off the workers, as for every pool backend.
  */
object UringBackend extends Backend {
  type Interface = UringOps.type

  def pool(bufferSize: Int, buffers: Int): BufferPool = new NativePool(bufferSize, buffers)

  private[io] def run[T](program: Io[Interface] ?=> Async.Spawn ?=> T): T =
    runOn(defaultWorkers, Starvation.printWarning)(program)

  private[io] def runOn[T](workers: Int, onStarvation: Starvation.Handler = Starvation.printWarning)(
      program: Io[Interface] ?=> Async.Spawn ?=> T
  ): T = {
    gears.async.io.posix.FileLimit.raised: Unit
    PoolRun(UringScheduler(workers, onStarvation))(program)
  }

  /** `GEARS_IO_URING_WORKERS`, or one for each processor. */
  def defaultWorkers: Int =
    sys.env
      .get("GEARS_IO_URING_WORKERS")
      .flatMap(_.toIntOption)
      .filter(_ > 0)
      .getOrElse(Runtime.getRuntime.availableProcessors)
}

// ---- the kinds of op besides the ring's commands

sealed trait UringImmediateOp extends UringOp, BackendOp.Immediate[UringWorker]
sealed trait UringOffThreadOp extends UringOp, BackendOp.OffThread[UringWorker]
sealed trait UringTimedOp extends UringOp, BackendOp.Timed[UringWorker]

private[uring] final class UringTimer(duration: FiniteDuration) extends AbstractMakeTimerOp(duration), UringTimedOp

private[uring] final class UringDeadline(initial: Long) extends AbstractMakeDeadlineOp(initial), UringTimedOp

private[uring] final class MakePipe(read: Slot[UringReadable], write: Slot[UringWritable])
    extends AbstractMakePipeOp[UringReadable, UringWritable](read, write),
      UringImmediateOp {
  def _pipe(owner: HandleOwner): (UringReadable, UringWritable) = {
    val (r, w) = Descriptors.pipe()
    try {
      UringHandle.closeOnExec(r)
      UringHandle.closeOnExec(w)
      (new PipeSource(r, owner), new PipeSink(w, owner))
    } catch {
      case failure: Throwable =>
        Descriptors.closeHandle(r)
        Descriptors.closeHandle(w)
        throw failure
    }
  }
}

private[uring] final class Listen(address: SocketAddress, options: SocketOptions, into: Slot[ListenerHandle])
    extends AbstractOpenOp(into),
      UringImmediateOp {
  def _open(owner: HandleOwner): ListenerHandle = UringSockets.listen(address, options, owner)
  override def toString: String = s"listen on $address"
}

private[uring] final class OpenDatagram[D <: DatagramHandle](
    local: SocketAddress | Null,
    remote: SocketAddress | Null,
    options: SocketOptions,
    kind: SocketOption.Kind,
    into: Slot[D]
)(make: (Int, Boolean, UnixBinding | Null, HandleOwner) => D)
    extends AbstractOpenOp(into),
      UringImmediateOp {
  def _open(owner: HandleOwner): D =
    UringSockets.openDatagram(local, remote, options, kind, owner) { (fd, ipv6, bound, owner) =>
      UringHandle.closeOnExec(fd)
      make(fd, ipv6, bound, owner)
    }
}

private[uring] final class ShutdownOutput(connection: ConnectionHandle)
    extends AbstractHandleCallOp(connection),
      UringImmediateOp {
  def _call(connection: ConnectionHandle): Unit = PosixSockets.shutdownOutput(connection.fd)
}

private[uring] final class OpenFifo[K <: UringHandle](path: String, write: Boolean, into: Slot[K])(
    make: (Int, HandleOwner) => K
) extends AbstractOpenOp(into),
      UringImmediateOp {
  Shared.checkPath(path)
  def _open(owner: HandleOwner): K = {
    val fd = Descriptors.openFifo(path, write)
    try {
      UringHandle.closeOnExec(fd)
      make(fd, owner)
    } catch {
      case failure: Throwable =>
        Descriptors.closeHandle(fd)
        throw failure
    }
  }
}

private[uring] final class RunBlocking(body: () => Unit) extends AbstractBlockingOp(body), UringOffThreadOp

private[uring] final class RunBlockingValue[A <: AnyRef](body: () => A, into: Slot[A])
    extends AbstractBlockingValueOp(body, into),
      UringOffThreadOp

private[uring] final class Resolve(host: String, into: Slot[List[IpAddress]])
    extends AbstractResolveOp(host, into),
      UringOffThreadOp {
  def _lookup(host: String): List[IpAddress] = PosixResolver.lookup(host) match {
    case Right(addresses) => addresses
    case Left(error)      => throw new java.io.IOException(s"Failed to resolve $host: $error")
  }
}

// - blocking again once open: io_uring reads uncached data of a blocking file in a kernel worker, where a
//   non-blocking one reports that it would block
private[uring] final class OpenFile(path: String, write: Boolean, into: Slot[FileHandle])
    extends AbstractOpenFileOp(path, write, into),
      UringOffThreadOp {
  def _openFile(path: String, write: Boolean, owner: HandleOwner): FileHandle = {
    val fd = Descriptors.openFile(path, write)
    try {
      UringHandle.closeOnExec(fd)
      val flags = fcntl.fcntl(fd, fcntl.F_GETFL, 0)
      if flags < 0 || fcntl.fcntl(fd, fcntl.F_SETFL, flags & ~fcntl.O_NONBLOCK) < 0 then
        throw Results.failed(s"Failed to make $path blocking")
      new FileHandle(fd, path, owner)
    } catch {
      case failure: Throwable =>
        Descriptors.closeHandle(fd)
        throw failure
    }
  }
}

// ---- the interface

object UringOps
    extends IpStreams
    with IpDatagrams
    with UnixStreams
    with UnixDatagrams
    with RegularFiles
    with Pipes
    with Fifos
    with Resolving
    with Blocking {
  import scala.annotation.targetName
  type Op = UringOp
  type Handle = UringHandle
  type Readable = UringReadable
  type Writable = UringWritable
  type Connection = ConnectionHandle
  type Listener = ListenerHandle
  type BoundDatagram = BoundDatagramHandle
  type ConnectedDatagram = ConnectedDatagramHandle
  type UnixBoundDatagram = UnixBoundDatagramHandle
  type UnixConnectedDatagram = UnixConnectedDatagramHandle
  type File = FileHandle

  def timer(duration: FiniteDuration): UringOp & Repeatable = {
    if duration.length < 0 then throw new IllegalArgumentException(s"A timer's time is not negative: $duration")
    UringTimer(duration)
  }

  def deadlineTimer(nanoTime: Long): UringOp & Repeatable & Deadline = UringDeadline(nanoTime)

  def close(handle: UringHandle): UringOp = CloseHandle(handle)

  // ---- streams

  def read(handle: UringReadable, buf: ByteBuffer): UringOp & Repeatable = ReadIntoBuffer(handle, buf)
  def write(handle: UringWritable, buf: ByteBuffer): UringOp & Repeatable = WriteFromBuffer(handle, buf)
  def read(handle: UringReadable, bufs: Array[ByteBuffer]): UringOp & Repeatable = ReadIntoBuffers(handle, bufs)
  def write(handle: UringWritable, bufs: Array[ByteBuffer]): UringOp & Repeatable = WriteFromBuffers(handle, bufs)

  def read(handle: UringReadable, pool: BufferPool, into: Slot[Received]): UringOp & Repeatable = pool match {
    case ours: NativePool => PooledRead(handle, ours, into)
    case other            => throw new IllegalArgumentException(s"Not a pool of this backend's: $other")
  }

  def pipe(read: Slot[UringReadable], write: Slot[UringWritable]): UringOp = MakePipe(read, write)

  // ---- stream sockets

  def connect(address: IpSocketAddress, options: SocketOptions, into: Slot[ConnectionHandle]): UringOp = {
    Shared.checkOptions(options, supports, SocketOption.Kind.Connection)
    Connect(address, options, into)
  }

  def connect(address: UnixAddress, options: SocketOptions, into: Slot[ConnectionHandle]): UringOp = {
    Shared.checkOptions(options, supports, SocketOption.Kind.UnixConnection)
    Connect(Shared.unix(address), options, into)
  }

  def listen(address: IpSocketAddress, options: SocketOptions, into: Slot[ListenerHandle]): UringOp = {
    Shared.checkOptions(options, supports, SocketOption.Kind.Listener)
    Listen(address, options, into)
  }

  def listen(address: UnixAddress, options: SocketOptions, into: Slot[ListenerHandle]): UringOp = {
    Shared.checkOptions(options, supports, SocketOption.Kind.UnixListener)
    Listen(Shared.unix(address), options, into)
  }

  def accept(handle: ListenerHandle, into: Slot[ConnectionHandle]): UringOp & Repeatable = Accept(handle, into)

  def acceptAll(handle: ListenerHandle, into: Slot[Array[ConnectionHandle]], max: Int): UringOp & Repeatable = {
    if max <= 0 then throw new IllegalArgumentException(s"acceptAll takes at least one connection, not $max")
    AcceptAll(handle, into, max)
  }

  def shutdownOutput(handle: ConnectionHandle): UringOp = ShutdownOutput(handle)

  def localAddress(handle: ListenerHandle | ConnectionHandle): IpSocketAddress | UnixAddress =
    UringSockets.addressOf(handle, peer = false)

  def peerAddress(handle: ConnectionHandle): IpSocketAddress | UnixAddress =
    UringSockets.addressOf(handle, peer = true)

  // ---- socket options

  def supports(option: SocketOption[?], kind: SocketOption.Kind): Boolean = option.appliesTo(kind)

  def canChange(option: SocketOption[?], kind: SocketOption.Kind): Boolean =
    SocketOption.onSocket(option, kind) && option != SocketOption.ReusePort

  def option[V](handle: UringHandle, option: SocketOption[V]): V =
    onSocket(handle, option)(socket => PosixSocketOptions.get(socket.fd, socket.ipv6, option).asInstanceOf[V])

  def setOption[V](handle: UringHandle, option: SocketOption[V], value: V): Unit = {
    option.check(value)
    onSocket(handle, option) { socket =>
      if !canChange(option, socket.kind) then
        throw new IllegalArgumentException(s"$option is given as a socket opens, not changed")
      PosixSocketOptions.set(socket.fd, socket.ipv6, option, value)
    }
  }

  private def onSocket[A](handle: UringHandle, option: SocketOption[?])(
      body: gears.async.io.posix.PosixSocket => A
  ): A = handle match {
    case socket: gears.async.io.posix.PosixSocket =>
      socket.claimWork()
      try {
        if !SocketOption.onSocket(option, socket.kind) then throw Shared.notFor(option, socket.kind)
        body(socket)
      } finally socket.releaseWork()
    case _ => throw new IllegalArgumentException(s"$option applies to sockets, not $handle")
  }

  // ---- IP datagram sockets

  def bindDatagram(local: IpSocketAddress, options: SocketOptions, into: Slot[BoundDatagramHandle]): UringOp = {
    val kind = SocketOption.Kind.BoundDatagram
    Shared.checkOptions(options, supports, kind)
    OpenDatagram(local, null, options, kind, into)((fd, ipv6, _, owner) => new BoundDatagramHandle(fd, ipv6, owner))
  }

  def connectDatagram(
      local: IpSocketAddress | Null,
      remote: IpSocketAddress,
      options: SocketOptions,
      into: Slot[ConnectedDatagramHandle]
  ): UringOp = {
    val kind = SocketOption.Kind.ConnectedDatagram
    Shared.checkOptions(options, supports, kind)
    val (from, to) = IpAddresses.pair(local, remote)
    OpenDatagram(from, to, options, kind, into)((fd, ipv6, _, owner) => new ConnectedDatagramHandle(fd, ipv6, owner))
  }

  def receive(handle: BoundDatagramHandle, buf: ByteBuffer, from: Slot[IpSocketAddress]): UringOp & Repeatable =
    ReceiveFrom(handle, buf, from)

  def receive(handle: ConnectedDatagramHandle, buf: ByteBuffer): UringOp & Repeatable = ReceiveFrom(handle, buf, null)

  def send(handle: BoundDatagramHandle, buf: ByteBuffer, to: IpSocketAddress): UringOp & Repeatable =
    SendTo(handle, Array(buf), to)

  def send(handle: ConnectedDatagramHandle, buf: ByteBuffer): UringOp & Repeatable = SendTo(handle, Array(buf), null)

  def send(handle: BoundDatagramHandle, bufs: Array[ByteBuffer], to: IpSocketAddress): UringOp & Repeatable =
    SendTo(handle, bufs, to)

  def send(handle: ConnectedDatagramHandle, bufs: Array[ByteBuffer]): UringOp & Repeatable = SendTo(handle, bufs, null)

  @targetName("datagramLocalAddress")
  def localAddress(handle: BoundDatagramHandle | ConnectedDatagramHandle): IpSocketAddress =
    UringSockets.addressOf(handle, peer = false).asInstanceOf[IpSocketAddress]

  @targetName("datagramPeerAddress")
  def peerAddress(handle: ConnectedDatagramHandle): IpSocketAddress =
    UringSockets.addressOf(handle, peer = true).asInstanceOf[IpSocketAddress]

  // ---- Unix datagram sockets

  def bindDatagram(local: UnixAddress, options: SocketOptions, into: Slot[UnixBoundDatagramHandle]): UringOp = {
    val kind = SocketOption.Kind.UnixBoundDatagram
    Shared.checkOptions(options, supports, kind)
    OpenDatagram(Shared.unix(local), null, options, kind, into)((fd, _, bound, owner) =>
      new UnixBoundDatagramHandle(fd, bound, owner)
    )
  }

  def connectDatagram(
      local: UnixAddress | Null,
      remote: UnixAddress,
      options: SocketOptions,
      into: Slot[UnixConnectedDatagramHandle]
  ): UringOp = {
    val kind = SocketOption.Kind.UnixConnectedDatagram
    Shared.checkOptions(options, supports, kind)
    val from = if local == null then null else Shared.unix(local)
    OpenDatagram(from, Shared.unix(remote), options, kind, into)((fd, _, bound, owner) =>
      new UnixConnectedDatagramHandle(fd, bound, owner)
    )
  }

  @targetName("receiveUnix")
  def receive(handle: UnixBoundDatagramHandle, buf: ByteBuffer, from: Slot[UnixAddress]): UringOp & Repeatable =
    ReceiveFrom(handle, buf, from)

  @targetName("receiveUnixConnected")
  def receive(handle: UnixConnectedDatagramHandle, buf: ByteBuffer): UringOp & Repeatable =
    ReceiveFrom(handle, buf, null)

  def send(handle: UnixBoundDatagramHandle, buf: ByteBuffer, to: UnixAddress): UringOp & Repeatable =
    SendTo(handle, Array(buf), Shared.unix(to))

  @targetName("sendUnixConnected")
  def send(handle: UnixConnectedDatagramHandle, buf: ByteBuffer): UringOp & Repeatable =
    SendTo(handle, Array(buf), null)

  def send(handle: UnixBoundDatagramHandle, bufs: Array[ByteBuffer], to: UnixAddress): UringOp & Repeatable =
    SendTo(handle, bufs, Shared.unix(to))

  @targetName("sendUnixConnectedMany")
  def send(handle: UnixConnectedDatagramHandle, bufs: Array[ByteBuffer]): UringOp & Repeatable =
    SendTo(handle, bufs, null)

  @targetName("unixDatagramLocalAddress")
  def localAddress(handle: UnixBoundDatagramHandle | UnixConnectedDatagramHandle): UnixAddress =
    UringSockets.addressOf(handle, peer = false).asInstanceOf[UnixAddress]

  @targetName("unixDatagramPeerAddress")
  def peerAddress(handle: UnixConnectedDatagramHandle): UnixAddress =
    UringSockets.addressOf(handle, peer = true).asInstanceOf[UnixAddress]

  // ---- files, FIFOs, names and blocking code

  def open(path: String, write: Boolean, into: Slot[FileHandle]): UringOp = OpenFile(path, write, into)

  def read(handle: FileHandle, buf: ByteBuffer, at: Long): UringOp & Repeatable = {
    Shared.checkOffset(at)
    PositionalRead(handle, buf, at)
  }

  def write(handle: FileHandle, buf: ByteBuffer, at: Long): UringOp & Repeatable = {
    Shared.checkOffset(at)
    PositionalWrite(handle, buf, at)
  }

  def sync(handle: FileHandle): UringOp & Repeatable = FileSync(handle)

  def openFifoForReading(path: String, into: Slot[UringReadable]): UringOp =
    OpenFifo[UringReadable](path, write = false, into)(new FifoSource(_, _))

  def openFifoForWriting(path: String, into: Slot[UringWritable]): UringOp =
    OpenFifo[UringWritable](path, write = true, into)(new FifoSink(_, _))

  def resolve(host: String, into: Slot[List[IpAddress]]): UringOp = Resolve(host, into)

  def blocking(body: () => Unit): UringOp = RunBlocking(body)

  def blocking[A <: AnyRef](body: () => A, into: Slot[A]): UringOp = RunBlockingValue(body, into)
}
