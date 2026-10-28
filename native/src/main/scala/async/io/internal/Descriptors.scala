package gears.async.io
package internal

import java.io.IOException
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.fcntl
import scala.scalanative.posix.sys.stat
import scala.scalanative.posix.sys.statOps.*
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*

import PosixErr.cError

/** Opening the descriptors every POSIX backend's handles are: regular files, FIFOs and pipes, non-blocking. Writing to
  * a pipe or FIFO whose reader has gone fails with `EPIPE` rather than raising `SIGPIPE`, which would end the process:
  * on macOS those opened for writing get `F_SETNOSIGPIPE`, and elsewhere the signal is ignored (`SigPipe`).
  */
object Descriptors {

  /** Opens a regular file; anything else is refused. */
  def openFile(path: String, write: Boolean): Int = {
    val fd = openNonBlocking(path, write)
    try if !isKind(fd, stat.S_ISREG) then throw new IOException(s"Not a regular file: $path")
    catch {
      case e: IOException =>
        unistd.close(fd)
        throw e
    }
    fd
  }

  /** Opens a FIFO without blocking; anything else is refused. Opening one for writing fails while it has no reader. */
  def openFifo(path: String, write: Boolean): Int = {
    val fd = openNonBlocking(path, write)
    try {
      if !isKind(fd, stat.S_ISFIFO) then throw new IOException(s"Not a FIFO: $path")
      if write then noSigPipe(fd)
    } catch {
      case e: IOException =>
        unistd.close(fd)
        throw e
    }
    fd
  }

  /** A pipe, as its read end and its write end, both non-blocking. */
  def pipe(): (Int, Int) = {
    val ends = stackalloc[CInt](2)
    if unistd.pipe(ends) < 0 then throw new IOException(s"Failed to create pipe: ${cError()}")
    try {
      PosixSockets.setNonBlocking(ends(0))
      PosixSockets.setNonBlocking(ends(1))
      noSigPipe(ends(1))
    } catch {
      case e: IOException =>
        unistd.close(ends(0))
        unistd.close(ends(1))
        throw e
    }
    (ends(0), ends(1))
  }

  private def openNonBlocking(path: String, write: Boolean): Int = {
    val fd = Zone.acquire { implicit z =>
      fcntl.open(toCString(path), (if write then fcntl.O_WRONLY else fcntl.O_RDONLY) | fcntl.O_NONBLOCK)
    }
    if fd < 0 then throw new IOException(s"Failed to open $path: ${cError()}")
    fd
  }

  private def isKind(fd: Int, kind: stat.mode_t => CInt): Boolean = {
    val info = stackalloc[stat.stat]()
    if stat.fstat(fd, info) != 0 then throw new IOException(s"Failed to inspect file: ${cError()}")
    kind(info.st_mode) != 0
  }

  /** macOS's `fcntl` command for a pipe or FIFO's write end that makes a write after the reader has closed fail with
    * `EPIPE` instead of raising `SIGPIPE`. posixlib does not define it.
    */
  private inline val F_SETNOSIGPIPE = 73

  /** Stops writes to `fd`, a pipe or FIFO's write end, from raising `SIGPIPE`: on macOS with `F_SETNOSIGPIPE`;
    * elsewhere, Linux and FreeBSD, which have no per-descriptor equivalent, by ignoring the signal for the process
    * (`SigPipe`).
    */
  private def noSigPipe(fd: Int): Unit =
    if LinktimeInfo.isMac then { // a link-time condition, kept apart from the runtime one
      if fcntl.fcntl(fd, F_SETNOSIGPIPE, 1) < 0 then throw new IOException(s"Failed to set F_SETNOSIGPIPE: ${cError()}")
    } else SigPipe.ignored: Unit
}
