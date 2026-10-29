package gears.async.io.internal

/** Allocation-free accounting for immediate completions on an event-loop worker. Thread-confined. Each drain has a
  * finite budget to guarantee another poll, even when every operation is immediately ready. Each task gets a smaller
  * allowance while peers are queued; when alone it may spend an additional idle allowance, also bounded per drain.
  * Starting a new task never replenishes the drain budget. Charge only operations that actually finish immediately.
  */
final class CooperativeBudget(perDrain: Int, perTask: Int, perIdleDrain: Int = Int.MaxValue) {
  require(perDrain > 0 && perTask > 0 && perIdleDrain > 0)
  private var drainLeft = 0
  private var taskLeft = 0
  private var idleLeft = 0

  def startDrain(): Unit = {
    drainLeft = perDrain
    idleLeft = perIdleDrain
  }
  def startTask(): Unit = taskLeft = perTask

  inline def canCompleteImmediately(inline hasPeers: Boolean): Boolean =
    drainLeft > 0 && (taskLeft > 0 || (idleLeft > 0 && !hasPeers))

  def completedImmediately(): Unit = {
    drainLeft -= 1
    if taskLeft > 0 then taskLeft -= 1
    else idleLeft -= 1
  }
}
