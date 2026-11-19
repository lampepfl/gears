package gears.async.io
package posix

import java.io.IOException
import scala.scalanative.posix.errno
import scala.scalanative.posix.sys.stat
import scala.scalanative.posix.sys.statOps.*
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*

import PosixErr.cError

object PosixFileOps {

  /** The file at `path`, not following a link, as its device and inode, and whether it is a socket; null if there is
    * none.
    */
  final case class FileId(device: Long, inode: Long, socket: Boolean)

  def fileId(path: CString): FileId | Null = {
    val info = stackalloc[stat.stat]()
    if stat.lstat(path, info) != 0 then { // posixlib's wrapper returns 1 on failure, not -1
      if errno.errno == errno.ENOENT then null
      else throw new IOException(s"Failed to inspect ${fromCString(path)}: ${cError()}")
    } else FileId(info.st_dev.toLong, info.st_ino.toLong, stat.S_ISSOCK(info.st_mode) != 0)
  }

  /** Removes the socket file at `path` if it is still `id`, the one a listener bound: not one a later listener bound in
    * its place, which closing the first must leave.
    */
  def unlinkIfStill(path: CString, id: FileId): Unit = {
    val now = fileId(path)
    if now != null && now.nn.device == id.device && now.nn.inode == id.inode then safeUnlink(path)
  }
  def safeUnlink(path: CString): Unit = {
    val rmErr = unistd.unlink(path) // remove the socket file if it already exists
    if (rmErr < 0 && errno.errno != errno.ENOENT) {
      throw new IOException(
        s"Failed to unlink existing socket at $path: ${cError()}"
      )
    }
  }
}
