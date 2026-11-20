package gears.async.io
package uring

import java.io.IOException
import java.nio.channels.ClosedChannelException
import scala.scalanative.libc.string
import scala.scalanative.posix.errno
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

private[uring] object Results {

  val currentPosition: uring.__u64 = (-1L).toULong

  def transient(res: Int): Boolean = res == -errno.EINTR || res == -errno.EAGAIN

  def failure(what: String, res: Int): IOException = new SystemFailure(s"$what: ${fromCString(string.strerror(-res))}")

  def failed(what: String): IOException = failure(what, -errno.errno)

  final class SystemFailure(message: String) extends IOException(message) {
    override def fillInStackTrace(): Throwable = this
  }
}

final class HandleClosedException(handle: AnyRef) extends ClosedChannelException {
  override def getMessage: String = s"$handle is closed"
  override def fillInStackTrace(): Throwable = this
}
