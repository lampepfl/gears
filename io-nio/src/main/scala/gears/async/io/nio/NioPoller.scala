package gears.async.io.nio

import gears.async.io.shareable.BaseHandle
import gears.async.io.shareable.EventSource
import gears.async.io.shareable.Poller
import gears.async.io.shareable.ReadinessOp
import gears.async.io.shareable.TimerHeap.TimerOp

import java.io.IOException
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.function.Consumer

/** A worker's event source on the JVM: a selector, which says when the channels registered with it are ready.
  *
  * A selector reports a channel for as long as it is ready, where the handles expect to be told once each time one
  * becomes ready (see `HandleReadiness`). So a channel's interest in a direction is set when an op begins to wait for
  * it (`arm`), and taken off again by the first report that finds no op waiting: a channel whose waiters keep up with
  * it stays registered, and costs no call between one wait and the next, while one that is ready with nobody waiting is
  * reported once, not for ever.
  *
  * Unlike a worker's own thread on Scala Native, the threads that perform ops here are not the worker's: every method
  * but `waitForDeadline` may be called from any thread.
  */
trait NioPoller extends Poller, EventSource {

  /** Its timers include every op of this backend's: the worker it is part of takes its pool's ops. */
  type Timer >: NioOp <: TimerOp

  private val selector = Selector.open()

  /** Guards the first registration of a channel here: a second `register` would reset the interest of the first. */
  private val registering = new Object

  @volatile private var closed = false

  /** Set once the selector is given up: from then on this waits without it, and reports no handle ready. */
  @volatile private var broken = false

  /** The worker's thread, once it has waited here: what a wake-up unparks when there is no selector to wake. */
  @volatile private var waitingThread: Thread | Null = null

  /** Registers the handle's channel here, with no interest yet: `arm` sets it, as each op begins to wait. */
  override def startListening(handle: BaseHandle, read: Boolean): Unit = handle match
    case handle: NioChannelHandle =>
      registering.synchronized {
        if handle.channel.keyFor(selector) == null then
          handle.registeredWith(this)
          handle.channel.register(selector, 0, handle): Unit
      }
    case _ => throw new IllegalArgumentException(s"Not a handle of this backend's: $handle")

  /** An op has begun to wait for `handle`, which this listens for: the selector is to report it, and is woken if it was
    * not already to, as a selection under way does not see the change.
    */
  private[nio] def arm(handle: NioChannelHandle, read: Boolean): Unit = {
    val key = handle.channel.keyFor(selector)
    if key != null then
      val bits = handle.interest(read)
      // A cancelled key is a closing handle's, whose close fails whoever waits.
      try if (key.interestOpsOr(bits) & bits) != bits then selector.wakeup(): Unit
      catch case _: CancelledKeyException => ()
  }

  /** The interest is taken off; the key stays, for the next time this listens for the handle, until the handle closes.
    */
  override def stopListening(handle: BaseHandle, read: Boolean): Unit = handle match
    case handle: NioChannelHandle =>
      val key = handle.channel.keyFor(selector)
      if key != null then
        try key.interestOpsAnd(~handle.interest(read)): Unit
        catch case _: CancelledKeyException => ()
    case _ => ()

  /** The handles whose channels have been closed, and whose keys this selector has yet to take out. */
  private val closing = new java.util.concurrent.ConcurrentLinkedQueue[NioChannelHandle]

  /** `handle`'s channel has been closed: this takes its key out of the selector, on its own thread, as it next looks,
    * and tells the handle (`releasedBy`). A poller that has closed has no key of it left.
    */
  private[nio] def release(handle: NioChannelHandle): Unit = {
    closing.add(handle)
    if closed then releaseClosing() else wake()
  }

  private def releaseClosing(): Unit = {
    var handle = closing.poll()
    while handle != null do
      handle.releasedBy(this)
      handle = closing.poll()
  }

  /** Those closed by now have their keys taken out by a selection, which waits for nothing: whether there were any.
    * That selection takes with it any wake-up sent so far, whatever it was sent for, and cannot say that it did. So a
    * worker that has made one does not go on to wait in the same turn (see `waitForDeadline`): it looks again at all it
    * may have been woken for, a handle closed meanwhile among it.
    */
  private def finishCloses(): Boolean =
    if closing.isEmpty() then false
    else
      val closed = closing.toArray(new Array[NioChannelHandle](0))
      if !broken then
        try selector.selectNow(dispatch): Unit
        catch
          case failure: IOException             => selectFailed(failure)
          case failure: ClosedSelectorException => ()
      closed.foreach { handle =>
        closing.remove(handle)
        handle.releasedBy(this)
      }
      true

  private val dispatch: Consumer[SelectionKey] = key => {
    val handle = key.attachment().asInstanceOf[NioChannelHandle]
    val ready =
      try key.readyOps()
      catch case _: CancelledKeyException => 0
    if (ready & NioPoller.ReadBits) != 0 then report(handle, key, read = true)
    if (ready & NioPoller.WriteBits) != 0 then report(handle, key, read = false)
  }

  /** The handle is ready: its waiters do their work. If none waited as this began, the interest is taken off, and put
    * back if one has come to wait since, which may have found it set, and so not have woken the selector.
    */
  private def report(handle: NioChannelHandle, key: SelectionKey, read: Boolean): Unit = {
    val readiness = handle.readiness(read)
    val idle = !readiness.isWaitedOn
    readiness.report(this, ReadinessOp.Unknown)
    if idle then
      val bits = handle.interest(read)
      try
        key.interestOpsAnd(~bits): Unit
        if readiness.isWaitedOn && readiness.listenedBy(this) then key.interestOpsOr(bits): Unit
      catch case _: CancelledKeyException => ()
  }

  def wake(): Unit =
    if broken then
      val thread = waitingThread
      if thread != null then java.util.concurrent.locks.LockSupport.unpark(thread)
    else if !closed then
      try selector.wakeup(): Unit
      catch case _: ClosedSelectorException => ()

  /** A selector that fails is given up, and its owner told (`pollerFailed`), once: the worker goes on firing its timers
    * and running what it is given, so that the program can unwind, and no handle is reported ready.
    */
  private def selectFailed(failure: Throwable): Unit = {
    broken = true
    pollerFailed(new IOException(s"selector $id gave up", failure))
  }

  protected def waitForDeadline(deadlineTask: Long): Unit =
    if !finishCloses() then waitOnSelector(deadlineTask)

  private def waitOnSelector(deadlineTask: Long): Unit = {
    val now = System.nanoTime()
    val timeout =
      if !isEmpty() then 0L
      else if deadlineTask >= 0 then Math.max(deadlineTask - now, 0)
      else -1L
    if waitingThread == null then waitingThread = Thread.currentThread()
    if broken then
      if timeout > 0 then java.util.concurrent.locks.LockSupport.parkNanos(this, timeout)
      else if timeout < 0 then java.util.concurrent.locks.LockSupport.park(this)
    else
      try
        if timeout == 0 then selector.selectNow(dispatch): Unit
        // A selector waits whole milliseconds: rounded up, so that a deadline is never met early.
        else if timeout > 0 then selector.select(dispatch, (timeout + 999_999L) / 1_000_000L): Unit
        else selector.select(dispatch): Unit
      catch
        case failure: IOException             => selectFailed(failure)
        case failure: ClosedSelectorException => if !closed then selectFailed(failure)
  }

  /** Closing the selector takes every key out of it, which finishes the closes still waiting for that. */
  override def close(): Unit = {
    closed = true
    try selector.close()
    finally releaseClosing()
  }
}

object NioPoller {
  private inline val ReadBits = SelectionKey.OP_READ | SelectionKey.OP_ACCEPT
  private inline val WriteBits = SelectionKey.OP_WRITE | SelectionKey.OP_CONNECT
}
