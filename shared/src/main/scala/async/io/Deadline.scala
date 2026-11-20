package gears.async.io

/** A timer whose deadline may be moved while it is pending (see `Interface.deadlineTimer`). */
trait Deadline {

  /** The deadline, on `System.nanoTime`'s clock. */
  def deadline: Long

  /** Moves the deadline to `nanoTime`, from any thread. A `perform` waiting on the timer returns at the new deadline
    * instead, at once if it has passed, however often the deadline was moved. The time limit given to `perform` stays
    * fixed. A move after a performance finishes or is cancelled affects the next performance; it does not revive the
    * previous one. A move racing completion may arrive too late to prevent it. Moving a deadline later need not wake
    * the thread that waits for timers; moving it earlier may wake it.
    */
  def moveTo(nanoTime: Long): Unit
}
