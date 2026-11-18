package gears.async.io
package shareable

import java.nio.ByteBuffer

/** Receives one packet on a datagram socket, waiting for one while there is none. Receives performed at once on one
  * socket take turns, each a whole packet (see `WaiterQueue`). `from`, if given, takes the packet's sender.
  */
abstract class AbstractReceiveOp[H <: ReadinessHandle, A <: AnyRef](
    socket: H,
    buf: ByteBuffer,
    from: Slot[A] | Null
) extends AbstractReadinessOp[H](socket) {
  Checks.checkFillable(buf)

  /** Takes one packet, if there is one, into `buf`, cleared first and left flipped, what does not fit dropped, and
    * writes its sender into `from` if that is given: whether there was one. Must not block.
    */
  def _receive(socket: H, buf: ByteBuffer, from: Slot[A] | Null): Boolean

  protected final def isRead: Boolean = true

  override def onEntry(): Unit =
    super.onEntry()
    if buf.capacity() == 0 then throw Checks.noCapacity(buf)
    if from != null then Checks.checkEmpty(from)

  protected final def attempt(): Int = if _receive(socket, buf, from) then 1 else ReadyWaiting.Blocked
}

/** Sends `bufs`, in order, as one packet on a datagram socket, waiting for room while the socket has none. Sends
  * performed at once on one socket take turns. A `connected` socket sends no empty packet (see `IpDatagrams.send`).
  */
abstract class AbstractSendOp[H <: ReadinessHandle](
    socket: H,
    bufs: Array[ByteBuffer],
    connected: Boolean
) extends AbstractReadinessOp[H](socket) {
  Checks.checkBuffers(bufs)

  /** Sends what remains of `bufs` as one packet, consuming them: whether it was sent, or the socket had no room. Must
    * not block.
    */
  def _send(socket: H, bufs: Array[ByteBuffer]): Boolean

  protected final def isRead: Boolean = false

  override def onEntry(): Unit =
    super.onEntry()
    if connected && !Checks.anyRemaining(bufs) then throw Checks.emptyPacket(bufs(0))

  protected final def attempt(): Int = if _send(socket, bufs) then 1 else ReadyWaiting.Blocked
}
