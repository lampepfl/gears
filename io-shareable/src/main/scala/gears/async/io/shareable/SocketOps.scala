package gears.async.io
package shareable

/** An op that opens a handle at once, without waiting, and writes it into a slot: a listener, a datagram socket, a
  * FIFO. Performed once. What opens it is the backend's (`_open`).
  */
abstract class AbstractOpenOp[K <: AnyRef](into: Slot[K]) extends ImmediateOp {

  /** Opens the handle, as `owner`'s, or throws why it could not be: nothing is left open by one that throws. */
  def _open(owner: HandleOwner): K

  /** Before anything is opened: an occupied slot could not take the handle. */
  override def onEntry(): Unit =
    super.onEntry()
    Checks.checkEmpty(into)

  final def perform(owner: HandleOwner): Unit = into.set(_open(owner))
}

/** An op that makes one call on an open handle, which returns at once: shutting a connection down for writing.
  * Performed once, with the handle's work claimed, so a closed handle refuses it and a close waits for it.
  */
abstract class AbstractHandleCallOp[H <: BaseHandle](handle: H) extends ImmediateOp {
  def _call(handle: H): Unit

  final def perform(owner: HandleOwner): Unit =
    owner.check(handle)
    handle.claimWork()
    try _call(handle)
    finally handle.releaseWork()
}

/** Connects a stream socket: opened, and its connect begun without blocking, as the op is performed, after which the op
  * waits for the socket to be writable, as a connect that is under way does. The connection is written into `into` once
  * it is made. One that fails, is cancelled or times out first leaves the slot empty and closes the socket, which never
  * reached the caller. Performed once.
  */
abstract class AbstractConnectOp[H <: BaseHandle](into: Slot[H]) extends SingleUseAbstractTimerHeapOp, ReadyWaiting {

  /** The socket as the op waits on it: one listened for where the op keeps its time limit. */
  type Connecting = H { type Source = AbstractConnectOp.this.Source }

  /** Opens the socket, as `owner`'s, and begins connecting it, without blocking, or throws why it could not. */
  def _startConnect(owner: HandleOwner): Connecting

  /** Whether `socket`'s connect has gone through; throws its failure if it has failed. Must not block. */
  def _finishConnect(socket: H): Boolean

  private var socket: H | Null = null

  /** Written by whoever does the op's work, before the op is settled, and read by the performer after. */
  private var published = false

  protected final def isRead: Boolean = false

  override def onEntry(): Unit =
    super.onEntry()
    Checks.checkEmpty(into)

  protected final def attempt(): Int =
    val connecting = socket.nn
    if !_finishConnect(connecting) then ReadyWaiting.Blocked
    else
      into.set(connecting)
      published = true
      0

  final def performConnect(self: Source)(using gears.async.Async): Unit =
    val opened = _startConnect(self.handleOwner)
    socket = opened
    try performWhenReadyOn(opened)(self)
    finally
      if !published then
        // Nothing else has the socket, so this closes it here and now; a failure to is not the op's.
        try opened.requestClose(): Unit
        catch case _: java.io.IOException => ()
}

/** Accepts one connection on a listener: the one at the head of its queue of pending connections, waiting for one while
  * there is none. Accepts performed at once on one listener take turns (see `WaiterQueue`).
  */
abstract class AbstractAcceptOp[L <: BaseHandle, C <: AnyRef](
    listener: L,
    into: Slot[C]
) extends AbstractReadinessOp[L](listener) {

  /** A connection pending on `listener`, accepted, as its listener's run's, or null if there is none. Must not block.
    */
  def _accept(listener: L): C | Null

  protected final def isRead: Boolean = true

  override def onEntry(): Unit =
    super.onEntry()
    Checks.checkEmpty(into)

  protected final def attempt(): Int =
    val accepted = _accept(listener)
    if accepted == null then ReadyWaiting.Blocked
    else
      into.set(accepted.nn)
      1
}

/** Accepts the connections pending on a listener, at most `max`: at least one, waiting while there is none. */
abstract class AbstractAcceptAllOp[
    L <: BaseHandle,
    C <: AnyRef: scala.reflect.ClassTag
](
    listener: L,
    into: Slot[Array[C]],
    max: Int
) extends AbstractReadinessOp[L](listener) {
  if max <= 0 then throw new IllegalArgumentException(s"max must be positive, not $max")

  /** As `AbstractAcceptOp._accept`. */
  def _accept(listener: L): C | Null

  protected final def isRead: Boolean = true

  override def onEntry(): Unit =
    super.onEntry()
    Checks.checkEmpty(into)

  protected final def attempt(): Int =
    val first = _accept(listener)
    if first == null then ReadyWaiting.Blocked
    else
      val accepted = scala.collection.mutable.ArrayBuffer[C](first.nn)
      // What is accepted is the caller's whatever happens to the next: one that fails ends the batch, not the op.
      var more = true
      while more && accepted.size < max do
        val next =
          try _accept(listener)
          catch case _: java.io.IOException => null
        if next == null then more = false else accepted += next.nn
      into.set(accepted.toArray)
      accepted.size
}
