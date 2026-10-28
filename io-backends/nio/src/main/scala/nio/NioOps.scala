package gears.async.io
package nio

import gears.async.io.AddressFamily
import gears.async.io.BlockingOps
import gears.async.io.BufferPool
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
import gears.async.io.UnixStreamOps
import gears.async.io.internal.Blockers
import gears.async.io.internal.BlockingRun
import gears.async.io.internal.Interest

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

import NioCapabilities.Handle

/** Everything `NioReactor` can run: its `Op` member type. NIO-specific code may construct the classes below directly;
  * code that only knows the `Reactor` interface builds them through `reactor.ops`. What each keeps while pending is
  * `LoopOp`'s.
  */
sealed trait NioOp extends gears.async.io.internal.LoopOp[NioOp]

/** Builds the ops `NioReactor` understands. */
object NioOps
    extends IpStreamOps[NioOp, Handle]
    with IpDatagramOps[NioOp, Handle]
    with UnixStreamOps[NioOp, Handle]
    with PipeOps[NioOp, Handle]
    with BlockingOps[NioOp, Handle]
    with FileOps[NioOp, Handle]
    with ResolveOps[NioOp, Handle] {
  type Readable = NioKinds.Readable
  type Writable = NioKinds.Writable
  type Connection = NioKinds.Connection
  type Listener = NioKinds.Listener
  type BoundDatagram = NioKinds.BoundDatagram
  type ConnectedDatagram = NioKinds.ConnectedDatagram
  type UnixBoundDatagram = NioKinds.UnixBoundDatagram
  type UnixConnectedDatagram = NioKinds.UnixConnectedDatagram
  type File = NioKinds.File

  def read(handle: Readable, buf: ByteBuffer): Read = new Read(handle, buf)
  def write(handle: Writable, buf: ByteBuffer): Write = new Write(handle, buf)
  def read(handle: Readable, bufs: Array[ByteBuffer]): ReadMany = {
    gears.async.io.internal.Checks.checkBuffers(bufs)
    new ReadMany(handle, bufs)
  }
  def write(handle: Writable, bufs: Array[ByteBuffer]): WriteMany = {
    gears.async.io.internal.Checks.checkBuffers(bufs)
    new WriteMany(handle, bufs)
  }
  def accept(handle: Listener, into: Slot[Connection]): Accept = new Accept(handle, into)
  def acceptAll(handle: Listener, into: Slot[Array[Connection]], max: Int): AcceptAll = {
    require(max > 0, s"max must be positive, not $max")
    new AcceptAll(handle, into, max)
  }
  def connect(address: IpAddress, options: SocketOptions, into: Slot[Connection]): ConnectTo = {
    gears.async.io.internal.Checks.checkOptions(options, supports, SocketOption.Kind.Connection)
    new ConnectTo(gears.async.io.internal.Checks.ip(address), into, options)
  }
  def connect(address: UnixAddress, options: SocketOptions, into: Slot[Connection]): ConnectTo = {
    gears.async.io.internal.Checks.checkOptions(options, supports, SocketOption.Kind.UnixConnection)
    new ConnectTo(gears.async.io.internal.Checks.unix(address), into, options)
  }
  def listen(address: IpAddress, options: SocketOptions, into: Slot[Listener]): NioOp = {
    gears.async.io.internal.Checks.checkOptions(options, supports, SocketOption.Kind.Listener)
    new Listen(gears.async.io.internal.Checks.ip(address), into, options)
  }
  def listen(address: UnixAddress, options: SocketOptions, into: Slot[Listener]): NioOp = {
    gears.async.io.internal.Checks.checkOptions(options, supports, SocketOption.Kind.UnixListener)
    new Listen(gears.async.io.internal.Checks.unix(address), into, options)
  }
  def bindDatagram(local: IpAddress, options: SocketOptions, into: Slot[BoundDatagram]): NioOp = {
    gears.async.io.internal.Checks.checkOptions(options, supports, SocketOption.Kind.BoundDatagram)
    new OpenDatagram(gears.async.io.internal.Checks.ip(local), null, into, NioKinds.boundDatagram, options)
  }
  def connectDatagram(
      local: IpAddress | Null,
      remote: IpAddress,
      options: SocketOptions,
      into: Slot[ConnectedDatagram]
  ): NioOp = {
    gears.async.io.internal.Checks.checkOptions(options, supports, SocketOption.Kind.ConnectedDatagram)
    val (l, r) = gears.async.io.internal.Checks.ipPair(local, remote)
    new OpenDatagram(l, r, into, NioKinds.connectedDatagram, options)
  }

  // Socket options: each that applies, where the JDK has it here, changed at any time but `ReusePort`, set before
  // binding; a Unix socket's buffer sizes too.
  def supports(option: SocketOption[?], kind: SocketOption.Kind): Boolean = option.appliesTo(kind) && (option match {
    case SocketOption.KeepAlive    => NioHandles.keepAliveTimings
    case SocketOption.DontFragment => NioHandles.dontFragment
    case SocketOption.ReusePort    => NioHandles.reusePort
    case _                         => true
  })
  def canChange(option: SocketOption[?], kind: SocketOption.Kind): Boolean =
    supports(option, kind) && option != SocketOption.ReusePort
  def option[V](handle: Handle, option: SocketOption[V]): V = {
    val (channel, _) = networkChannel(handle, option)
    NioHandles.optionOf(channel, option).asInstanceOf[V]
  }
  def setOption[V](handle: Handle, option: SocketOption[V], value: V): Unit = {
    option.check(value)
    val (channel, kind) = networkChannel(handle, option)
    if !canChange(option, kind) then
      throw new IllegalArgumentException(s"$option is given as a socket opens, not changed")
    NioHandles.setOption(channel, option, value)
  }

  /** `handle`'s channel, a socket's `option` applies to, and its kind; else an `IllegalArgumentException`. */
  private def networkChannel(
      handle: Handle,
      option: SocketOption[?]
  ): (java.nio.channels.NetworkChannel, SocketOption.Kind) =
    NioHandles.entry(handle) match {
      case s: Selectable =>
        s.channel match {
          case c: java.nio.channels.NetworkChannel =>
            val unix = c.getLocalAddress.isInstanceOf[java.net.UnixDomainSocketAddress]
            val kind = c match {
              case _: ServerSocketChannel => if unix then SocketOption.Kind.UnixListener else SocketOption.Kind.Listener
              case _: SocketChannel       =>
                if unix then SocketOption.Kind.UnixConnection else SocketOption.Kind.Connection
              case d: DatagramChannel =>
                if d.isConnected then SocketOption.Kind.ConnectedDatagram else SocketOption.Kind.BoundDatagram
              case _ => throw new IllegalArgumentException(s"$option applies to sockets, not $handle")
            }
            if !SocketOption.onSocket(option, kind) then throw gears.async.io.internal.Checks.notFor(option, kind)
            if !supports(option, kind) then throw gears.async.io.internal.Checks.noOption(option, kind)
            (c, kind)
          case _ => throw new IllegalArgumentException(s"$option applies to sockets, not $handle")
        }
      case _ => throw new IllegalArgumentException(s"$option applies to sockets, not $handle")
    }
  def pipe(read: Slot[Readable], write: Slot[Writable]): NioOp = new OpenPipe(read, write)
  def receive(handle: BoundDatagram, buf: ByteBuffer, from: Slot[IpAddress]): Receive = new Receive(handle, buf, from)
  def receive(handle: ConnectedDatagram, buf: ByteBuffer): Receive = new Receive(handle, buf, null)
  def send(handle: BoundDatagram, buf: ByteBuffer, to: IpAddress): Send =
    new Send(handle, buf, gears.async.io.internal.Checks.ip(to))
  def send(handle: ConnectedDatagram, buf: ByteBuffer): Send = new Send(handle, buf, null)
  def send(handle: BoundDatagram, bufs: Array[ByteBuffer], to: IpAddress): Send = {
    gears.async.io.internal.Checks.checkBuffers(bufs)
    new Send(handle, bufs, gears.async.io.internal.Checks.ip(to))
  }
  def send(handle: ConnectedDatagram, bufs: Array[ByteBuffer]): Send = {
    gears.async.io.internal.Checks.checkBuffers(bufs)
    new Send(handle, bufs, null)
  }
  def timer(duration: scala.concurrent.duration.FiniteDuration): NioTimer = {
    require(duration.length >= 0, s"A timer's time is not negative: $duration")
    new NioTimer(duration.toNanos)
  }
  def deadlineTimer(nanoTime: Long): NioDeadlineTimer = new NioDeadlineTimer(nanoTime)
  def blocking(body: () => Unit): NioBlocking = new BlockingTask(body)
  def blocking[A <: AnyRef](body: () => A, into: Slot[A]): NioBlocking = new BlockingResultTask(body, into)
  def resolve(host: String, into: Slot[List[ResolvedAddress]]): Resolve = new Resolve(host, into)
  def read(handle: Readable, pool: BufferPool, into: Slot[Received]): PooledRead =
    new PooledRead(handle, NioOps.direct(pool), into)
  def close(handle: Handle): NioOp = new Close(handle)
  def shutdownOutput(handle: Connection): NioOp = new ShutdownOutput(handle)
  def localAddress(handle: Listener | Connection): IpAddress | UnixAddress = NioHandles.addressOf(handle, peer = false)
  def peerAddress(handle: Connection): IpAddress | UnixAddress = NioHandles.addressOf(handle, peer = true)
  @annotation.targetName("datagramLocalAddress")
  def localAddress(handle: BoundDatagram | ConnectedDatagram): IpAddress = ip(handle, peer = false)
  @annotation.targetName("datagramPeerAddress")
  def peerAddress(handle: ConnectedDatagram): IpAddress = ip(handle, peer = true)

  private def ip(handle: Handle, peer: Boolean): IpAddress = NioHandles.addressOf(handle, peer) match {
    case ip: IpAddress => ip
    case other         => throw new java.io.IOException(s"$handle is not an IP socket: $other")
  }
  def open(path: String, write: Boolean, into: Slot[File]): NioOp = new OpenFile(path, write, into)
  def read(handle: File, buf: ByteBuffer, offset: Long): PositionalRead = new PositionalRead(handle, buf, offset)
  def write(handle: File, buf: ByteBuffer, offset: Long): PositionalWrite = new PositionalWrite(handle, buf, offset)
  def sync(handle: File): FileSync = new FileSync(handle)

  /** `bufs`' remaining bytes, `size` of them, in one buffer, for a send that takes one; `bufs` left as they were. */
  private[nio] def joined(bufs: Array[ByteBuffer], size: Long): ByteBuffer = {
    val packet = ByteBuffer.allocateDirect(size.toInt)
    bufs.foreach(b => packet.put(b.duplicate()))
    packet.flip()
  }

  private def direct(pool: BufferPool): DirectPool = pool match {
    case direct: DirectPool => direct
    case other              => throw new IllegalArgumentException(s"$other is not a pool of this backend's")
  }
}

/** An op the reactor performs as soon as it is submitted, with no handle to wait for: its completion runs on the next
  * turn, with the failure `perform` threw, if any. One-shot.
  */
sealed abstract class NioAtOnce extends gears.async.io.internal.LoopAtOnce[NioOp] with NioOp

/** An op that opens a handle and writes it into a slot (see `LoopOpens`): what it opened, abandoned, is closed. */
sealed trait Opens extends NioOp with gears.async.io.internal.LoopOpens[NioOp]

/** An op that opens its handle at once, when submitted (see `NioAtOnce`), as NIO's listeners and datagram sockets are.
  */
sealed abstract class OpensAtOnce[K <: Handle](into: Slot[K])
    extends NioAtOnce
    with gears.async.io.internal.LoopOpensAtOnce[NioOp, K](into)
    with Opens {
  protected final def discard(handle: K): Unit = NioHandles.close(handle)
}

/** A stream socket bound and listening on `address` (see `IpStreamOps.listen`). */
final class Listen(
    address: SocketAddress,
    into: Slot[NioKinds.Listener],
    options: SocketOptions = SocketOptions.default
) extends OpensAtOnce(into) {
  protected def open(): NioKinds.Listener = address match {
    case ip: IpAddress     => NioHandles.listen(ip, options)
    case unix: UnixAddress => NioHandles.listen(unix, options)
  }
}

/** A datagram socket, bound to `local` and connected to `remote` where given (see `IpDatagramOps.bindDatagram` and
  * `connectDatagram`), written into `into` as the kind `kind` makes.
  */
final class OpenDatagram[K <: Handle](
    local: IpAddress | Null,
    remote: IpAddress | Null,
    into: Slot[K],
    kind: Handle => K,
    options: SocketOptions = SocketOptions.default
) extends OpensAtOnce(into) {
  protected def open(): K = kind(NioHandles.datagram(local, remote, options))
}

/** A pipe, its read end written into `read` and its write end into `write` (see `PipeOps.pipe`). */
final class OpenPipe(read: Slot[NioKinds.Readable], write: Slot[NioKinds.Writable])
    extends NioAtOnce
    with gears.async.io.internal.LoopOpensPipe[NioOp, NioKinds.Readable, NioKinds.Writable](read, write)
    with Opens {
  protected def open(): (NioKinds.Readable, NioKinds.Writable) = NioHandles.pipe()
  protected def discard(end: NioKinds.Readable | NioKinds.Writable): Unit = NioHandles.close(end)
}

/** Closes a handle (see `Ops.close`): at once, as `NioHandles.close` does. */
final class Close(val handle: Handle) extends NioAtOnce {
  override private[io] def closes: Boolean = true
  def perform(): Unit = NioHandles.close(handle)
}

/** Shuts a socket down for writing (see `StreamSocketOps.shutdownOutput`): at once, as `SocketChannel.shutdownOutput`
  * does.
  */
final class ShutdownOutput(val handle: Handle) extends NioAtOnce {
  def perform(): Unit = NioHandles.shutdownOutput(handle)
}

/** What to do with a handle once it is ready. `perform` runs a non-blocking operation on the handle's entry, which is
  * looked up when the command is submitted, or performed on the caller (`NioOnCaller`); it returns whether the op is
  * done, and an exception it throws is the op's failure. A reactor performs a command as soon as it is submitted,
  * before waiting for readiness. Commands are `Repeatable`.
  */
abstract class Command extends NioOp with Repeatable {
  def handle: Handle
  def interest: Interest
  def perform(): Boolean

  override private[io] def claim: gears.async.io.internal.Turns | Null =
    if interest == Interest.Read then handle.reader else handle.writer

  /** The interest ops to wait for on `channel`: reading a listener is accepting. */
  def selectOps(channel: SelectableChannel): Int =
    if interest == Interest.Write then SelectionKey.OP_WRITE
    else if channel.isInstanceOf[ServerSocketChannel] then SelectionKey.OP_ACCEPT
    else SelectionKey.OP_READ

  /** The entry of the current submission, looked up by the reactor, or by `NioOnCaller`. */
  private[nio] var entry: Entry | Null = null

  /** Submissions in a row that `NioOnCaller` finished at once, so that a run of them yields now and then. Caller's
    * thread only.
    */
  private[nio] var finishedHere = 0

  /** The key of the selector this command last waited on, kept so that waiting there again needs no lookup. */
  private[nio] var key: SelectionKey | Null = null

  protected final def selectable[C <: SelectableChannel](kind: Class[C], op: String): C = entry match {
    case s: Selectable => s.as(kind, op)
    case other         => throw new IOException(s"$op needs a socket, not $other")
  }
}

/** Fills `buf` between position and limit. Done once at least one byte has been read, or at end of stream; fails if
  * `buf` has no room, where a channel's read returns 0, which would otherwise mean wait, for ever.
  */
final class Read(val handle: Handle, buf: ByteBuffer) extends Command {
  gears.async.io.internal.Checks.checkFillable(buf)
  def interest: Interest = Interest.Read
  override private[io] def refusal: Throwable | Null =
    if buf.hasRemaining() then null else gears.async.io.internal.Checks.bufferFull(buf)
  def perform(): Boolean = entry.nn.read(buf) != 0
}

/** Fills a buffer of `pool`'s, writing it into `into` (see `Ops.read`), as `Read` fills the caller's. */
final class PooledRead(val handle: Handle, pool: DirectPool, into: Slot[Received]) extends Command {
  override private[io] def slotRefusal: Throwable | Null = gears.async.io.internal.Checks.notEmpty(into)
  def interest: Interest = Interest.Read
  def perform(): Boolean = {
    val slot = pool.take()
    if slot == null then throw new IOException("The pool has no buffer free")
    val buffer = slot.nn.buffer
    val read =
      try entry.nn.read(buffer)
      catch {
        case failure: Throwable =>
          slot.nn.release() // a failed read holds nothing
          throw failure
      }
    if read == 0 then {
      slot.nn.release()
      false
    } else {
      buffer.flip()
      into.set(slot.nn)
      true
    }
  }
}

/** Drains `buf` between position and limit. Done once at least one byte has been written, or at once, with no system
  * call, if `buf` has none.
  */
final class Write(val handle: Handle, buf: ByteBuffer) extends Command {
  def interest: Interest = Interest.Write
  def perform(): Boolean = !buf.hasRemaining() || entry.nn.write(buf) >= 0
}

/** Fills `bufs` in order, as `Read` fills one, through the channel's scattering read. */
final class ReadMany(val handle: Handle, bufs: Array[ByteBuffer]) extends Command {
  gears.async.io.internal.Checks.checkFillable(bufs)
  def interest: Interest = Interest.Read
  override private[io] def refusal: Throwable | Null =
    if gears.async.io.internal.Checks.anyRemaining(bufs) then null else gears.async.io.internal.Checks.buffersFull(bufs)
  def perform(): Boolean = entry.nn.read(bufs) != 0
}

/** Drains `bufs` in order, as `Write` drains one, through the channel's gathering write. */
final class WriteMany(val handle: Handle, bufs: Array[ByteBuffer]) extends Command {
  def interest: Interest = Interest.Write
  def perform(): Boolean = !gears.async.io.internal.Checks.anyRemaining(bufs) || entry.nn.write(bufs) >= 0
}

/** Accepts one connection on a listener, writing the new connection's handle into `into`. */
final class Accept(val handle: Handle, into: Slot[NioKinds.Connection]) extends Command {
  override private[io] def waitsItsTurn: Boolean = true
  override private[io] def slotRefusal: Throwable | Null = gears.async.io.internal.Checks.notEmpty(into)
  def interest: Interest = Interest.Read
  def perform(): Boolean = {
    val client = selectable(classOf[ServerSocketChannel], "Accepting").accept()
    if client != null then {
      try client.configureBlocking(false)
      catch {
        case t: Throwable =>
          client.close()
          throw t
      }
      NioHandles.accepted(handle, client)
      into.set(NioKinds.connection(NioHandles.add(Selectable(client, null))))
    }
    client != null
  }
}

/** Accepts the connections pending on a listener, at most `max`, writing their handles into `into` (see
  * `StreamSocketOps.acceptAll`). Done once at least one has been accepted.
  */
final class AcceptAll(val handle: Handle, into: Slot[Array[NioKinds.Connection]], max: Int) extends Command {
  override private[io] def waitsItsTurn: Boolean = true
  override private[io] def slotRefusal: Throwable | Null = gears.async.io.internal.Checks.notEmpty(into)
  def interest: Interest = Interest.Read
  def perform(): Boolean = {
    val listener = selectable(classOf[ServerSocketChannel], "Accepting")
    val clients = gears.async.io.internal.AcceptLoop.acceptAll[NioKinds.Connection](max) {
      val client = listener.accept()
      if client == null then null
      else {
        try client.configureBlocking(false)
        catch {
          case t: Throwable =>
            client.close()
            throw t
        }
        NioHandles.accepted(handle, client)
        NioKinds.connection(NioHandles.add(Selectable(client, null)))
      }
    }
    if clients != null then into.set(clients)
    clients != null
  }
}

/** A stream socket connected to `address` (see `IpStreamOps.connect` and `LoopConnects`): the socket is opened, and its
  * non-blocking connect started, when the op is submitted or performed on the caller, and the op then waits for the
  * connect to finish. Done once connected, after writing the socket into `into`, which a connection that finished at
  * once, as a Unix socket's usually does, is as soon as it is submitted; if the connect fails, or the op is cancelled
  * or times out first, the socket is closed and `into` left empty.
  */
final class ConnectTo(
    address: SocketAddress,
    into: Slot[NioKinds.Connection],
    options: SocketOptions = SocketOptions.default
) extends Command
    with gears.async.io.internal.LoopConnects[NioOp, NioKinds.Connection](into)
    with Opens {
  override private[io] def claim: gears.async.io.internal.Turns | Null =
    null // on a socket of its own
  def handle: Handle = socket.nn
  def interest: Interest = Interest.Write
  override def selectOps(channel: SelectableChannel): Int = SelectionKey.OP_CONNECT

  protected def open(): NioKinds.Connection = NioKinds.connection(NioHandles.connectStream(address, options))
  protected def discard(socket: NioKinds.Connection): Unit = NioHandles.close(socket)

  def perform(): Boolean = {
    val done =
      try {
        val channel = selectable(classOf[SocketChannel], "Connecting")
        try channel.isConnected || channel.finishConnect()
        catch case e: java.net.ConnectException => throw NioHandles.connectFailed(e)
      } catch {
        case t: Throwable =>
          abandon()
          throw t
      }
    if done then finished()
    done
  }
}

/** Receives one packet into `buf`, left flipped for reading, and writes its sender into `from`; on a connected socket,
  * whose sender is its peer, `from` is null.
  */
final class Receive(val handle: Handle, buf: ByteBuffer, from: Slot[IpAddress] | Null) extends Command {
  gears.async.io.internal.Checks.checkFillable(buf)
  override private[io] def waitsItsTurn: Boolean = true
  override private[io] def slotRefusal: Throwable | Null =
    if from == null then null else gears.async.io.internal.Checks.notEmpty(from.nn)
  def interest: Interest = Interest.Read
  override private[io] def refusal: Throwable | Null = // the JDK would drop the whole packet
    if buf.capacity() > 0 then null else gears.async.io.internal.Checks.noCapacity(buf)
  def perform(): Boolean = {
    buf.clear()
    val sender = selectable(classOf[DatagramChannel], "Receiving").receive(buf)
    if sender != null then {
      buf.flip()
      if from != null then from.nn.set(NioHandles.ipAddress(sender))
    }
    sender != null
  }
}

/** Sends `buf` as one packet, consuming it: from a bound socket to `to`, an empty one too; from a connected one, `to`
  * null, to its peer, never an empty one.
  */
final class Send(val handle: Handle, bufs: Array[ByteBuffer], to: IpAddress | Null) extends Command {
  override private[io] def waitsItsTurn: Boolean = true
  def this(handle: Handle, buf: ByteBuffer, to: IpAddress | Null) = this(handle, Array(buf), to)
  def interest: Interest = Interest.Write
  private val target = if to == null then null else NioHandles.socketAddress(to)

  // A connected channel sends nothing for an empty buffer, through `write` or `send` alike, and says it sent it; so no
  // backend sends an empty packet on a connected socket (see `IpDatagramOps.send`).
  override private[io] def refusal: Throwable | Null =
    if target == null && !gears.async.io.internal.Checks.anyRemaining(bufs) then
      gears.async.io.internal.Checks.emptyPacket(bufs(0))
    else null

  def perform(): Boolean = {
    val channel = selectable(classOf[DatagramChannel], "Sending")
    val size = gears.async.io.internal.Checks.remaining(bufs)
    if target == null then {
      // A connected channel's gathering write sends one datagram of all the buffers.
      (if bufs.length == 1 then channel.write(bufs(0)).toLong else channel.write(bufs)) > 0
    } else {
      // A send to an address takes one buffer: several are joined, a copy.
      val packet = if bufs.length == 1 then bufs(0) else NioOps.joined(bufs, size)
      val sent = channel.send(packet, target) > 0 || size == 0 // an empty one goes, and reports 0 bytes
      if sent && bufs.length > 1 then bufs.foreach(b => b.position(b.limit()))
      sent
    }
  }
}

/** A one-shot timer, kept in its reactor's heap while pending (`TimerHeap.Entry`). */
sealed class NioTimer private[nio] (protected val nanos: Long)
    extends gears.async.io.internal.LoopTimer[NioOp]
    with NioOp

/** A timer at a deadline that may move while it is pending (see `Ops.deadlineTimer`). */
final class NioDeadlineTimer private[nio] (initially: Long) extends NioTimer(-1L) with Deadline {
  at = initially
  def deadline: Long = at
  def moveTo(nanoTime: Long): Unit = move(nanoTime)
}

/** The reactor's side of a promise (`LoopPromise`): `NioBlocking`, the only kind, completes itself from the blocker
  * pool.
  */
sealed abstract class NioPromise extends gears.async.io.internal.LoopPromise[NioOp] with NioOp

/** A promise the reactor completes by running `block` on the blocker pool (`Blockers`), with `BlockingRun`'s
  * cancellation: skipped if not started, interrupted if running, and `onCancel` waits for `block` to return. An
  * exception thrown by `block` is the op's failure. As with the kqueue backend's blocking ops, a subclass writes its
  * result into its slot through `publish`, which skips the write once cancelled. One marked `Repeatable`, such as a
  * file op, is restarted on each submission.
  */
abstract class NioBlocking extends NioPromise with gears.async.io.internal.LoopBlocking[NioOp] {

  /** Readies this op to run on the calling thread instead of the blocker pool (see `NioOnCaller`, which has recorded
    * the submission).
    */
  private[nio] def here(): BlockingHere = {
    reset()
    restart()
    new BlockingHere(this)
  }
}

/** A blocking task built from a function, for `BlockingOps.blocking`. */
final class BlockingTask(body: () => Unit) extends NioBlocking {
  def block(): Unit = body()
}

/** A blocking task whose function's value is written into `into`, for `BlockingOps.blocking`. */
final class BlockingResultTask[A <: AnyRef](body: () => A, into: Slot[A]) extends NioBlocking {
  override private[io] def slotRefusal: Throwable | Null = gears.async.io.internal.Checks.notEmpty(into)
  def block(): Unit = publish(into, body())
}

/** Resolves a host name with `InetAddress`, which blocks, on the blocker pool. */
final class Resolve(val host: String, into: Slot[List[ResolvedAddress]]) extends NioBlocking {
  gears.async.io.internal.Checks.checkHost(host)
  override private[io] def slotRefusal: Throwable | Null = gears.async.io.internal.Checks.notEmpty(into)
  override protected def detachable: Boolean = true // its cancel need not wait for the lookup
  def block(): Unit = publish(into, Resolve.addresses(host))
}

private[nio] object Resolve {

  /** `host`'s addresses, in numeric form, looked up with `InetAddress`, which blocks: an IPv6 one as `inet_ntop` writes
    * it (`IpText`), where the JDK writes every group in full, and without a scope, as the Native backends give it.
    */
  def addresses(host: String): List[ResolvedAddress] = {
    val addresses =
      try InetAddress.getAllByName(host).toList
      catch case e: IOException => throw new IOException(s"Failed to resolve $host: ${e.getMessage}", e)
    gears.async.io.internal.Checks.resolved(
      host,
      addresses.map {
        case v4: Inet4Address => ResolvedAddress(AddressFamily.IPv4, v4.getHostAddress)
        case v6               => ResolvedAddress(AddressFamily.IPv6, gears.async.io.internal.IpText.ipv6(v6.getAddress))
      }
    )
  }
}
