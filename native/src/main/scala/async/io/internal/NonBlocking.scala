package gears.async.io
package internal

import java.io.IOException
import java.nio.ByteBuffer
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.errno
import scala.scalanative.posix.sys.socket
import scala.scalanative.posix.sys.uio
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import PosixErr.cError

/** Non-blocking reads, writes, and accepts that report would-block and EOF as values instead of exceptions. The
  * descriptors must be non-blocking: the calls go through `Unblocked`, which tells the GC nothing, as they return at
  * once.
  */
object NonBlocking {

  private def wouldBlock: Boolean =
    val err = errno.errno
    err == errno.EAGAIN || err == errno.EWOULDBLOCK || err == errno.EINTR

  inline val EOF = -1

  /** Accepts a new connection on `serverFd`, which must be non-blocking, and returns it non-blocking too. Returns the
    * client file descriptor, or -1 if no client was accepted. On macOS and FreeBSD that takes the `accept` alone: an
    * accepted socket has the listening socket's properties, `O_NONBLOCK` among them (see accept(2)).
    */
  def accept(serverFd: Int): Int = {
    var clientFd = Unblocked.accept(serverFd, null, null)
    // A connection its client reset while it was queued is gone, not a failure of the listener: the next one, as the
    // JDK's accept does.
    while clientFd < 0 && errno.errno == errno.ECONNABORTED do clientFd = Unblocked.accept(serverFd, null, null)
    if clientFd < 0 then {
      val transient = wouldBlock
      if !transient then throw new IOException(s"Failed to accept connection: ${cError()}")
      -1 // no client was accepted
    } else {
      if !(LinktimeInfo.isMac || LinktimeInfo.isFreeBSD) then PosixSockets.setNonBlocking(clientFd)
      clientFd
    }
  }

  /** Fills `buf` from `fd` between position and limit. Returns the number of bytes read, 0 if the read would block, or
    * -1 at EOF.
    */
  def read(fd: Int, buf: ByteBuffer): Int =
    NativeBuffer.staged(buf, copyIn = false, copyOut = true)(readAddressable(fd, _))

  private def readAddressable(fd: Int, buf: ByteBuffer): Int = {
    val read = Unblocked.read(fd, NativeBuffer.atPosition(buf), buf.remaining().toCSize)
    if read < 0 then
      if !wouldBlock then throw new IOException(s"Failed to read from descriptor $fd: ${cError()}")
      0
    else if read == 0 then EOF
    else
      NativeBuffer.advance(buf, read.toInt)
      read.toInt
  }

  /** The most buffers one `readv` or `writev` takes (`IOV_MAX` on macOS and Linux): beyond them, an op takes the rest
    * on its next submission, as it does what one call did not finish.
    */
  inline val MaxBuffers = 1024

  /** Fills `bufs` from `fd` in order, as `read` fills one, with one `readv`: the bytes read, 0 if it would block, or -1
    * at end of stream, which leaves the buffers as they were.
    */
  def readv(fd: Int, bufs: Array[java.nio.ByteBuffer]): Int =
    NativeBuffer.staged(bufs, copyIn = false, copyOut = true)(readvAddressable(fd, _))

  private def readvAddressable(fd: Int, bufs: Array[java.nio.ByteBuffer]): Int = {
    val vectors = stackalloc[uio.iovec](math.min(bufs.length, MaxBuffers))
    val count = fill(vectors, bufs, room = true)
    val read = Unblocked.readv(fd, vectors, count)
    if read < 0 then
      if !wouldBlock then throw new IOException(s"Failed to read from descriptor $fd: ${cError()}")
      0
    else if read == 0 then EOF
    else
      advance(bufs, read.toLong)
      read.toInt
  }

  /** Drains `bufs` into `fd` in order, as `write` drains one, with one `writev`: the bytes written, or -1 if it would
    * block.
    */
  def writev(fd: Int, bufs: Array[java.nio.ByteBuffer]): Int =
    NativeBuffer.staged(bufs, copyIn = true, copyOut = false)(writevAddressable(fd, _))

  private def writevAddressable(fd: Int, bufs: Array[java.nio.ByteBuffer]): Int = {
    val vectors = stackalloc[uio.iovec](math.min(bufs.length, MaxBuffers))
    val count = fill(vectors, bufs, room = false)
    val written = Unblocked.writev(fd, vectors, count)
    if written < 0 then {
      if !wouldBlock then throw new IOException(s"Failed to write to descriptor $fd: ${cError()}")
      -1
    } else {
      advance(bufs, written.toLong)
      written.toInt
    }
  }

  /** Points `vectors` at `bufs`, those with room or bytes remaining, in order, at most `MaxBuffers`: their count. */
  def fill(vectors: Ptr[uio.iovec], bufs: Array[java.nio.ByteBuffer], room: Boolean): Int = {
    var count = 0
    var i = 0
    while i < bufs.length && count < MaxBuffers do {
      val buf = bufs(i)
      if buf.hasRemaining() then {
        val v = vectors + count
        v._1 = NativeBuffer.atPosition(buf)
        v._2 = buf.remaining().toCSize
        count += 1
      }
      i += 1
    }
    count
  }

  /** Advances `bufs` in order by `bytes` all told, each to its limit before the next. */
  def advance(bufs: Array[java.nio.ByteBuffer], bytes: Long): Unit = {
    var left = bytes
    var i = 0
    while left > 0 && i < bufs.length do {
      val n = math.min(left, bufs(i).remaining().toLong).toInt
      if n > 0 then NativeBuffer.advance(bufs(i), n)
      left -= n
      i += 1
    }
  }

  /** Drains `buf` into `fd` between position and limit. Returns the number of bytes written, or -1 if the write would
    * block.
    */
  def write(fd: Int, buf: ByteBuffer): Int =
    NativeBuffer.staged(buf, copyIn = true, copyOut = false)(writeAddressable(fd, _))

  private def writeAddressable(fd: Int, buf: ByteBuffer): Int = {
    val written = Unblocked.write(fd, NativeBuffer.atPosition(buf), buf.remaining().toCSize)
    if written < 0 then {
      if !wouldBlock then throw new IOException(s"Failed to write to descriptor $fd: ${cError()}")
      -1
    } else {
      NativeBuffer.advance(buf, written.toInt)
      written.toInt
    }
  }
}
