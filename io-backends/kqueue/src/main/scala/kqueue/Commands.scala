package gears.async.io
package kqueue

import gears.async.io.Received
import gears.async.io.Slot
import gears.async.io.internal.Interest
import gears.async.io.internal.NativeBuffer
import gears.async.io.internal.NativePool
import gears.async.io.internal.NonBlocking
import gears.async.io.internal.PosixSockets

import java.nio.ByteBuffer
import scala.scalanative.posix.sys.socket
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import KqueueBackend.Handle

/** The kqueue commands behind `KqueueOps`. `perform` returns true once the op is done, and false when the descriptor
  * was not ready after all, so the reactor waits again without completing it.
  */

/** Fills `buf` between position and limit. Done once at least one byte has been read, or at end of stream; refused if
  * `buf` has no room, since a read of nothing would look like the end of the stream.
  */
final case class ReadIntoBuffer(fd: Int, buf: ByteBuffer) extends Command {
  gears.async.io.internal.Checks.checkFillable(buf)
  def interest: Interest = Interest.Read
  override def refusal: Throwable | Null =
    if buf.hasRemaining() then null else gears.async.io.internal.Checks.bufferFull(buf)
  private var read = 0 // what the last perform returned: bytes, 0 for would-block, or -1 at EOF
  private var filled = false
  def perform(): Boolean = {
    read = NonBlocking.read(fd, buf) // -1, EOF, leaves the buffer as it was
    filled = !buf.hasRemaining()
    read != 0
  }
  override def transferred: Int = read // -1 at EOF, which stays readable, so readiness is not known from it
  override def mayStillBeReady: Boolean = read < 0 || filled // a short read drained the socket
  override def edgeTriggered: Boolean = true
}

/** Fills `bufs` in order with one `readv`, as `ReadIntoBuffer` fills one (see `Ops.read`). */
final class ReadIntoBuffers(val fd: Int, bufs: Array[ByteBuffer]) extends Command {
  gears.async.io.internal.Checks.checkFillable(bufs)
  def interest: Interest = Interest.Read
  override def refusal: Throwable | Null =
    if gears.async.io.internal.Checks.anyRemaining(bufs) then null else gears.async.io.internal.Checks.buffersFull(bufs)
  private var read = 0
  private var filled = false
  def perform(): Boolean = {
    read = NonBlocking.readv(fd, bufs)
    filled = !gears.async.io.internal.Checks.anyRemaining(bufs)
    read != 0
  }
  override def transferred: Int = read
  override def mayStillBeReady: Boolean = read < 0 || filled
  override def edgeTriggered: Boolean = true
}

/** Drains `bufs` in order with one `writev`, as `WriteFromBuffer` drains one (see `Ops.write`). */
final class WriteFromBuffers(val fd: Int, bufs: Array[ByteBuffer]) extends Command {
  def interest: Interest = Interest.Write
  def perform(): Boolean = !gears.async.io.internal.Checks.anyRemaining(bufs) || NonBlocking.writev(fd, bufs) >= 0
}

/** Fills a buffer of `pool`'s, writing it into `into` (see `Ops.read`): as `ReadIntoBuffer`, into a buffer taken when
  * the descriptor is ready, and given back if it was not after all, or if the read fails.
  */
final case class PooledRead(fd: Int, pool: NativePool, into: Slot[Received]) extends Command {
  override private[io] def slotRefusal: Throwable | Null = gears.async.io.internal.Checks.notEmpty(into)
  def interest: Interest = Interest.Read
  private var read = 0
  def perform(): Boolean = {
    read = gears.async.io.internal.PooledBufferRead.attempt(pool, into)(buffer => NonBlocking.read(fd, buffer))
    read != 0
  }
  override def transferred: Int = read
  override def mayStillBeReady: Boolean = read < 0 || read == pool.bufferSize
  override def edgeTriggered: Boolean = true
}

/** Drains `buf` between position and limit. Done once at least one byte has been written, or, with nothing remaining,
  * at once, with no system call.
  */
final case class WriteFromBuffer(fd: Int, buf: ByteBuffer) extends Command {
  def interest: Interest = Interest.Write
  def perform(): Boolean = !buf.hasRemaining() || NonBlocking.write(fd, buf) >= 0
}

/** Accepts one connection on a listening socket, writing its handle into `into`. Done once a connection has been
  * accepted. The same command may be submitted again, after `into` is cleared, to accept the next connection.
  */
final case class Accept(fd: Int, into: Slot[KqueueKinds.Connection]) extends Command {
  override private[io] def waitsItsTurn: Boolean = true
  override private[io] def slotRefusal: Throwable | Null = gears.async.io.internal.Checks.notEmpty(into)
  def interest: Interest = Interest.Read
  override def edgeTriggered: Boolean = true
  def perform(): Boolean = {
    val client = NonBlocking.accept(fd)
    if client >= 0 then into.set(KqueueKinds.connection(KqueueHandles.accepted(fd, client)))
    client >= 0
  }
  override def transferred: Int =
    1 // one connection, against the pending connections a listening socket's event reports
}

/** Accepts the connections pending on a listening socket, at most `max`, writing their handles into `into` (see
  * `StreamSocketOps.acceptAll`). Done once at least one has been accepted.
  */
final class AcceptAll(val fd: Int, into: Slot[Array[KqueueKinds.Connection]], max: Int) extends Command {
  override private[io] def waitsItsTurn: Boolean = true
  override private[io] def slotRefusal: Throwable | Null = gears.async.io.internal.Checks.notEmpty(into)
  def interest: Interest = Interest.Read
  override def edgeTriggered: Boolean = true
  private var count = 0
  def perform(): Boolean = {
    val clients = gears.async.io.internal.AcceptLoop.acceptAll[KqueueKinds.Connection](max) {
      val client = NonBlocking.accept(fd)
      if client < 0 then null else KqueueKinds.connection(KqueueHandles.accepted(fd, client))
    }
    count = if clients == null then 0 else clients.length
    if clients != null then into.set(clients)
    clients != null
  }
  override def transferred: Int =
    count // connections, against the pending connections a listening socket's event reports
}

/** Waits for a non-blocking connect to finish and checks its outcome. Done once connected; throws if it failed. */
final case class Connect(fd: Int) extends Command {
  def interest: Interest = Interest.Write
  def perform(): Boolean = {
    PosixSockets.checkConnect(fd)
    true
  }
  override def tryFirst: Boolean = false // the check passes while the connection is still in progress
  override def registersReads: Boolean = true
}

/** Receives one packet and writes its sender into `from`, which holds a Unix address if `unix`, and an IP one
  * otherwise; on a connected socket, whose sender is its peer, `from` is null. Done once a packet has arrived.
  */
final class ReceiveFrom(val fd: Int, buf: ByteBuffer, from: Slot[? <: SocketAddress] | Null, unix: Boolean)
    extends Command {
  gears.async.io.internal.Checks.checkFillable(buf)
  override private[io] def waitsItsTurn: Boolean = true
  override private[io] def slotRefusal: Throwable | Null =
    if from == null then null else gears.async.io.internal.Checks.notEmpty(from.nn)
  def interest: Interest = Interest.Read
  override def refusal: Throwable | Null =
    if buf.capacity() > 0 then null else gears.async.io.internal.Checks.noCapacity(buf)
  private var received = -1
  override def transferred: Int = received
  override def edgeTriggered: Boolean = true
  def perform(): Boolean = {
    val storage = stackalloc[socket.sockaddr_storage]()
    val length = stackalloc[socket.socklen_t]()
    !length = sizeOf[socket.sockaddr_storage].toUInt
    buf.clear()
    val address = storage.asInstanceOf[Ptr[socket.sockaddr]]
    val size = NativeBuffer.staged(buf, copyIn = false, copyOut = true) { b =>
      val n = PosixSockets.receiveDatagram(fd, NativeBuffer.atPosition(b), b.remaining(), address, length)
      if n >= 0 then NativeBuffer.advance(b, n)
      n
    }
    received = size
    if size >= 0 then {
      buf.flip()
      if from != null then {
        val sender = KqueueHandles.decode(address, !length)
        if sender.isInstanceOf[gears.async.io.UnixAddress] != unix then
          throw new java.io.IOException(s"Received from $sender on a socket of the other family")
        from.asInstanceOf[Slot[SocketAddress]].set(sender)
      }
    }
    size >= 0
  }
}

/** Sends one packet, consuming `buf`: from a bound socket to `to`, or from a connected one, `to` null, to its peer,
  * without an address, as the JDK sends it, since macOS refuses one there (`EISCONN`). An empty packet is refused on a
  * connected socket, since the JDK cannot send one (see `IpDatagramOps.send`). Done once it has gone.
  */
final class SendTo(val fd: Int, bufs: Array[ByteBuffer], to: SocketAddress | Null) extends Command {
  override private[io] def waitsItsTurn: Boolean = true
  def this(fd: Int, buf: ByteBuffer, to: SocketAddress | Null) = this(fd, Array(buf), to)
  def interest: Interest = Interest.Write

  override def refusal: Throwable | Null =
    if to == null && !gears.async.io.internal.Checks.anyRemaining(bufs) then
      gears.async.io.internal.Checks.emptyPacket(bufs(0))
    else null

  def perform(): Boolean = {
    val sent =
      if bufs.length == 1 then {
        NativeBuffer.staged(bufs(0), copyIn = true, copyOut = false) { buf =>
          val size = buf.remaining()
          val done =
            if to == null then PosixSockets.sendDatagram(fd, NativeBuffer.atPosition(buf), size)
            else
              KqueueHandles.withSocketAddr(KqueueHandles.targetFor(fd, to)) { (address, length) =>
                PosixSockets.sendDatagram(fd, NativeBuffer.atPosition(buf), size, address, length)
              }
          if done then buf.position(buf.limit()) // a datagram goes whole or not at all
          done
        }
      } else if to == null then PosixSockets.sendDatagram(fd, bufs, null, 0.toUInt)
      else
        KqueueHandles.withSocketAddr(KqueueHandles.targetFor(fd, to)) { (address, length) =>
          PosixSockets.sendDatagram(fd, bufs, address, length)
        }
    sent
  }
}
