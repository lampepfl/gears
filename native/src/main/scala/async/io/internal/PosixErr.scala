package gears.async.io
package internal

import scala.scalanative.posix.errno
import scala.scalanative.posix.string
import scala.scalanative.unsafe.*

object PosixErr {
  def cError(): String = {
    fromCString(string.strerror(errno.errno))
  }
}
