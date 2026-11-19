package gears.async.io
package posix

import java.io.IOException
import java.nio.ByteBuffer
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import PosixErr.cError

/** The file calls, which block for the disk: `pread`, `pwrite` and `fsync`, through posixlib's `@blocking` bindings, so
  * that a collection need not wait for the disk either. For threads that may block, never a worker's.
  */
object PosixFiles {

  /** Fills `buf` from `fd` at `offset`, moving its position: the bytes read, 0 at the end of the file. */
  def readAt(fd: Int, buf: ByteBuffer, offset: Long): Int =
    NativeBuffer.staged(buf, copyIn = false, copyOut = true) { b =>
      val read = unistd.pread(fd, NativeBuffer.atPosition(b), b.remaining().toCSize, offset.toSize)
      if read < 0 then throw new IOException(s"Failed to read descriptor $fd at $offset: ${cError()}")
      NativeBuffer.advance(b, read.toInt)
      read.toInt
    }

  /** Drains `buf` into `fd` at `offset`, moving its position: the bytes written. */
  def writeAt(fd: Int, buf: ByteBuffer, offset: Long): Int =
    NativeBuffer.staged(buf, copyIn = true, copyOut = false) { b =>
      val written = unistd.pwrite(fd, NativeBuffer.atPosition(b), b.remaining().toCSize, offset.toSize)
      if written < 0 then throw new IOException(s"Failed to write descriptor $fd at $offset: ${cError()}")
      NativeBuffer.advance(b, written.toInt)
      written.toInt
    }

  def sync(fd: Int): Unit =
    if unistd.fsync(fd) < 0 then throw new IOException(s"Failed to sync descriptor $fd: ${cError()}")
}
