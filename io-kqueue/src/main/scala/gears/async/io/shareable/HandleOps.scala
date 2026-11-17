package gears.async.io.shareable

import gears.async.io.Slot

abstract class ImmediateOp extends SingleUseAbstractTimerHeapOp, TimeoutOnWake {

  /** Does the op, at once. */
  def perform(): Unit
}

abstract class AbstractCloseOp(val handle: BaseHandle)
    extends SingleUseAbstractTimerHeapOp,
      TimeoutOnWake,
      CloseListener {

  /** The performer's worker keeps the op's time limit. */
  type Source <: EventSource

  /** Closes the handle, which is done at once unless work claimed on it is under way: then, or with a time limit to
    * keep, the performer waits. `self` is the performer's worker.
    */
  final def performClose(self: Source)(using gears.async.Async): Unit =
    var shouldWait = false
    if hasDeadline then
      holdTimerOn(self)
      shouldWait = true
    if !pollClose() then shouldWait = true
    if shouldWait then parkUntilSettled()

  /** Whether the handle is closed already. If not, work claimed on it is still under way, and the last of it to be
    * released closes the handle and tells this op, its one owner.
    */
  final def pollClose(): Boolean =
    if handle.requestClose() then true
    else
      handle.onClosed(this)
      false

  /** The op is performed once, so this can only be for the performance that waits; it may by then have been cancelled
    * or timed out, and the handle closes all the same.
    */
  final def handleClosed(): Unit =
    if claimAtCloseFire() then completeFromCloseFire(WorkpoolScheduler.SuccessUnit)
}

/** Opens a pipe and writes its ends into two slots. Performed once. What opens it is the backend's (`_pipe`). */
abstract class AbstractMakePipeOp[R <: AnyRef, W <: AnyRef](readSlot: Slot[R], writeSlot: Slot[W]) extends ImmediateOp {

  /** Opens a pipe: its read end and its write end. Nothing is left open by one that throws. */
  def _pipe(): (R, W)

  /** Before anything is opened: an occupied slot could not take its end. */
  override def onEntry(): Unit =
    super.onEntry()
    if !readSlot.isEmpty || !writeSlot.isEmpty then throw new IllegalArgumentException("A pipe needs two empty slots")

  final def perform(): Unit =
    val (read, write) = _pipe()
    readSlot.set(read)
    writeSlot.set(write)
}
