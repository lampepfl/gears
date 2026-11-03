package gears.async.io
package reference

import gears.async.Async
import gears.async.AsyncOperations
import gears.async.Future
import gears.async.VThreadScheduler
import gears.async.VThreadSupport

import java.nio.ByteBuffer
import java.nio.channels.NetworkChannel
import scala.annotation.targetName
import scala.concurrent.duration.FiniteDuration

/** The reference backend: the interface's semantics on the JDK's channels, with no regard for speed, as what the
  * testkit's contracts are checked against.
  *
  * A program runs on gears' virtual threads, and each op is performed by the thread performing it: tried at once on its
  * non-blocking channel, and if it would block, waited for on one selector, which one thread polls (`Poller`), and
  * tried again once woken. Timers park the caller. Files are read and written by the caller, blocking code and name
  * lookups on threads of their own. Nothing is tuned: no retries before waiting, no batching, one wake-up per
  * readiness.
  *
  * It has IP stream and datagram sockets, Unix stream sockets, regular files, pipes, name resolution and blocking ops:
  * what the JDK has, which has no Unix datagram sockets and no way to open a FIFO without blocking.
  */
object ReferenceBackend
    extends IpStreams
    with IpDatagrams
    with UnixStreams
    with RegularFiles
    with Pipes
    with Resolving
    with Blocking {
  type Op = RefOp
  type Handle = RefHandle
  type Readable = ReadEnd
  type Writable = WriteEnd
  type Connection = ConnectionHandle
  type Listener = ListenerHandle
  type BoundDatagram = BoundDatagramHandle
  type ConnectedDatagram = ConnectedDatagramHandle
  type File = FileHandle

  val ops: ReferenceOps.type = ReferenceOps

  def pool(bufferSize: Int, buffers: Int): BufferPool = new RefPool(bufferSize, buffers)

  /** Runs `program` on gears' virtual threads, each op performed by the future performing it. */
  private[io] def run[T](program: Io[this.type] ?=> (Async.Spawn, AsyncOperations) ?=> T): T = {
    given VThreadSupport.type = VThreadSupport
    given VThreadScheduler.type = VThreadScheduler
    val io: Io[this.type] = new Io[this.type] {
      val backend: ReferenceBackend.type = ReferenceBackend
      private[io] def performWithin(op: RefOp, timeoutMillis: Int)(using Async): Unit = op.perform(timeoutMillis)
    }
    given AsyncOperations = io
    // `Async.blocking` runs its body on the calling thread, which may be a platform thread; a future's is virtual.
    Async.blocking(Future(program(using io)).await)
  }
}

/** The reference backend's ops, each checked as it is built. */
object ReferenceOps
    extends IpStreamOps[RefOp, RefHandle]
    with UnixStreamOps[RefOp, RefHandle]
    with IpDatagramOps[RefOp, RefHandle]
    with FileOps[RefOp, RefHandle]
    with PipeOps[RefOp, RefHandle]
    with ResolveOps[RefOp, RefHandle]
    with BlockingOps[RefOp, RefHandle] {
  import SocketOption.Kind

  type Readable = ReadEnd
  type Writable = WriteEnd
  type Connection = ConnectionHandle
  type Listener = ListenerHandle
  type BoundDatagram = BoundDatagramHandle
  type ConnectedDatagram = ConnectedDatagramHandle
  type File = FileHandle

  private def fillable(buf: ByteBuffer): ByteBuffer = {
    if buf.isReadOnly() then throw new IllegalArgumentException(s"A read-only buffer cannot be read into: $buf")
    buf
  }

  private def several(bufs: Array[ByteBuffer]): Array[ByteBuffer] = {
    if bufs.isEmpty then throw new IllegalArgumentException("A vectored op takes at least one buffer")
    bufs
  }

  private def offset(at: Long): Long = {
    if at < 0 then throw new IllegalArgumentException(s"A file offset must not be negative: $at")
    at
  }

  // ---- Every backend's

  def read(handle: ReadEnd, buf: ByteBuffer): RefOp & Repeatable = ReadOp(handle, fillable(buf))

  def write(handle: WriteEnd, buf: ByteBuffer): RefOp & Repeatable = WriteOp(handle, buf)

  def read(handle: ReadEnd, bufs: Array[ByteBuffer]): RefOp & Repeatable = {
    several(bufs).foreach(fillable)
    ScatterOp(handle, bufs)
  }

  def write(handle: WriteEnd, bufs: Array[ByteBuffer]): RefOp & Repeatable = GatherOp(handle, several(bufs))

  def timer(duration: FiniteDuration): RefOp & Repeatable = {
    if duration.length < 0 then throw new IllegalArgumentException(s"A timer's time is not negative: $duration")
    TimerOp(duration.toNanos)
  }

  def deadlineTimer(nanoTime: Long): RefOp & Repeatable & Deadline = DeadlineOp(nanoTime)

  def read(handle: ReadEnd, pool: BufferPool, into: Slot[Received]): RefOp & Repeatable = pool match {
    case ours: RefPool => PooledReadOp(handle, ours, into)
    case other         => throw new IllegalArgumentException(s"Not a pool of this backend's: $other")
  }

  def close(handle: RefHandle): RefOp = CloseOp(handle)

  // ---- Stream sockets

  def accept(handle: ListenerHandle, into: Slot[ConnectionHandle]): RefOp & Repeatable = AcceptOp(handle, into)

  def acceptAll(handle: ListenerHandle, into: Slot[Array[ConnectionHandle]], max: Int): RefOp & Repeatable = {
    if max <= 0 then throw new IllegalArgumentException(s"acceptAll takes at least one connection, not $max")
    AcceptAllOp(handle, into, max)
  }

  def shutdownOutput(handle: ConnectionHandle): RefOp = ShutdownOutputOp(handle)

  def localAddress(handle: ListenerHandle | ConnectionHandle): IpAddress | UnixAddress = {
    handle.checkOpen()
    handle match {
      case l: ListenerHandle =>
        if l.unix then Addresses.unix(l.server.getLocalAddress()) else Addresses.ip(l.server.getLocalAddress())
      case c: ConnectionHandle =>
        if c.unix then Addresses.unix(c.socket.getLocalAddress()) else Addresses.ip(c.socket.getLocalAddress())
    }
  }

  def peerAddress(handle: ConnectionHandle): IpAddress | UnixAddress = {
    handle.checkOpen()
    if handle.unix then Addresses.unix(handle.socket.getRemoteAddress())
    else Addresses.ip(handle.socket.getRemoteAddress())
  }

  def connect(address: IpAddress, options: SocketOptions, into: Slot[ConnectionHandle]): RefOp = {
    val checked = Addresses.checked(address)
    Options.check(options, Kind.Connection)
    ConnectOp(checked, options, into)
  }

  def listen(address: IpAddress, options: SocketOptions, into: Slot[ListenerHandle]): RefOp = {
    val checked = Addresses.checked(address)
    Options.check(options, Kind.Listener)
    ListenOp(checked, options, into)
  }

  def connect(address: UnixAddress, options: SocketOptions, into: Slot[ConnectionHandle]): RefOp = {
    val checked = Addresses.checked(address)
    Options.check(options, Kind.UnixConnection)
    ConnectOp(checked, options, into)
  }

  def listen(address: UnixAddress, options: SocketOptions, into: Slot[ListenerHandle]): RefOp = {
    val checked = Addresses.checked(address)
    Options.check(options, Kind.UnixListener)
    ListenOp(checked, options, into)
  }

  // ---- IP datagram sockets

  def bindDatagram(local: IpAddress, options: SocketOptions, into: Slot[BoundDatagramHandle]): RefOp = {
    val checked = Addresses.checked(local)
    Options.check(options, Kind.BoundDatagram)
    OpenDatagramOp(checked, null, options, into)
  }

  def connectDatagram(
      local: IpAddress | Null,
      remote: IpAddress,
      options: SocketOptions,
      into: Slot[ConnectedDatagramHandle]
  ): RefOp = {
    val (l, r) = Addresses.pair(local, remote)
    Options.check(options, Kind.ConnectedDatagram)
    OpenDatagramOp(l, r, options, into)
  }

  def receive(handle: BoundDatagramHandle, buf: ByteBuffer, from: Slot[IpAddress]): RefOp & Repeatable =
    ReceiveOp(handle, fillable(buf), from)

  def receive(handle: ConnectedDatagramHandle, buf: ByteBuffer): RefOp & Repeatable =
    ReceiveOp(handle, fillable(buf), null)

  def send(handle: BoundDatagramHandle, buf: ByteBuffer, to: IpAddress): RefOp & Repeatable =
    SendOp(handle, Array(buf), Addresses.checked(to))

  def send(handle: ConnectedDatagramHandle, buf: ByteBuffer): RefOp & Repeatable = SendOp(handle, Array(buf), null)

  def send(handle: BoundDatagramHandle, bufs: Array[ByteBuffer], to: IpAddress): RefOp & Repeatable =
    SendOp(handle, several(bufs), Addresses.checked(to))

  def send(handle: ConnectedDatagramHandle, bufs: Array[ByteBuffer]): RefOp & Repeatable =
    SendOp(handle, several(bufs), null)

  @targetName("datagramLocalAddress")
  def localAddress(handle: BoundDatagramHandle | ConnectedDatagramHandle): IpAddress = {
    handle.checkOpen()
    Addresses.ip(handle.datagrams.getLocalAddress())
  }

  @targetName("datagramPeerAddress")
  def peerAddress(handle: ConnectedDatagramHandle): IpAddress = {
    handle.checkOpen()
    Addresses.ip(handle.datagrams.getRemoteAddress())
  }

  // ---- Socket options

  def supports(option: SocketOption[?], kind: Kind): Boolean = Options.supports(option, kind)

  def canChange(option: SocketOption[?], kind: Kind): Boolean = Options.canChange(option, kind)

  /** The socket `handle` names, and its kind. */
  private def socket(handle: RefHandle): (NetworkChannel, Kind) = handle match {
    case c: ConnectionHandle        => (c.socket, if c.unix then Kind.UnixConnection else Kind.Connection)
    case l: ListenerHandle          => (l.server, if l.unix then Kind.UnixListener else Kind.Listener)
    case b: BoundDatagramHandle     => (b.datagrams, Kind.BoundDatagram)
    case c: ConnectedDatagramHandle => (c.datagrams, Kind.ConnectedDatagram)
    case other                      => throw new IllegalArgumentException(s"$other is not a socket")
  }

  def option[V](handle: RefHandle, option: SocketOption[V]): V = {
    val (channel, kind) = socket(handle)
    if !Options.onOpen(option, kind) then
      throw new IllegalArgumentException(s"$option is not read on a ${kind.description} by this backend")
    handle.checkOpen()
    Options.get(channel, option).asInstanceOf[V]
  }

  def setOption[V](handle: RefHandle, option: SocketOption[V], value: V): Unit = {
    val (channel, kind) = socket(handle)
    if !Options.canChange(option, kind) then
      throw new IllegalArgumentException(s"$option is not changed on an open ${kind.description} by this backend")
    option.check(value)
    handle.checkOpen()
    Options.set(channel, option, value)
  }

  // ---- Files, pipes, names and blocking code

  def open(path: String, write: Boolean, into: Slot[FileHandle]): RefOp = {
    Addresses.checkPath(path)
    OpenFileOp(path, write, into)
  }

  def read(handle: FileHandle, buf: ByteBuffer, at: Long): RefOp & Repeatable =
    FileReadOp(handle, fillable(buf), offset(at))

  def write(handle: FileHandle, buf: ByteBuffer, at: Long): RefOp & Repeatable = FileWriteOp(handle, buf, offset(at))

  def sync(handle: FileHandle): RefOp & Repeatable = SyncOp(handle)

  def pipe(read: Slot[ReadEnd], write: Slot[WriteEnd]): RefOp = PipeOp(read, write)

  def resolve(host: String, into: Slot[List[ResolvedAddress]]): RefOp = {
    if host.isEmpty then throw new IllegalArgumentException("An empty host name names no host")
    Addresses.checkPath(host)
    ResolveOp(host, into)
  }

  def blocking(body: () => Unit): RefOp = BlockingOp[AnyRef](() => { body(); "" }, null)

  def blocking[A <: AnyRef](body: () => A, into: Slot[A]): RefOp = BlockingOp(body, into)
}
