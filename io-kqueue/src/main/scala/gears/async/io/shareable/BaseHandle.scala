package gears.async.io.shareable

import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicReference

trait BaseHandle extends HandleState.Holder:
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

abstract class AbstractBaseHandle extends BaseHandle:
  import HandleState.{ClosedState, ClosingState, OpenState, UnenteredState}

  opaque type ClosePermit = Unit

  /** does final cleanup of the handle and releases any associated resources. Returns true if the handle was closed,
    * else a listener is required.
    */
  def cleanup()(using ClosePermit): Boolean

  final private def closeHandle()(using ClosePermit): Boolean =
    // First, before anything is released: whoever waits fails. Then the listeners drop the handle, while its
    // descriptor is still its own; what they registered with the kernel goes as the descriptor is closed.
    reads.failWaiter()
    writes.failWaiter()
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
    closeListener.getAndSet(AbstractBaseHandle.Closed) match
      case owner: CloseListener => owner.handleClosed()
      case _                    => ()

  final def onClosed(owner: CloseListener): Unit =
    if !closeListener.compareAndSet(null, owner) then
      closeListener.get() match
        case AbstractBaseHandle.Closed => owner.handleClosed()
        case _                         => throw new IllegalStateException("A close already has its owner waiting")

  private val reads = HandleReadiness[Source](this, read = true)
  private val writes = HandleReadiness[Source](this, read = false)

  final def readiness(read: Boolean): HandleReadiness[Source] = if read then reads else writes

  /** Whether the handle accepts `op` as its waiter while `pending` is. By default one op waits in each direction, and a
    * second is refused; one that has settled, whose performer has yet to clear it, is not waiting, and gives way.
    */
  protected[shareable] def acceptsWaiter(read: Boolean, op: ReadinessOp, pending: ReadinessOp | Null): Boolean =
    pending == null || pending.settled

  /** How many times in a row the handle is reported ready with no op waiting before it gives up its listener. Until
    * then an op that waits costs no system call; past it, the handle is taken to be one whose ops find it ready, for
    * which the reports only wake the listener for nothing, and tie the handle's next wait to a worker that may by then
    * be kept busy.
    */
  protected[shareable] def idleReportsLimit: Int = 16

  final def open(): Unit =
    if compareAndSetState(UnenteredState, OpenState) then ()
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
