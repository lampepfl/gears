package gears.async.io.shareable

import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicReference

trait BaseHandle extends HandleState.Holder:

  /** The run the handle was opened in, as its pool's `HandleOwner`: the only one whose ops may use it. */
  def owner: HandleOwner
  def open(): Unit
  def requestClose(): Boolean

  /** What the handle keeps for the ops that wait for it to be readable, or to be writable, and for the event source
    * that tells them when it is.
    */
  def readiness(read: Boolean): HandleReadiness[Source]

  /** The event sources that can listen for this handle: its backend's workers. An op that waits on the handle gets its
    * listener back as one, and so can keep its time limit there.
    */
  type Source <: EventSource

  /** Has `owner` told once the handle is closed, at once if it already is. For whoever's `requestClose` began the close
    * and returned false: a close has one owner, so one listener at most.
    */
  def onClosed(owner: CloseListener): Unit
  def claimWork(): Unit
  def releaseWork(): Unit

trait BaseReadableHandle extends BaseHandle
trait BaseWritableHandle extends BaseHandle

/** Told that a handle's close is done, by whichever thread finished it. */
trait CloseListener:
  def handleClosed(): Unit

object AbstractBaseHandle:
  private[shareable] case object Closed

abstract class AbstractBaseHandle(val owner: HandleOwner) extends BaseHandle:
  import HandleState.{ClosedState, ClosingState, OpenState, UnenteredState}

  opaque type ClosePermit = Unit

  /** does final cleanup of the handle and releases any associated resources. Returns true if the handle was closed,
    * else a listener is required.
    */
  def cleanup()(using ClosePermit): Boolean

  final private def closeHandle()(using ClosePermit): Boolean =
    // First, before anything is released: whoever waits fails. Then the listeners drop the handle, while its
    // descriptor is still its own; what they registered with the kernel goes as the descriptor is closed.
    reads.failWaiters()
    writes.failWaiters()
    reads.forgetListener()
    writes.forgetListener()
    val closedSync = cleanup()
    if closedSync then
      assert(getState == ClosedState)
      completeClose()
    closedSync

  /** Whoever waits for the close to be done: null until there is one, its owner while it waits, and `Closed` from when
    * the close is done, whichever comes first. The owner is told exactly once, by whichever of the two comes second.
    */
  private val closeListener = AtomicReference[CloseListener | AbstractBaseHandle.Closed.type | Null](null)

  protected def completeClose(): Unit =
    owner.closed(this)
    closeListener.getAndSet(AbstractBaseHandle.Closed) match
      case owner: CloseListener => owner.handleClosed()
      case _                    => ()

  final def onClosed(owner: CloseListener): Unit =
    if !closeListener.compareAndSet(null, owner) then
      closeListener.get() match
        case AbstractBaseHandle.Closed => owner.handleClosed()
        case _                         => throw new IllegalStateException("A close already has its owner waiting")

  /** Whether the ops that wait for this handle to be readable, or to be writable, take turns in a queue, as a
    * listener's accepts and a datagram socket's receives and sends do, rather than one being allowed at a time, as of a
    * stream's reads and of its writes. Asked once for each direction, as the handle is made: a constant.
    */
  protected def queuesWaiters(read: Boolean): Boolean = false

  private def readinessOf(read: Boolean): HandleReadiness[Source] =
    if queuesWaiters(read) then WaiterQueue[Source](this, read) else SingleWaiter[Source](this, read)

  private val reads = readinessOf(read = true)
  private val writes = readinessOf(read = false)

  final def readiness(read: Boolean): HandleReadiness[Source] = if read then reads else writes

  /** Whether the handle accepts `op` as its waiter while `pending` is. By default one op waits in each direction, and a
    * second is refused; one that has settled, whose performer has yet to clear it, is not waiting, and gives way.
    */
  protected[shareable] def acceptsWaiter(read: Boolean, op: ReadinessOp, pending: ReadinessOp | Null): Boolean =
    pending == null || pending.settled

  /** An op has just begun to wait for the handle to be readable, or writable, with `listener` listening for it: for a
    * handle whose listener may never learn that it is, which then has something else find out, and tell the listener
    * (`EventSource.reportLater`). Called on the waiting op's thread, with no lock held.
    */
  def waiterWaits(read: Boolean, listener: EventSource): Unit = ()

  /** How many times in a row the handle is reported ready with no op waiting before it gives up its listener. Until
    * then an op that waits costs no system call; past it, the handle is taken to be one whose ops find it ready, for
    * which the reports only wake the listener for nothing, and tie the handle's next wait to a worker that may by then
    * be kept busy.
    */
  protected[shareable] def idleReportsLimit: Int = 16

  final def open(): Unit =
    if compareAndSetState(UnenteredState, OpenState) then owner.opened(this)
    else throw new IllegalStateException("Handle is already open or closed")

  final def claimWork(): Unit =
    claimIfOpen() match
      case OpenState                  => ()
      case ClosedState | ClosingState => throw new ClosedChannelException()
      case UnenteredState             => throw new IOException("Handle is not open")

  /** Work claimed is released even once the handle is closing: the last of it then closes the handle. */
  final def releaseWork(): Unit =
    if releaseClaimed() then
      given ClosePermit = ()
      val _ = closeHandle()

  final def requestClose(): Boolean =
    val begun = beginClose()
    begun.found match
      case OpenState =>
        if begun.idle then
          given ClosePermit = ()
          closeHandle()
        else false // the last work released closes it
      // Closing is done once: a second close fails as any op on a closed handle does.
      case ClosingState | ClosedState => throw new ClosedChannelException()
      case UnenteredState             => throw new IllegalStateException("Handle is not open")
