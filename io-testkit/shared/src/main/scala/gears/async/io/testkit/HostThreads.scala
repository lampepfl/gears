package gears.async.io
package testkit

/** The host's threads, as blocking code in the demos uses them: only where there are threads to block, which a backend
  * with `Blocking` ops has.
  */
object HostThreads {

  /** Blocks the calling thread for `millis`, as `Thread.sleep` does, throwing `InterruptedException` if it is
    * interrupted. Scala.js has one thread and no way to block it, so there it throws `UnsupportedOperationException`:
    * no backend there has the `Blocking` ops whose bodies call this.
    */
  def sleep(millis: Long): Unit = PlatformThreads.sleep(millis)
}
