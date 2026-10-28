package gears.async.io
package internal

import scala.scalanative.posix.sys.resource
import scala.scalanative.posix.sys.resourceOps.*
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** The process's limit on open descriptors, raised as the JVM raises its own (`-XX:+MaxFDLimit`, on by default): a
  * shell's soft limit, 256 by default on macOS, is far below what a server's sockets need, and a server that reaches it
  * fails its accepts with `EMFILE`. The soft limit goes up to the hard limit, or, where the kernel refuses that, as
  * macOS does above its per-process maximum, to the highest power of two it takes. Done once, by the runner.
  */
private[io] object FileLimit {

  /** The soft limit once raised, or as it was if it could not be. */
  lazy val raised: Long = Zone.acquire { implicit z =>
    val limit = alloc[resource.rlimit]()
    if resource.getrlimit(resource.RLIMIT_NOFILE, limit) != 0 then -1L
    else {
      val current = limit.rlim_cur.toLong
      val hard = limit.rlim_max
      def tryRaise(to: resource.rlim_t): Boolean = {
        limit.rlim_cur = to
        resource.setrlimit(resource.RLIMIT_NOFILE, limit) == 0
      }
      if hard != resource.RLIM_INFINITY && tryRaise(hard) then hard.toLong
      else {
        // Refused, or unlimited: the highest power of two above the current limit that the kernel takes.
        var candidate = 1L << 20
        while candidate > current && !tryRaise(candidate.toUSize) do candidate >>= 1
        math.max(candidate, current)
      }
    }
  }
}
