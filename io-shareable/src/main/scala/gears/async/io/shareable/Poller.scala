package gears.async.io.shareable

trait Poller extends AutoCloseable {
  protected def id: Int
  protected def wake(): Unit
  protected def isEmpty(): Boolean
  protected def waitForDeadline(deadlineTask: Long): Unit

  /** The poller can no longer find out what is ready, and has given up trying: `failure` says why. Its owner decides
    * what that means for the program; the poller goes on waiting for deadlines and wake-ups without it.
    */
  protected def pollerFailed(failure: Throwable): Unit = ()
}
