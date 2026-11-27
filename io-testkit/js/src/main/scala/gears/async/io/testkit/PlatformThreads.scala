package gears.async.io
package testkit

private[testkit] object PlatformThreads {
  def sleep(millis: Long): Unit =
    throw new UnsupportedOperationException("Scala.js has one thread, and no way to block it")
}
