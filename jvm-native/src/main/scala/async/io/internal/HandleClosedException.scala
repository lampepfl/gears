package gears.async.io
package internal

/** The failure of an op on a closed handle, the same on every backend of the JVM and Scala Native: the JDK's for a
  * closed channel, as `Ops.close` has it, so that a caller tells it from other I/O failures by its type, with a message
  * naming what was closed, which the JDK's leaves out. Here rather than in `Checks`, since Scala.js has no channels.
  */
private[io] final class HandleClosedException(message: String) extends java.nio.channels.ClosedChannelException {
  override def getMessage: String = message
}

private[io] object HandleClosedException {

  /** The failure of an op pending on `what` as it closed. */
  def pending(what: Any): HandleClosedException = new HandleClosedException(s"$what was closed")

  /** The failure of an op submitted on `handle` once it is closed, a close of it among them. */
  def notOpen(handle: Any): HandleClosedException = new HandleClosedException(s"Not an open handle: $handle")
}
