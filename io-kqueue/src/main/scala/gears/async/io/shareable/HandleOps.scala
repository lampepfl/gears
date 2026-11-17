package gears.async.io.shareable

import gears.async.io.Slot

sealed abstract class ImmediateOp extends SingleUseAbstractTimerHeapOp, TimeoutOnWake {
  def perform(): Unit
}

abstract class AbstractCloseOp(val handle: BaseHandle)
    extends SingleUseAbstractTimerHeapOp,
      TimeoutOnWake,
      CloseListener {

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

trait PipeOpener:
  type PipeReadable <: BaseReadableHandle
  type PipeWritable <: BaseWritableHandle
  def pipe(): (read: PipeReadable, write: PipeWritable)

abstract class AbstractMakePipeOp[PR <: BaseReadableHandle, PW <: BaseWritableHandle](
    readSlot: Slot[PR],
    writeSlot: Slot[PW]
) extends ImmediateOp,
      PipeOpener {
  type PipeReadable <: PR
  type PipeWritable <: PW

  /** Before anything is opened: an occupied slot could not take its end. */
  override def onEntry(): Unit =
    super.onEntry()
    if !readSlot.isEmpty || !writeSlot.isEmpty then throw new IllegalArgumentException("A pipe needs two empty slots")

  final def perform(): Unit =
    val pair = pipe()
    readSlot.set(pair.read)
    writeSlot.set(pair.write)
}
