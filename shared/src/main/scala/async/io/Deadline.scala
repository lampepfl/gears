package gears.async.io

/** A timer whose deadline may be moved while it is pending (see `Ops.deadlineTimer`). */
trait Deadline {

  /** The deadline, on `System.nanoTime`'s clock. */
  def deadline: Long

  /** Moves the deadline to `nanoTime`, from any thread. A `perform` waiting on the timer returns at the new deadline
    * instead, at once if it has passed, however often the deadline was moved; a later `perform` waits for it. Moving a
    * deadline later is cheap, a store; moving it earlier may wake the thread that waits for timers.
    */
  def moveTo(nanoTime: Long): Unit
}
