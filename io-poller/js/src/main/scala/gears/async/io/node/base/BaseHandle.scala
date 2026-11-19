package gears.async.io.node.base

import gears.async.io.ClosedChannelException
import gears.async.io.shareable
import gears.async.io.shareable.HandleState

import java.io.IOException

/** Told that a handle's close is done: the shared layer's. */
type CloseListener = shareable.CloseListener

/** A handle of this backend: the shared layer's (`AbstractBaseHandle`), which keeps its state and the work claimed on
  * it, and closes it once, for one owner, when no work is claimed. What is Node's is how its I/O object is closed
  * (`closeObject`), at once or by a callback.
  *
  * A handle is open from the moment it is made: construction is acquisition, as nothing else runs between Node handing
  * an object over and the handle being made of it. It is its run's (`owner`), that of the op that opened it, or of the
  * listener that accepted it: the run closes it at its end if nothing has, and no other run may use it.
  */
abstract class BaseHandle(owner: shareable.HandleOwner) extends shareable.AbstractBaseHandle(owner) {
  import HandleState.*

  open()

  final def isOpen: Boolean = getState == OpenState

  /** Throws the failure of an op on this handle if it has been closed. */
  final def checkOpen(): Unit = if getState != OpenState then throw new ClosedChannelException()

  /** The failure of an op on this handle if it has been closed, or null. */
  final def failureIfClosed: IOException | Null = if getState != OpenState then new ClosedChannelException() else null

  /** Closes the handle's I/O object and releases what goes with it. Returns true if that is done as it returns; if not,
    * `completeClose` is to be called once it is.
    */
  protected def closeObject(): Boolean

  final def cleanup()(using ClosePermit): Boolean = {
    val closedNow = closeObject()
    if closedNow then compareAndSetState(ClosingState, ClosedState): Unit
    closedNow
  }

  /** The handle's I/O object is closed, now or by a callback since: its owner is told, once. */
  override protected def completeClose(): Unit = {
    compareAndSetState(ClosingState, ClosedState): Unit
    super.completeClose()
  }
}

/** A handle whose ops wait for it to be ready, as the shared layer has them (`AbstractReadinessHandle`): its waiters,
  * one in each direction or a queue of them, are kept and woken there. Its listener is the backend's one event source,
  * and what Node tells the handle of, bytes that came, room made, a connection or a packet arrived, the handle reports
  * to it (`ready`).
  */
trait WaitableHandle extends BaseHandle, shareable.AbstractReadinessHandle {
  type Source = NodeSource.type

  /** Called as an op begins to wait for the handle, each one, and once the handle is reported ready with none waiting:
    * for a handle that must do something to learn that it is ready, which it does for as long as an op waits.
    */
  protected def listen(read: Boolean, on: Boolean): Unit = ()

  final override def waiterWaits(read: Boolean, listener: NodeSource.type): Unit = listen(read, on = true)

  private[base] final def stopListening(read: Boolean): Unit = listen(read, on = false)

  /** One report with nobody waiting ends the listening: a stream that goes on handing bytes over with no read to take
    * them would fill memory with them.
    */
  override protected[io] def idleReportsLimit: Int = 1

  /** The handle is ready, for reads or for writes: its waiters do their work, in turn, for as long as each finds it so.
    */
  protected final def ready(read: Boolean): Unit = readiness(read).report(NodeSource, shareable.ReadinessOp.Unknown)
}
