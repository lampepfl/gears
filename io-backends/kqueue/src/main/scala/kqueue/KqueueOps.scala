package gears.async.io
package kqueue

import gears.async.io.BlockingOps
import gears.async.io.BufferPool
import gears.async.io.FifoOps
import gears.async.io.FileOps
import gears.async.io.IpAddress
import gears.async.io.IpDatagramOps
import gears.async.io.IpStreamOps
import gears.async.io.PipeOps
import gears.async.io.Received
import gears.async.io.Repeatable
import gears.async.io.ResolveOps
import gears.async.io.ResolvedAddress
import gears.async.io.Slot
import gears.async.io.UnixAddress
import gears.async.io.UnixDatagramOps
import gears.async.io.UnixStreamOps
import gears.async.io.internal.BlockingRun
import gears.async.io.internal.Interest
import gears.async.io.internal.NativePool
import gears.async.io.internal.NonBlocking

import java.nio.ByteBuffer

import KqueueBackend.Handle

/** Everything `KqueueReactor` can run: its `Op` member type. Kqueue-specific code may construct the classes below
  * directly; code that only knows the `Reactor` interface builds them through `reactor.ops`. What each keeps while
  * pending is `LoopOp`'s.
  */
sealed trait KqueueOp extends gears.async.io.internal.LoopOp[KqueueOp]

/** Builds the ops `KqueueReactor` understands. */
object KqueueOps
    extends IpStreamOps[KqueueOp, Handle]
    with IpDatagramOps[KqueueOp, Handle]
    with UnixStreamOps[KqueueOp, Handle]
    with UnixDatagramOps[KqueueOp, Handle]
    with PipeOps[KqueueOp, Handle]
    with FifoOps[KqueueOp, Handle]
    with BlockingOps[KqueueOp, Handle]
    with FileOps[KqueueOp, Handle]
    with ResolveOps[KqueueOp, Handle] {
  type Readable = KqueueKinds.Readable
  type Writable = KqueueKinds.Writable
  type Connection = KqueueKinds.Connection
  type Listener = KqueueKinds.Listener
  type BoundDatagram = KqueueKinds.BoundDatagram
  type ConnectedDatagram = KqueueKinds.ConnectedDatagram
  type UnixBoundDatagram = KqueueKinds.UnixBoundDatagram
  type UnixConnectedDatagram = KqueueKinds.UnixConnectedDatagram
  type File = KqueueKinds.File

  def read(handle: Readable, buf: ByteBuffer): ReadIntoBuffer = on(handle)(ReadIntoBuffer(fd(handle), buf))
  def write(handle: Writable, buf: ByteBuffer): WriteFromBuffer = on(handle)(WriteFromBuffer(fd(handle), buf))
  def read(handle: Readable, bufs: Array[ByteBuffer]): ReadIntoBuffers = {
    gears.async.io.internal.Checks.checkBuffers(bufs)
    on(handle)(new ReadIntoBuffers(fd(handle), bufs))
  }
  def write(handle: Writable, bufs: Array[ByteBuffer]): WriteFromBuffers = {
    gears.async.io.internal.Checks.checkBuffers(bufs)
    on(handle)(new WriteFromBuffers(fd(handle), bufs))
  }
  def accept(handle: Listener, into: Slot[Connection]): Accept = on(handle)(Accept(fd(handle), into))
  def acceptAll(handle: Listener, into: Slot[Array[Connection]], max: Int): AcceptAll = {
    require(max > 0, s"max must be positive, not $max")
    on(handle)(new AcceptAll(fd(handle), into, max))
  }
  def connect(address: IpAddress, options: SocketOptions, into: Slot[Connection]): ConnectTo = {
    gears.async.io.internal.Checks.checkOptions(options, supports, SocketOption.Kind.Connection)
    new ConnectTo(gears.async.io.internal.Checks.ip(address), into, options)
  }
  def connect(address: UnixAddress, options: SocketOptions, into: Slot[Connection]): ConnectTo = {
    gears.async.io.internal.Checks.checkOptions(options, supports, SocketOption.Kind.UnixConnection)
    new ConnectTo(gears.async.io.internal.Checks.unix(address), into, options)
  }
  def listen(address: IpAddress, options: SocketOptions, into: Slot[Listener]): KqueueOp = {
    gears.async.io.internal.Checks.checkOptions(options, supports, SocketOption.Kind.Listener)
    new Listen(gears.async.io.internal.Checks.ip(address), into, options)
  }
  def listen(address: UnixAddress, options: SocketOptions, into: Slot[Listener]): KqueueOp = {
    gears.async.io.internal.Checks.checkOptions(options, supports, SocketOption.Kind.UnixListener)
    new Listen(gears.async.io.internal.Checks.unix(address), into, options)
  }
  def bindDatagram(local: IpAddress, options: SocketOptions, into: Slot[BoundDatagram]): KqueueOp = {
    gears.async.io.internal.Checks.checkOptions(options, supports, SocketOption.Kind.BoundDatagram)
    new OpenDatagram(gears.async.io.internal.Checks.ip(local), null, into, KqueueKinds.boundDatagram, options)
  }
  def connectDatagram(
      local: IpAddress | Null,
      remote: IpAddress,
      options: SocketOptions,
      into: Slot[ConnectedDatagram]
  ): KqueueOp = {
    gears.async.io.internal.Checks.checkOptions(options, supports, SocketOption.Kind.ConnectedDatagram)
    val (l, r) = gears.async.io.internal.Checks.ipPair(local, remote)
    new OpenDatagram(l, r, into, KqueueKinds.connectedDatagram, options)
  }
  def bindDatagram(local: UnixAddress, options: SocketOptions, into: Slot[UnixBoundDatagram]): KqueueOp = {
    gears.async.io.internal.Checks.checkOptions(options, supports, SocketOption.Kind.UnixBoundDatagram)
    new OpenDatagram(gears.async.io.internal.Checks.unix(local), null, into, KqueueKinds.unixBoundDatagram, options)
  }
  def connectDatagram(
      local: UnixAddress | Null,
      remote: UnixAddress,
      options: SocketOptions,
      into: Slot[UnixConnectedDatagram]
  ): KqueueOp = {
    gears.async.io.internal.Checks.checkOptions(options, supports, SocketOption.Kind.UnixConnectedDatagram)
    new OpenDatagram(
      if local == null then null else gears.async.io.internal.Checks.unix(local),
      gears.async.io.internal.Checks.unix(remote),
      into,
      KqueueKinds.unixConnectedDatagram,
      options
    )
  }
  def pipe(read: Slot[Readable], write: Slot[Writable]): KqueueOp = new OpenPipe(read, write)
  def openFifoForReading(path: String, into: Slot[Readable]): KqueueOp =
    new OpenFifo(path, write = false, into, KqueueKinds.readable)
  def openFifoForWriting(path: String, into: Slot[Writable]): KqueueOp =
    new OpenFifo(path, write = true, into, KqueueKinds.writable)
  def receive(handle: BoundDatagram, buf: ByteBuffer, from: Slot[IpAddress]): ReceiveFrom =
    on(handle)(new ReceiveFrom(fd(handle), buf, from, unix = false))
  def receive(handle: ConnectedDatagram, buf: ByteBuffer): ReceiveFrom =
    on(handle)(new ReceiveFrom(fd(handle), buf, null, unix = false))
  def send(handle: BoundDatagram, buf: ByteBuffer, to: IpAddress): SendTo =
    on(handle)(new SendTo(fd(handle), buf, gears.async.io.internal.Checks.ip(to)))
  def send(handle: ConnectedDatagram, buf: ByteBuffer): SendTo = on(handle)(new SendTo(fd(handle), buf, null))
  def send(handle: BoundDatagram, bufs: Array[ByteBuffer], to: IpAddress): SendTo = {
    gears.async.io.internal.Checks.checkBuffers(bufs)
    on(handle)(new SendTo(fd(handle), bufs, gears.async.io.internal.Checks.ip(to)))
  }
  def send(handle: ConnectedDatagram, bufs: Array[ByteBuffer]): SendTo = {
    gears.async.io.internal.Checks.checkBuffers(bufs)
    on(handle)(new SendTo(fd(handle), bufs, null))
  }
  @annotation.targetName("receiveUnix")
  def receive(handle: UnixBoundDatagram, buf: ByteBuffer, from: Slot[UnixAddress]): ReceiveFrom =
    on(handle)(new ReceiveFrom(fd(handle), buf, from, unix = true))
  @annotation.targetName("receiveUnixConnected")
  def receive(handle: UnixConnectedDatagram, buf: ByteBuffer): ReceiveFrom =
    on(handle)(new ReceiveFrom(fd(handle), buf, null, unix = true))
  def send(handle: UnixBoundDatagram, buf: ByteBuffer, to: UnixAddress): SendTo =
    on(handle)(new SendTo(fd(handle), buf, gears.async.io.internal.Checks.unix(to)))
  @annotation.targetName("sendUnixConnected")
  def send(handle: UnixConnectedDatagram, buf: ByteBuffer): SendTo = on(handle)(new SendTo(fd(handle), buf, null))
  def send(handle: UnixBoundDatagram, bufs: Array[ByteBuffer], to: UnixAddress): SendTo = {
    gears.async.io.internal.Checks.checkBuffers(bufs)
    on(handle)(new SendTo(fd(handle), bufs, gears.async.io.internal.Checks.unix(to)))
  }
  @annotation.targetName("sendUnixConnectedMany")
  def send(handle: UnixConnectedDatagram, bufs: Array[ByteBuffer]): SendTo = {
    gears.async.io.internal.Checks.checkBuffers(bufs)
    on(handle)(new SendTo(fd(handle), bufs, null))
  }
  def timer(duration: scala.concurrent.duration.FiniteDuration): KqueueTimer = {
    require(duration.length >= 0, s"A timer's time is not negative: $duration")
    new KqueueTimer(duration.toNanos)
  }
  def deadlineTimer(nanoTime: Long): KqueueDeadlineTimer = new KqueueDeadlineTimer(nanoTime)
  def blocking(body: () => Unit): KqueueBlocking = new BlockingTask(body)
  def blocking[A <: AnyRef](body: () => A, into: Slot[A]): KqueueBlocking = new BlockingResultTask(body, into)
  def resolve(host: String, into: Slot[List[ResolvedAddress]]): Resolve = new Resolve(host, into)
  def read(handle: Readable, pool: BufferPool, into: Slot[Received]): PooledRead =
    on(handle)(PooledRead(fd(handle), KqueueOps.native(pool), into))
  def close(handle: Handle): KqueueOp = {
    new Close(handle)
  }
  def shutdownOutput(handle: Connection): KqueueOp = new ShutdownOutput(handle)

  // Socket options: every one that applies, IP or Unix, on macOS and Linux alike, changed at any time but
  // `ReusePort`, set before binding.
  def supports(option: SocketOption[?], kind: SocketOption.Kind): Boolean = option.appliesTo(kind)
  def canChange(option: SocketOption[?], kind: SocketOption.Kind): Boolean =
    supports(option, kind) && option != SocketOption.ReusePort
  def option[V](handle: Handle, option: SocketOption[V]): V =
    onSocket(handle, option)((fd, _) => KqueueHandles.optionOf(fd, option).asInstanceOf[V])
  def setOption[V](handle: Handle, option: SocketOption[V], value: V): Unit = {
    option.check(value)
    onSocket(handle, option) { (fd, kind) =>
      if !canChange(option, kind) then
        throw new IllegalArgumentException(s"$option is given as a socket opens, not changed")
      KqueueHandles.setOption(fd, option, value)
    }
  }

  /** The kind of socket `handle` is, for `option`, which applies to it; else an `IllegalArgumentException`. For a
    * backend built on kqueue's reactors, whose options differ.
    */
  private[io] def kindFor(handle: Handle, option: SocketOption[?]): SocketOption.Kind =
    onSocket(handle, option)((_, kind) => kind)

  /** Runs `body` on `handle`'s descriptor, a socket `option` applies to, with its kind; else an
    * `IllegalArgumentException`.
    */
  private def onSocket[A](handle: Handle, option: SocketOption[?])(body: (Int, SocketOption.Kind) => A): A = {
    KqueueHandles.inUse(handle) {
      val kind = KqueueHandles.socketKind(handle.fd)
      if kind == null then throw new IllegalArgumentException(s"$option applies to sockets, not $handle")
      if !SocketOption.onSocket(option, kind.nn) then throw gears.async.io.internal.Checks.notFor(option, kind.nn)
      body(handle.fd, kind.nn)
    }
  }
  def localAddress(handle: Listener | Connection): IpAddress | UnixAddress =
    KqueueHandles.addressOf(handle, peer = false)
  def peerAddress(handle: Connection): IpAddress | UnixAddress = KqueueHandles.addressOf(handle, peer = true)
  @annotation.targetName("datagramLocalAddress")
  def localAddress(handle: BoundDatagram | ConnectedDatagram): IpAddress = ip(handle, peer = false)
  @annotation.targetName("datagramPeerAddress")
  def peerAddress(handle: ConnectedDatagram): IpAddress = ip(handle, peer = true)
  @annotation.targetName("unixDatagramLocalAddress")
  def localAddress(handle: UnixBoundDatagram | UnixConnectedDatagram): UnixAddress = unix(handle, peer = false)
  @annotation.targetName("unixDatagramPeerAddress")
  def peerAddress(handle: UnixConnectedDatagram): UnixAddress = unix(handle, peer = true)

  private def ip(handle: Handle, peer: Boolean): IpAddress = KqueueHandles.addressOf(handle, peer) match {
    case ip: IpAddress => ip
    case other         => throw new java.io.IOException(s"$handle is not an IP socket: $other")
  }
  private def unix(handle: Handle, peer: Boolean): UnixAddress = KqueueHandles.addressOf(handle, peer) match {
    case unix: UnixAddress => unix
    case other             => throw new java.io.IOException(s"$handle is not a Unix socket: $other")
  }
  def open(path: String, write: Boolean, into: Slot[File]): KqueueOp = new OpenFile(path, write, into)
  def read(handle: File, buf: ByteBuffer, offset: Long): PositionalRead = new PositionalRead(handle, buf, offset)
  def write(handle: File, buf: ByteBuffer, offset: Long): PositionalWrite = new PositionalWrite(handle, buf, offset)
  def sync(handle: File): FileSync = new FileSync(handle)

  // Kqueue's own readiness, outside the interface, whose ops say what is done rather than what is waited for: for code
  // written against kqueue that does its own operations once a handle is ready, as its demos do.

  /** Completes once `handle` is ready for `interest`. The caller then uses the handle without waiting (`readNow`,
    * `writeNow`) until it would block, and submits the op again to wait for the next readiness. Level-triggered:
    * submitted again while the handle is still ready, it completes at once.
    */
  def ready(handle: Handle, interest: Interest): Ready = on(handle)(new Ready(fd(handle), interest))

  /** Reads what is available now into `buf`, without waiting: the bytes read, 0 if nothing is, or -1 at end of stream.
    * Throws `IllegalArgumentException` if `buf` has no room, as `read` fails. Not an op: it runs on the calling thread.
    */
  def readNow(handle: Readable, buf: ByteBuffer): Int = KqueueHandles.readNow(handle, buf)

  /** Writes what fits now from `buf`, without waiting: the bytes written, or -1 if nothing fits; 0 if `buf` has nothing
    * remaining, writing nothing. Not an op.
    */
  def writeNow(handle: Writable, buf: ByteBuffer): Int = KqueueHandles.writeNow(handle, buf)

  private def fd(handle: Handle): Int = KqueueHandles.fd(handle)

  /** `command`, carrying `handle`, which the reactor takes for each use of the descriptor (see `Command.handle`). */
  private def on[C <: Command](handle: Handle)(command: C): C = {
    command.handle = handle
    command
  }

  private def native(pool: BufferPool): NativePool = pool match {
    case native: NativePool => native
    case other              => throw new IllegalArgumentException(s"$other is not a pool of this backend's")
  }
}

/** An op the reactor performs as soon as it is submitted, with no descriptor to wait for: its completion runs on the
  * next turn, with the failure `perform` threw, if any. One-shot.
  */
sealed abstract class KqueueAtOnce extends gears.async.io.internal.LoopAtOnce[KqueueOp] with KqueueOp

/** An op that opens a handle and writes it into a slot (see `LoopOpens`): what it opened, abandoned, is closed by its
  * descriptor, the handle never having reached the caller.
  */
sealed trait Opens extends KqueueOp with gears.async.io.internal.LoopOpens[KqueueOp]

/** An op that opens its handle at once, when submitted (see `KqueueAtOnce`), as kqueue's listeners, datagram sockets,
  * pipes and FIFOs are: `openDescriptor` opens its descriptor, whose handle, of the kind `kind` makes, is written into
  * `into` by the time the op completes.
  */
sealed abstract class OpensAtOnce[K <: Handle](into: Slot[K], kind: Handle => K)
    extends KqueueAtOnce
    with gears.async.io.internal.LoopOpensAtOnce[KqueueOp, K](into)
    with Opens {
  protected def openDescriptor(): Int
  protected final def open(): K = kind(KqueueHandles.handle(openDescriptor()))
  protected final def discard(handle: K): Unit = KqueueHandles.closeDescriptor(handle.fd)
}

/** A stream socket bound and listening on `address` (see `IpStreamOps.listen`). */
final class Listen(
    address: SocketAddress,
    into: Slot[KqueueKinds.Listener],
    options: SocketOptions = SocketOptions.default
) extends OpensAtOnce(into, KqueueKinds.listener) {
  protected def openDescriptor(): Int = KqueueHandles.listenStream(address, options)
}

/** A datagram socket, bound and connected where given (see `IpDatagramOps.connectDatagram`). */
final class OpenDatagram[K <: Handle](
    local: SocketAddress | Null,
    remote: SocketAddress | Null,
    into: Slot[K],
    kind: Handle => K,
    options: SocketOptions = SocketOptions.default
) extends OpensAtOnce(into, kind) {
  protected def openDescriptor(): Int = KqueueHandles.datagramSocket(local, remote, options)
}

/** A FIFO, opened without waiting for its other end (see `FifoOps.openFifoForReading` and `openFifoForWriting`). */
final class OpenFifo[K <: Handle](path: String, write: Boolean, into: Slot[K], kind: Handle => K)
    extends OpensAtOnce(into, kind) {
  gears.async.io.internal.Checks.checkPath(path)
  protected def openDescriptor(): Int = KqueueHandles.fifoDescriptor(path, write)
}

/** A pipe, its read end written into `read` and its write end into `write` (see `PipeOps.pipe`). */
final class OpenPipe(read: Slot[KqueueKinds.Readable], write: Slot[KqueueKinds.Writable])
    extends KqueueAtOnce
    with gears.async.io.internal.LoopOpensPipe[KqueueOp, KqueueKinds.Readable, KqueueKinds.Writable](read, write)
    with Opens {
  protected def open(): (KqueueKinds.Readable, KqueueKinds.Writable) = {
    val (in, out) = KqueueHandles.pipeDescriptors()
    (KqueueKinds.readable(KqueueHandles.handle(in)), KqueueKinds.writable(KqueueHandles.handle(out)))
  }
  protected def discard(end: KqueueKinds.Readable | KqueueKinds.Writable): Unit = KqueueHandles.closeDescriptor(end.fd)
}

/** A stream socket connected to `address` (see `IpStreamOps.connect` and `LoopConnects`): the socket is opened, and its
  * non-blocking connect started, when the op is submitted, and the op then waits for the socket to be writable, as a
  * connect does. Done once connected, after writing the socket into `into`; if the connect fails, or the submission is
  * cancelled first, the socket is closed and `into` left empty.
  */
final class ConnectTo(
    address: SocketAddress,
    into: Slot[KqueueKinds.Connection],
    options: SocketOptions = SocketOptions.default
) extends Command
    with gears.async.io.internal.LoopConnects[KqueueOp, KqueueKinds.Connection](into)
    with Opens {
  def fd: Int = if socket == null then -1 else socket.nn.fd
  def interest: Interest = Interest.Write
  override def tryFirst: Boolean = false // the check passes while the connection is still in progress
  override def registersReads: Boolean = true

  protected def open(): KqueueKinds.Connection =
    KqueueKinds.connection(KqueueHandles.handle(KqueueHandles.connectStream(address, options)))
  protected def discard(socket: KqueueKinds.Connection): Unit = KqueueHandles.closeDescriptor(socket.fd)

  def perform(): Boolean = {
    try gears.async.io.internal.PosixSockets.checkConnect(fd)
    catch {
      case t: Throwable =>
        abandon()
        throw t
    }
    finished()
    true
  }
}

/** Closes a handle (see `Ops.close` and `KqueueBackend.Handle`): at once in meaning, and the descriptor itself at once
  * too if no use of it is in progress, as nearly always, completing as an at-once op does; otherwise once the last use
  * ends, on whose thread the descriptor is closed and this completed, as a promise is from another thread.
  */
final class Close(val handle: Handle) extends KqueuePromise {
  override private[io] def closes: Boolean = true

  /** Starts closing: true if that finished here, the descriptor closed or the close failed, its failure in
    * `atOnceFailure`; false if the last use of the descriptor closes it, completing this.
    */
  private[kqueue] def start(): Boolean =
    try KqueueHandles.closeHandle(handle, failure => if failure == null then signal() else fail(failure.nn))
    catch {
      case t: Throwable =>
        atOnceFailure = t
        true
    }
}

/** Shuts a socket down for writing (see `StreamSocketOps.shutdownOutput`): at once, since the kernel sends what is
  * queued, then the end of the stream, after the call returns.
  */
final class ShutdownOutput(val handle: Handle) extends KqueueAtOnce {
  def perform(): Unit = KqueueHandles.inUse(handle)(gears.async.io.internal.PosixSockets.shutdownOutput(handle.fd))
}

/** What to do with a descriptor once kqueue reports it ready. `perform` runs a non-blocking operation on the reactor
  * thread; its return value is the result, and an exception it throws is the op's failure. Commands are `Repeatable`:
  * waiting for readiness again and performing again is always meaningful.
  */
trait Command extends KqueueOp with Repeatable {
  def fd: Int
  def interest: Interest
  def perform(): Boolean

  override private[io] def claim: gears.async.io.internal.Turns | Null = {
    val h = handle
    if h == null then null else if interest == Interest.Read then h.reader else h.writer
  }

  /** The handle the command was built on, through `KqueueOps`, which the reactor takes around each use of the
    * descriptor, the command's system call and its registration, failing the command if it is closed; null for a
    * command built on a descriptor directly, as kqueue's own code does.
    */
  private[kqueue] var handle: KqueueBackend.Handle | Null = null

  /** Whether the reactor may perform this command as soon as it is submitted, before waiting for readiness. True for
    * operations that simply report would-block when not ready, so trying first only saves the wait when they are.
    */
  def tryFirst: Boolean = true

  /** Why the command cannot be performed as it stands, such as a read into a buffer with no room, or null: then it
    * fails as it is submitted, before readiness is looked at, and is never performed (see `LoopOp.refusal`).
    */
  override def refusal: Throwable | Null = null

  /** After a perform that finished: how many bytes it took, or -1 when that is not a count of bytes. Against the bytes
    * a read event reported available, it tells whether the descriptor is still readable afterwards.
    */
  def transferred: Int = -1

  /** After a perform that finished, with no event to compare against: whether the descriptor may still be ready. */
  def mayStillBeReady: Boolean = true

  /** Whether `perform` returns false only when the descriptor would block, as an operation run until it would block
    * does. Such a read can wait on a persistent edge-triggered filter, which reports only new data. Any other command,
    * one that may return false to mean "not finished yet" with data still waiting, waits on a one-shot, level-triggered
    * filter instead, which fires again while the descriptor stays ready.
    */
  def edgeTriggered: Boolean = false

  /** Whether waiting for this write command should also add the descriptor's persistent read filter, in the same
    * `kevent` call: true for a connect, since a connected socket is then read, and with the filter added before
    * anything can arrive, its first read can wait for an edge instead of trying.
    */
  def registersReads: Boolean = false
}

/** Readiness itself, for `KqueueOps.ready`: done as soon as kqueue reports the descriptor ready, which it does at once
  * while the descriptor stays so, since the command waits on a one-shot, level-triggered filter each time.
  */
final class Ready(val fd: Int, val interest: Interest) extends Command {
  def perform(): Boolean = true
  override def tryFirst: Boolean = false // the wait is the point: only kqueue says the descriptor is ready
}

/** A one-shot timer, `nanos` from each submission, kept in the reactor's heap while pending (`TimerState`). */
sealed class KqueueTimer private[kqueue] (protected val nanos: Long)
    extends gears.async.io.internal.LoopTimer[KqueueOp]
    with KqueueOp {

  /** A timer of `milliseconds`, for kqueue-specific code. */
  def this(milliseconds: Int) = {
    this(milliseconds * 1_000_000L)
    require(milliseconds >= 0, "Timer duration must not be negative")
  }

  /** Its time, in whole milliseconds, for kqueue-specific code; a deadline timer's is negative. */
  def milliseconds: Int = if nanos < 0 then -1 else (nanos / 1_000_000L).toInt
}

/** A timer at a deadline that may move while it is pending (see `Ops.deadlineTimer`). */
final class KqueueDeadlineTimer private[kqueue] (initially: Long) extends KqueueTimer(-1L) with Deadline {
  at = initially
  def deadline: Long = at
  def moveTo(nanoTime: Long): Unit = move(nanoTime)
}

/** The reactor's side of a promise (`LoopPromise`): `KqueueBlocking` completes itself from the blocker pool, and
  * `Close`, when it cannot close at once, from the thread of the descriptor's last use.
  */
sealed abstract class KqueuePromise extends gears.async.io.internal.LoopPromise[KqueueOp] with KqueueOp

/** A promise the reactor completes by running `block` on the blocker pool, with `BlockingRun`'s cancellation: skipped
  * if not started, interrupted if running, and `onCancel` waits for `block` to return. One marked `Repeatable`, such as
  * a file op, is restarted on each submission.
  */
abstract class KqueueBlocking extends KqueuePromise with gears.async.io.internal.LoopBlocking[KqueueOp]

/** A blocking task built from a function, for `BlockingOps.blocking`. */
final class BlockingTask(body: () => Unit) extends KqueueBlocking {
  def block(): Unit = body()
}

/** A blocking task whose function's value is written into `into`, for `BlockingOps.blocking`. */
final class BlockingResultTask[A <: AnyRef](body: () => A, into: Slot[A]) extends KqueueBlocking {
  override private[io] def slotRefusal: Throwable | Null = gears.async.io.internal.Checks.notEmpty(into)
  def block(): Unit = {
    publish(into, body())
  }
}
