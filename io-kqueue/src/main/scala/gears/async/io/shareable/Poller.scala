package gears.async.io.shareable

abstract class Poller {
  private val notifier = new Object

  protected def wake(): Unit = notifier.synchronized {
    signalled = true
    notifier.notify()
  }

  protected def isEmpty(): Boolean

  def waitForDeadline(deadlineTask: Long): Unit = notifier.synchronized {
    // Anything that arrived after the reads above has set `signalled`; the inbox may also be over budget.
    if !signalled && isEmpty() then
      if deadlineTask >= 0 then
        val sleepTime = deadlineTask - System.nanoTime()
        if sleepTime > 0 then notifier.wait(sleepTime / 1000000, (sleepTime % 1000000).toInt)
      else notifier.wait()
    signalled = false
  }

  /** Guarded by `notifier`. Whether there has been anything new since the worker last looked: a `notify` alone is lost
    * if the worker is not yet waiting.
    */
  private var signalled = false
}
