package gears.async.io
package node

import gears.async.io.node.base.BaseHandle
import gears.async.io.node.base.WaitableHandle
import gears.async.io.node.host.Addresses
import gears.async.io.node.host.ByteSink
import gears.async.io.node.host.ByteSource
import gears.async.io.node.host.NodeBuffer
import gears.async.io.node.host.NodeErr
import gears.async.io.node.host.NodeGlue
import gears.async.io.node.host.NodeStream
import gears.async.io.node.internal.Fs
import gears.async.io.shareable.HandleOwner

import java.io.IOException
import java.nio.ByteBuffer
import scala.scalajs.js
import scala.scalajs.js.typedarray.TypedArrayBufferOps.*
import scala.scalajs.js.typedarray.Uint8Array

/** A handle of the Node.js backend: an I/O object of Node's (see `BaseHandle` for how it is closed). */
sealed abstract class NodeHandle(kind: String, owner: HandleOwner) extends BaseHandle(owner) {
  private val id = NodeHandle.next()

  override def toString: String = s"$kind #$id"
}

private[node] object NodeHandle {
  private var ids = 0L

  private def next(): Long = {
    ids += 1
    ids
  }
}

/** A handle that can be read as a stream. */
sealed trait NodeReadable extends NodeHandle

/** A handle that can be written as a stream. */
sealed trait NodeWritable extends NodeHandle

/** A handle read through a source of Node's, by ops that wait for it to be readable. */
sealed trait StreamReadable extends NodeReadable, WaitableHandle {
  private[node] def source: ByteSource
}

/** A handle written through a sink of Node's, by ops that wait for it to be writable. */
sealed trait StreamWritable extends NodeWritable, WaitableHandle {
  private[node] def sink: ByteSink
}

/** A handle over a stream: one op waiting in each direction, which the stream wakes. The stream tells the handle,
  * through the one function the handle gives it as it is made, and the handle its waiter: an op that waits is a
  * reference held here, and costs Node nothing.
  */
sealed abstract class StreamHandle(kind: String, owner: HandleOwner, reads: ByteSource | Null, writes: ByteSink | Null)
    extends NodeHandle(kind, owner)
    with WaitableHandle {
  if reads != null then reads.onReadable = () => ready(read = true)
  if writes != null then writes.onWritable = () => ready(read = false)

  override protected def listen(read: Boolean, on: Boolean): Unit =
    if read && reads != null then reads.listening(on)

  /** Closes the read side, then the write side if it is another object; done once both are. */
  protected def closeObject(): Boolean = {
    val done = () => completeClose()
    if reads != null then reads.close(done)
    else writes.nn.close(done)
  }
}

/** A stream's read end with no write end: a FIFO's. */
final class ReadEnd private[node] (kind: String, owner: HandleOwner, private[node] val source: ByteSource)
    extends StreamHandle(kind, owner, source, null)
    with StreamReadable

/** A stream's write end with no read end: a FIFO's. */
final class WriteEnd private[node] (kind: String, owner: HandleOwner, private[node] val sink: ByteSink)
    extends StreamHandle(kind, owner, null, sink)
    with StreamWritable

/** A connected stream socket, IP or Unix, with its addresses as they were when it connected: Node forgets them once the
  * socket has ended.
  */
final class ConnectionHandle private (
    owner: HandleOwner,
    socket: js.Dynamic,
    stream: NodeStream,
    private[node] val unix: Boolean,
    private var knownLocal: IpSocketAddress | UnixAddress | Null,
    private var knownPeer: IpSocketAddress | UnixAddress | Null,
    private var noDelay: Boolean
) extends StreamHandle(if unix then "Unix connection" else "connection", owner, stream, stream)
    with StreamReadable
    with StreamWritable {
  if !unix then socket.setNoDelay(noDelay)

  // An IP connection's addresses are asked of Node when they are first asked for here, as the system is asked on the
  // other platforms: each is two calls to the system that most connections never need. Asked once the socket has
  // gone, Node has none to give, and the asking fails.

  private[node] def local: IpSocketAddress | UnixAddress = {
    if knownLocal == null then knownLocal = Addresses.reported(socket.localAddress, socket.localPort)
    knownLocal.nn
  }

  private[node] def peer: IpSocketAddress | UnixAddress = {
    if knownPeer == null then knownPeer = Addresses.reported(socket.remoteAddress, socket.remotePort)
    knownPeer.nn
  }

  private[node] def source: ByteSource = stream
  private[node] def sink: ByteSink = stream

  private[node] def shutdownOutput(): Unit = stream.shutdown()

  private[node] def kind: SocketOption.Kind =
    if unix then SocketOption.Kind.UnixConnection else SocketOption.Kind.Connection

  private[node] def option(option: SocketOption[?]): Any = option match {
    case SocketOption.NoDelay => noDelay
    case other                => throw new IllegalArgumentException(s"This backend has no socket option $other")
  }

  private[node] def setOption(option: SocketOption[?], value: Any): Unit = option match {
    case SocketOption.NoDelay =>
      NodeErr.guard(socket.setNoDelay(value.asInstanceOf[Boolean]))
      noDelay = value.asInstanceOf[Boolean]
    case other => throw new IllegalArgumentException(s"This backend has no socket option $other")
  }
}

private[node] object ConnectionHandle {

  /** The connection `socket` is, an IP one. */
  def ip(socket: js.Dynamic, noDelay: Boolean, owner: HandleOwner): ConnectionHandle =
    new ConnectionHandle(
      owner,
      socket,
      new NodeStream(socket, readable = true, writable = true),
      false,
      null,
      null,
      noDelay
    )

  /** The connection `socket` is, a Unix one: Node does not tell its names, so they are those the backend knows. */
  def unix(socket: js.Dynamic, local: UnixAddress, peer: UnixAddress, owner: HandleOwner): ConnectionHandle =
    new ConnectionHandle(
      owner,
      socket,
      new NodeStream(socket, readable = true, writable = true),
      true,
      local,
      peer,
      false
    )
}

/** A listening stream socket, IP or Unix. Node accepts the connections that come as they come, so those no accept has
  * taken yet wait here, as they would in the system's queue, at most `Backlog` of them, the rest refused; the accepts
  * waiting take them in the order they came.
  */
final class ListenerHandle private[node] (
    owner: HandleOwner,
    server: js.Dynamic,
    private[node] val unix: Boolean,
    private[node] val local: IpSocketAddress | UnixAddress,
    options: SocketOptions
) extends NodeHandle(if unix then "Unix listener" else "listener", owner)
    with WaitableHandle {
  private val arrived = scala.collection.mutable.ArrayDeque.empty[ConnectionHandle]

  /** Accepts take turns: each takes whole connections. */
  override protected def queuesWaiters(read: Boolean): Boolean = true
  private val noDelay = options.get(SocketOption.NoDelay) != false
  private var failed: IOException | Null = null
  private var shut = false

  private val connected: js.Function1[js.Dynamic, Unit] = socket =>
    if shut || arrived.length >= ListenerHandle.Backlog then socket.destroy(): Unit
    else
      try {
        arrived.append(
          // What a listener accepts is its run's.
          if unix then ConnectionHandle.unix(socket, local.asInstanceOf[UnixAddress], UnixAddress(""), owner)
          else ConnectionHandle.ip(socket, noDelay, owner)
        )
        ready(read = true)
      } catch {
        case _: Exception => // gone before it was accepted: Node no longer has its addresses
          socket.on("error", NodeStream.ignore)
          socket.destroy(): Unit
      }

  /** A failure of the listener itself, as when the process has no descriptor left: the accept waiting fails with it, or
    * else the next, once.
    */
  private val errored: js.Function1[js.Dynamic, Unit] = failure => {
    failed = NodeErr.io(failure)
    ready(read = true)
  }

  server.on("connection", connected)
  server.on("error", errored)

  private[node] def kind: SocketOption.Kind =
    if unix then SocketOption.Kind.UnixListener else SocketOption.Kind.Listener

  /** At most `max` of the connections that have arrived, in order, still the listener's until `dropArrived`; null if
    * there is none. Fails, once, with a failure of the listener's own.
    */
  private[node] def peekArrived(max: Int): Array[ConnectionHandle] | Null =
    if arrived.isEmpty then {
      val failure = failed
      failed = null
      if failure != null then throw failure
      null
    } else {
      val taken = new Array[ConnectionHandle](math.min(max, arrived.length))
      var i = 0
      while i < taken.length do {
        taken(i) = arrived(i)
        i += 1
      }
      taken
    }

  /** The first `count` connections that arrived have been accepted. */
  private[node] def dropArrived(count: Int): Unit = arrived.remove(0, count)

  /** What closing does to the listener's path, for a Unix one: set by its opener. */
  private[node] var closing: (() => Unit) => Unit = close => close()

  /** The connections no accept took are closed with the listener, as the system closes those in its queue; one Node
    * hands over after this is closed as it comes.
    */
  protected def closeObject(): Boolean = {
    shut = true
    for connection <- arrived do connection.requestClose(): Unit
    arrived.clear()
    closing(() => server.close(): Unit)
    true
  }
}

private[node] object ListenerHandle {
  inline val Backlog = 511
}

/** An IP datagram socket, a `dgram.Socket` of Node's: bound, or connected to one peer.
  *
  * Node takes every packet from the system as it arrives and hands it over at once, to whoever listens, so the packets
  * no receive has taken wait here, as they would in the socket's receive buffer, and no more of them than it holds
  * (`SO_RCVBUF`, as Node reports it): one that arrives when they fill it is dropped, as the system drops it. The
  * receives waiting take them in the order they came.
  */
sealed abstract class DatagramHandle(name: String, owner: HandleOwner, socket: js.Dynamic, v6: Boolean)
    extends NodeHandle(name, owner)
    with WaitableHandle {

  /** Receives take turns: each takes a whole packet. */
  override protected def queuesWaiters(read: Boolean): Boolean = true

  private[node] val local: IpSocketAddress = {
    val bound = NodeErr.guard(socket.address())
    Addresses.reported(bound.address, bound.port)
  }
  private val packets = scala.collection.mutable.ArrayDeque.empty[DatagramHandle.Packet]
  private var held = 0
  private var capacity = receiveBufferSize()
  private var failed: IOException | Null = null

  private def receiveBufferSize(): Int =
    try socket.getRecvBufferSize().asInstanceOf[Int]
    catch case _: js.JavaScriptException => DatagramHandle.DefaultCapacity

  /** Kept unless the socket's buffer is full, when it is dropped; then the receives waiting are told. */
  private val arrived: js.Function2[Uint8Array, js.Dynamic, Unit] = (message, sender) => {
    val packet = new DatagramHandle.Packet(message, sender)
    if held + packet.cost <= capacity then {
      packets.append(packet)
      held += packet.cost
      ready(read = true)
    }
  }

  /** A failure Node reports of the socket itself. It fails the receive waiting, or else the next op, once, as the
    * system reports it once. On a connected socket that may be its peer refusing a packet; on one that is not, no
    * packet's fate fails anything (`IpDatagrams.send`), so what the network says of one, where a system passes it on,
    * is dropped.
    */
  private val errored: js.Function1[js.Dynamic, Unit] = failure => {
    val code: Any = if NodeErr.failed(failure) then failure.code else null
    val ofAPacket = DatagramHandle.PacketFates.contains(code)
    if !(ofAPacket && kind == SocketOption.Kind.BoundDatagram) then {
      failed = NodeErr.io(failure)
      ready(read = true)
    }
  }

  socket.on("message", arrived)
  socket.on("error", errored)

  private[node] def kind: SocketOption.Kind

  /** Throws a failure of the socket's own that no op has had yet. */
  private[node] def takeFailure(): Unit = {
    val failure = failed
    if failure != null then {
      failed = null
      throw failure
    }
  }

  /** The next packet, still the socket's until `dropPacket`; null if there is none. */
  private[node] def peekPacket(): DatagramHandle.Packet | Null =
    if packets.isEmpty then {
      takeFailure()
      null
    } else packets.head

  /** The next packet has been received. */
  private[node] def dropPacket(): Unit = held -= packets.removeHead().cost

  /** Hands `packet` to Node to send, to `to`, or to the peer if null: `sent` is called once Node has. */
  private[node] def send(packet: Uint8Array, to: IpSocketAddress | Null, sent: js.Function1[js.Dynamic, Unit]): Unit =
    NodeErr.guard {
      if to == null then socket.send(packet, sent)
      else socket.send(packet, to.port, hostOf(to.address), sent)
    }: Unit

  /** Sends the bytes of `bufs`, left as they are, as one packet: as `send`. One buffer whose memory JavaScript has is
    * copied and sent in one call.
    */
  private[node] def sendFrom(
      bufs: Array[ByteBuffer],
      to: IpSocketAddress | Null,
      sent: js.Function1[js.Dynamic, Unit]
  ): Unit =
    if bufs.length == 1 && bufs(0).hasArrayBuffer() then
      NodeErr.guard {
        val buf = bufs(0)
        NodeGlue.sendFrom(
          socket,
          buf.arrayBuffer(),
          buf.arrayBufferOffset() + buf.position(),
          buf.remaining(),
          if to == null then 0 else to.port,
          if to == null then js.undefined else hostOf(to.address),
          sent
        )
      }
    else send(NodeBuffer.copy(bufs, NodeBuffer.room(bufs)), to, sent)

  // A socket mostly sends to the address it last sent to, and hears from the one it last heard from: each is kept as
  // Node takes or gives it, text, beside what it is here, so that a packet costs no writing out or reading of one.

  private var lastSentTo: IpAddress | Null = null
  private var lastSentToHost: String = ""

  private def hostOf(address: IpAddress): String = {
    if !(lastSentTo eq address) && lastSentTo != address then {
      lastSentToHost = if v6 then Addresses.hostForIPv6(address) else Addresses.host(address)
      lastSentTo = address
    }
    lastSentToHost
  }

  private var lastSenderText: Any = null
  private var lastSenderPort: Any = null
  private var lastSender: IpSocketAddress | Null = null

  /** The sender of `packet`, as Node reports it. */
  private[node] def senderOf(packet: DatagramHandle.Packet): IpSocketAddress = {
    val text: Any = packet.sender.address
    val port: Any = packet.sender.port
    if lastSender == null || text != lastSenderText || port != lastSenderPort then {
      lastSender = Addresses.reported(text, port)
      lastSenderText = text
      lastSenderPort = port
    }
    lastSender.nn
  }

  private[node] def option(option: SocketOption[?]): Any = NodeErr.guard(option match {
    case SocketOption.ReceiveBuffer => socket.getRecvBufferSize().asInstanceOf[Int]
    case SocketOption.SendBuffer    => socket.getSendBufferSize().asInstanceOf[Int]
    case other                      => throw new IllegalArgumentException(s"This backend has no socket option $other")
  })

  private[node] def setOption(option: SocketOption[?], value: Any): Unit = NodeErr.guard(option match {
    case SocketOption.ReceiveBuffer =>
      socket.setRecvBufferSize(value.asInstanceOf[Int])
      capacity = receiveBufferSize()
    case SocketOption.SendBuffer => socket.setSendBufferSize(value.asInstanceOf[Int]): Unit
    case other                   => throw new IllegalArgumentException(s"This backend has no socket option $other")
  })

  protected def closeObject(): Boolean = {
    packets.clear()
    held = 0
    val closed: js.Function0[Unit] = () => completeClose()
    NodeErr.guard(socket.close(closed)): Unit
    false
  }
}

private[node] object DatagramHandle {

  /** What the packets waiting may hold where Node does not tell the socket's receive buffer. */
  inline val DefaultCapacity = 262144

  /** A packet Node handed over, with its sender as Node reports it. */
  final class Packet(val bytes: Uint8Array, val sender: js.Dynamic) {

    /** What the packet takes of the socket's buffer: its bytes, and something for itself, so that empty ones count. */
    def cost: Int = bytes.length + 64
  }

  /** The failures that are the network's word on a packet sent, not the socket's own. */
  val PacketFates: Set[Any] = Set("ECONNREFUSED", "ECONNRESET", "EHOSTUNREACH", "ENETUNREACH", "EHOSTDOWN")
}

final class BoundDatagramHandle private[node] (owner: HandleOwner, socket: js.Dynamic, v6: Boolean)
    extends DatagramHandle("bound datagram socket", owner, socket, v6) {
  private[node] def kind: SocketOption.Kind = SocketOption.Kind.BoundDatagram
}

final class ConnectedDatagramHandle private[node] (
    owner: HandleOwner,
    socket: js.Dynamic,
    v6: Boolean,
    private[node] val peer: IpSocketAddress
) extends DatagramHandle("connected datagram socket", owner, socket, v6) {
  private[node] def kind: SocketOption.Kind = SocketOption.Kind.ConnectedDatagram
}

/** A regular file, by its descriptor, read and written with Node's `fs` calls, which run on Node's own threads and call
  * back: as a stream at the position the handle carries, one read and one write at a time, or at offsets. A call is
  * work claimed on the handle, so a close waits for it, and the descriptor is never another file's under a call.
  */
final class FileHandle private[node] (
    owner: HandleOwner,
    private[node] val fd: Int,
    path: String,
    private[node] val writable: Boolean
) extends NodeHandle(s"file $path", owner)
    with NodeReadable
    with NodeWritable {

  /** Where the handle's stream reads, or writes, next. */
  private[node] var position = 0L
  private var reading = false
  private var writing = false

  /** Begins a stream read, or write, of `op`: refused while another is under way. */
  private[node] def beginStream(read: Boolean, op: AnyRef): Unit = {
    if (if read then reading else writing) then
      throw new IllegalArgumentException(
        s"An op ${if read then "reading" else "writing"}, as $op does, is already pending on its handle, $this"
      )
    if read then reading = true else writing = true
  }

  private[node] def endStream(read: Boolean): Unit = if read then reading = false else writing = false

  protected def closeObject(): Boolean = {
    val closed: js.Function1[js.Dynamic, Unit] = _ => completeClose()
    NodeErr.guard(Fs.close(fd, closed))
    false
  }
}
