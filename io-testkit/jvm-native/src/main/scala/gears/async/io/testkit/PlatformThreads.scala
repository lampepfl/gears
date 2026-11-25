package gears.async.io
package testkit

private[testkit] object PlatformThreads {
  def sleep(millis: Long): Unit = Thread.sleep(millis)
}
