package gears.async.io.nio

import gears.async.io.shareable.AbstractBaseHandle
import gears.async.io.shareable.BaseHandle
import gears.async.io.shareable.BaseReadableHandle
import gears.async.io.shareable.BaseWritableHandle
import gears.async.io.shareable.EventSource
import gears.async.io.shareable.HandleOwner
import gears.async.io.shareable.HandleState

import java.nio.channels.GatheringByteChannel
import java.nio.channels.Pipe
import java.nio.channels.ScatteringByteChannel
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey

sealed trait NioHandle extends BaseHandle:
  type Source = NioPoller

/** A handle read as a stream, from `reads`. */
sealed trait NioReadable extends NioHandle, BaseReadableHandle:
  def reads: ScatteringByteChannel

/** A handle written as a stream, to `writes`. */
sealed trait NioWritable extends NioHandle, BaseWritableHandle:
  def writes: GatheringByteChannel

/** A handle over a channel a selector can watch, which is made non-blocking here: every op on it is tried, and waits
  * for a worker's selector to report the channel ready if it could not be done.
  */
abstract class NioChannelHandle(val channel: SelectableChannel, owner: HandleOwner)
    extends AbstractBaseHandle(owner),
      NioHandle:
  channel.configureBlocking(false)
  open() // construction is acquisition of the handle

  /** What a selector is asked to report for ops that wait to read, or to write. */
  private[nio] def interest(read: Boolean): Int

  /** The pollers the channel was ever registered with, each of which has a key of it: a close is finished by them. Only
    * an op with the handle's work claimed registers it, which a close waits for, so none is added once one has begun.
    */
  private val pollers = java.util.concurrent.ConcurrentHashMap.newKeySet[NioPoller]()

  private[nio] def registeredWith(poller: NioPoller): Unit = pollers.add(poller): Unit

  /** How many pollers have yet to take the closed channel's key out of their selectors. */
  private val releasing = new java.util.concurrent.atomic.AtomicInteger(0)

  /** `poller`'s selector no longer has the channel: once none has, the channel is closed for good, and so the handle.
    */
  private[nio] def releasedBy(poller: NioPoller): Unit =
    if releasing.decrementAndGet() == 0 then
      if !compareAndSetState(HandleState.ClosingState, HandleState.ClosedState) then
        throw new IllegalStateException("Concurrent cleanup detected")
      completeClose()

  /** Each op that begins to wait has its listener's selector report the channel (see `NioPoller.arm`). */
  override def waiterWaits(read: Boolean, listener: EventSource): Unit = listener match
    case poller: NioPoller => poller.arm(this, read)
    case _                 => ()

  /** As the handle closes, while its channel is still open: what it leaves outside it is removed. */
  protected def beforeChannelCloses(): Unit = ()

  /** A channel registered with a selector is closed for good, its descriptor released and its port free, only once that
    * selector has taken its key out, which it does as it next selects. So the close of a handle that was ever waited on
    * is finished by the workers whose selectors have it (`NioPoller.release`), and whoever closes it is told then; one
    * that never was is closed here and now.
    */
  final def cleanup()(using ClosePermit): Boolean =
    try beforeChannelCloses()
    finally channel.close()
    val registered = pollers.toArray(new Array[NioPoller](0))
    if registered.isEmpty then
      if !compareAndSetState(HandleState.ClosingState, HandleState.ClosedState) then
        throw new IllegalStateException("Concurrent cleanup detected")
      true
    else
      releasing.set(registered.length)
      registered.foreach(_.release(this))
      false

final class PipeRead(source: Pipe.SourceChannel, owner: HandleOwner)
    extends NioChannelHandle(source, owner),
      NioReadable:
  def reads: ScatteringByteChannel = source
  private[nio] def interest(read: Boolean): Int = SelectionKey.OP_READ

final class PipeWrite(sink: Pipe.SinkChannel, owner: HandleOwner) extends NioChannelHandle(sink, owner), NioWritable:
  def writes: GatheringByteChannel = sink
  private[nio] def interest(read: Boolean): Int = SelectionKey.OP_WRITE
