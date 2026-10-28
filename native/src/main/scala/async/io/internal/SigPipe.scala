package gears.async.io
package internal

import scala.scalanative.libc
import scala.scalanative.posix.signal
import scala.scalanative.posix.signalOps.*
import scala.scalanative.unsafe.*

/** Where a descriptor cannot be told not to raise `SIGPIPE`, as macOS's sockets (`SO_NOSIGPIPE`) and pipes
  * (`F_SETNOSIGPIPE`) and FreeBSD's sockets can, the signal is ignored for the whole process, so that a write to a
  * stream whose reader has gone fails with `EPIPE` instead of ending the process: on Linux, for sockets and pipes, and
  * on FreeBSD, for pipes. The JVM does the same at startup, as does Rust's runtime. A handler the application installed
  * is left alone; only the default disposition, which ends the process, is replaced.
  */
private[io] object SigPipe {

  /** Ignores `SIGPIPE` unless the application handles it already. Done once; returns whether it is ignored now. */
  lazy val ignored: Boolean = Zone.acquire { implicit z =>
    val current = alloc[signal.sigaction]()
    if signal.sigaction(signal.SIGPIPE, null, current) != 0 then false
    else if CFuncPtr.toPtr(current.sa_handler) != CFuncPtr.toPtr(libc.signal.SIG_DFL) then
      CFuncPtr.toPtr(current.sa_handler) == CFuncPtr.toPtr(libc.signal.SIG_IGN)
    else {
      val ignore = alloc[signal.sigaction]()
      ignore.sa_handler = libc.signal.SIG_IGN
      signal.sigaction(signal.SIGPIPE, ignore, null) == 0
    }
  }
}
