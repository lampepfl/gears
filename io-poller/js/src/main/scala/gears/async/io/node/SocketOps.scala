package gears.async.io
package node

import gears.async.io.node.base.AbstractReadinessOp
import gears.async.io.node.base.CallOp
import gears.async.io.node.base.HandleCall
import gears.async.io.node.base.RepeatableOp
import gears.async.io.node.base.SingleUseOp
import gears.async.io.node.host.Addresses
import gears.async.io.node.host.NodeBuffer
import gears.async.io.node.host.NodeErr
import gears.async.io.node.host.NodeStream
import gears.async.io.node.host.UnixPaths
import gears.async.io.node.internal.Dgram
import gears.async.io.node.internal.Net
import gears.async.io.shareable.Moved

import java.nio.ByteBuffer
import scala.scalajs.js

/** An op that opens something of Node's and waits for Node to say it is open. Until then it only waits: a cancel or its
  * time limit ends it at once, and what was being opened is abandoned as the performance ends (`finish`), so that
  * nothing is left open and its slot is left empty.
  */
private[node] abstract class AbstractOpenOp[A <: AnyRef](into: Slot[A]) extends SingleUseOp with CallOp {
  private var opened = false

  override def onEntry(): Unit = Slots.requireEmpty(into)

  /** Node's callbacks end here: with what was opened, made by `make` if the op still waits for it. */
  protected final def openedAs(make: => A): Unit =
    if pending then
      try {
        into.set(make)
        opened = true
        complete(null)
      } catch case failure: Exception => complete(failure)

  /** One listener for the failure of what is being opened, for as long as the op waits. */
  protected final val failed: js.Function1[js.Dynamic, Unit] = failure => complete(NodeErr.io(failure))

  /** Closes what was being opened, which nobody is to have. */
  protected def abandon(): Unit

  override protected final def finish(): Unit = if !opened then abandon()
}

/** Opens a stream socket connected to an address. */
private[node] abstract class AbstractConnectOp(
    address: IpSocketAddress | UnixAddress,
    options: SocketOptions,
    into: Slot[ConnectionHandle]
) extends AbstractOpenOp[ConnectionHandle](into) {
  private var socket: js.Dynamic | Null = null

  private val connected: js.Function0[Unit] = () =>
    openedAs {
      val connecting = socket.nn
      connecting.removeListener("error", failed)
      address match {
        case unix: UnixAddress  => ConnectionHandle.unix(connecting, UnixAddress(""), unix, runOwner)
        case _: IpSocketAddress =>
          ConnectionHandle.ip(connecting, options.get(SocketOption.NoDelay) != false, runOwner)
      }
    }

  protected final def call(): Unit = {
    val to = address match {
      case IpSocketAddress(ip, port) =>
        js.Dynamic.literal(host = Addresses.host(ip), port = port, allowHalfOpen = true)
      case UnixAddress(path) => js.Dynamic.literal(path = path, allowHalfOpen = true)
    }
    val connecting = NodeErr.guard(Net.connect(to))
    socket = connecting
    connecting.once("error", failed)
    connecting.once("connect", connected)
  }

  protected final def abandon(): Unit = {
    val connecting = socket
    if connecting != null then {
      connecting.removeListener("connect", connected)
      connecting.on("error", NodeStream.ignore)
      connecting.destroy(): Unit
    }
  }
}

/** Opens a stream socket listening on an address: an IP one, or a Unix path, whose socket file it replaces if one is
  * there, and which it removes as it closes if the file there is still its own.
  */
private[node] abstract class AbstractListenOp(
    address: IpSocketAddress | UnixAddress,
    options: SocketOptions,
    into: Slot[ListenerHandle]
) extends AbstractOpenOp[ListenerHandle](into) {
  private var server: js.Dynamic | Null = null

  private val listening: js.Function0[Unit] = () =>
    openedAs {
      val bound = server.nn
      val listener = address match {
        case unix: UnixAddress =>
          val listener = new ListenerHandle(runOwner, bound, true, unix, options)
          val own = UnixPaths.identity(unix.path)
          listener.closing = close => UnixPaths.closing(unix.path, own)(close)
          listener
        case _: IpSocketAddress =>
          val at = bound.address()
          new ListenerHandle(runOwner, bound, false, Addresses.reported(at.address, at.port), options)
      }
      bound.removeListener("error", failed)
      listener
    }

  protected final def call(): Unit = {
    val on = address match {
      case IpSocketAddress(ip, port) =>
        js.Dynamic.literal(host = Addresses.host(ip), port = port, backlog = ListenerHandle.Backlog)
      case UnixAddress(path) =>
        UnixPaths.clear(path)
        js.Dynamic.literal(path = path, backlog = ListenerHandle.Backlog)
    }
    val binding = NodeErr.guard(Net.createServer(js.Dynamic.literal(allowHalfOpen = true)))
    server = binding
    binding.once("error", failed)
    binding.once("listening", listening)
    NodeErr.guard(binding.listen(on)): Unit
  }

  protected final def abandon(): Unit = {
    val binding = server
    if binding != null then {
      binding.removeListener("listening", listening)
      binding.on("error", NodeStream.ignore)
      binding.close(): Unit
    }
  }
}

/** Accepts connections on a listener, at least one and at most `max`, behind the accepts already waiting. Those it
  * cannot take, its slot filled while it waited, stay the listener's for the next accept.
  */
private[node] abstract class AbstractAcceptOp(
    listener: ListenerHandle,
    max: Int,
    slot: Slot[?],
    deliver: Array[ConnectionHandle] => Unit
) extends AbstractReadinessOp[ListenerHandle](listener) {
  override def onEntry(): Unit = Slots.requireEmpty(slot)

  protected final def isRead: Boolean = true

  protected final def attempt(): Moved = {
    val accepted = handle.peekArrived(max)
    if accepted == null then Moved.Blocked
    else {
      deliver(accepted)
      handle.dropArrived(accepted.length)
      Moved(accepted.length)
    }
  }
}

/** Opens a datagram socket: bound to `local`, and if `remote` is given connected to it, bound to `local` or to any
  * address of the remote's family.
  */
private[node] abstract class AbstractOpenDatagramOp[H <: DatagramHandle](
    local: IpSocketAddress | Null,
    remote: IpSocketAddress | Null,
    options: SocketOptions,
    into: Slot[H]
) extends AbstractOpenOp[H](into) {
  private val v6 = (if remote != null then remote else local.nn).address.protocol == Protocol.IPv6
  private var socket: js.Dynamic | Null = null

  private val connected: js.Function0[Unit] = () =>
    openedAs {
      val opening = socket.nn
      val kind = if remote == null then SocketOption.Kind.BoundDatagram else SocketOption.Kind.ConnectedDatagram
      for (option, value) <- SocketOption.opening(options, kind) do
        option match {
          case SocketOption.ReceiveBuffer => NodeErr.guard(opening.setRecvBufferSize(value.asInstanceOf[Int])): Unit
          case SocketOption.SendBuffer    => NodeErr.guard(opening.setSendBufferSize(value.asInstanceOf[Int])): Unit
          case _                          => ()
        }
      val handle =
        if remote == null then new BoundDatagramHandle(runOwner, opening, v6)
        else new ConnectedDatagramHandle(runOwner, opening, v6, remote)
      opening.removeListener("error", failed)
      handle.asInstanceOf[H]
    }

  private val bound: js.Function0[Unit] = () =>
    if pending then
      if remote == null then connected()
      else
        try socket.nn.connect(remote.port, Addresses.host(remote.address), connected): Unit
        catch case js.JavaScriptException(failure) => complete(NodeErr.io(failure))

  protected final def call(): Unit = {
    val opening = NodeErr.guard(Dgram.createSocket(js.Dynamic.literal(`type` = if v6 then "udp6" else "udp4")))
    socket = opening
    opening.on("error", failed)
    val to = js.Dynamic.literal(
      address = if local != null then Addresses.host(local.address) else if v6 then "::" else "0.0.0.0",
      port = if local != null then local.port else 0,
      exclusive = true
    )
    NodeErr.guard(opening.bind(to, bound)): Unit
  }

  protected final def abandon(): Unit = {
    val opening = socket
    if opening != null then {
      opening.on("error", NodeStream.ignore)
      try opening.close(): Unit
      catch case _: js.JavaScriptException => () // not bound yet, or closed by its failure
    }
  }
}

/** Receives one packet into `buf`, cleared first and left flipped, and its sender into `from`, if given, behind the
  * receives already waiting. A packet it cannot take, its slot filled while it waited, stays the socket's.
  */
private[node] abstract class AbstractReceiveOp(
    socket: DatagramHandle,
    buf: ByteBuffer,
    from: Slot[IpSocketAddress] | Null
) extends AbstractReadinessOp[DatagramHandle](socket) {
  override def onEntry(): Unit = {
    if from != null then Slots.requireEmpty(from)
    if buf.capacity() == 0 then
      throw new IllegalArgumentException("A receive into a buffer of no capacity would drop a whole packet")
  }

  protected final def isRead: Boolean = true

  protected final def attempt(): Moved = {
    val packet = handle.peekPacket()
    if packet == null then Moved.Blocked
    else {
      if from != null then {
        if !from.isEmpty then throw new IllegalArgumentException("The receive's slot was filled while it waited")
        from.set(handle.senderOf(packet))
      }
      val bytes = packet.bytes
      buf.clear()
      NodeBuffer.fillFrom(buf, bytes, math.min(bytes.length, buf.capacity())) // the rest dropped
      buf.flip()
      handle.dropPacket()
      Moved(buf.remaining())
    }
  }
}

/** Sends `bufs`, in order, as one packet, to `to`, or to the peer of a connected socket if null. A send is Node's from
  * the moment it is given, which sends them in the order given, each a whole packet, and cannot take one back: a cancel
  * or a time limit that comes before Node has sent it is too late, and a close waits for it.
  */
private[node] abstract class AbstractSendOp(
    protected val handle: DatagramHandle,
    bufs: Array[ByteBuffer],
    to: IpSocketAddress | Null
) extends RepeatableOp
    with HandleCall[DatagramHandle] {
  override def onEntry(): Unit =
    if to == null && NodeBuffer.remaining(bufs) == 0 then
      throw new IllegalArgumentException("A connected datagram socket sends no empty packet")

  /** The packet was sent, whatever came for the op meanwhile: only Node's own failure fails it. */
  private val sent: js.Function1[js.Dynamic, Unit] = failure => {
    returned(): Unit
    if NodeErr.failed(failure) then complete(NodeErr.io(failure))
    else {
      bufs.foreach(buf => buf.position(buf.limit()))
      complete(null)
    }
  }

  protected final def call(): Unit = calling {
    handle.takeFailure()
    // Copied without consuming: the buffers are consumed once the packet has been sent, not if the send fails.
    handle.sendFrom(bufs, to, sent)
  }
}
