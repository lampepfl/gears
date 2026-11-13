package gears.async.io.shareable

trait Poller extends AutoCloseable {
  protected def id: Int
  protected def wake(): Unit
  protected def isEmpty(): Boolean
  protected def waitForDeadline(deadlineTask: Long): Unit
}
