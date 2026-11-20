package gears.async.io
package uring

import gears.async.io.posix.Unblocked

import java.nio.ByteBuffer
import scala.scalanative.posix.errno
import scala.scalanative.posix.sys.uio
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

private[uring] object NonBlocking {

  private def wouldBlock: Boolean =
    val err = errno.errno
    err == errno.EAGAIN || err == errno.EWOULDBLOCK || err == errno.EINTR

  inline val EOF = -1

  def read(fd: Int, buf: ByteBuffer): Int =
    NativeBuffer.staged(buf, copyIn = false, copyOut = true)(readAddressable(fd, _))

  private def readAddressable(fd: Int, buf: ByteBuffer): Int = {
    val read = Unblocked.read(fd, NativeBuffer.atPosition(buf), buf.remaining().toCSize)
    if read < 0 then
      if !wouldBlock then throw Results.failed(s"Failed to read from descriptor $fd")
      0
    else if read == 0 then EOF
    else
      NativeBuffer.advance(buf, read.toInt)
      read.toInt
  }

  inline val MaxBuffers = 1024

  def readv(fd: Int, bufs: Array[java.nio.ByteBuffer]): Int =
    NativeBuffer.staged(bufs, copyIn = false, copyOut = true)(readvAddressable(fd, _))

  private def readvAddressable(fd: Int, bufs: Array[java.nio.ByteBuffer]): Int = {
    val vectors = stackalloc[uio.iovec](math.min(bufs.length, MaxBuffers))
    val count = fill(vectors, bufs, room = true)
    val read = Unblocked.readv(fd, vectors, count)
    if read < 0 then
      if !wouldBlock then throw Results.failed(s"Failed to read from descriptor $fd")
      0
    else if read == 0 then EOF
    else
      advance(bufs, read.toLong)
      read.toInt
  }

  def writev(fd: Int, bufs: Array[java.nio.ByteBuffer]): Int =
    NativeBuffer.staged(bufs, copyIn = true, copyOut = false)(writevAddressable(fd, _))

  private def writevAddressable(fd: Int, bufs: Array[java.nio.ByteBuffer]): Int = {
    val vectors = stackalloc[uio.iovec](math.min(bufs.length, MaxBuffers))
    val count = fill(vectors, bufs, room = false)
    val written = Unblocked.writev(fd, vectors, count)
    if written < 0 then {
      if !wouldBlock then throw Results.failed(s"Failed to write to descriptor $fd")
      -1
    } else {
      advance(bufs, written.toLong)
      written.toInt
    }
  }

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

  def write(fd: Int, buf: ByteBuffer): Int =
    NativeBuffer.staged(buf, copyIn = true, copyOut = false)(writeAddressable(fd, _))

  private def writeAddressable(fd: Int, buf: ByteBuffer): Int = {
    val written = Unblocked.write(fd, NativeBuffer.atPosition(buf), buf.remaining().toCSize)
    if written < 0 then {
      if !wouldBlock then throw Results.failed(s"Failed to write to descriptor $fd")
      -1
    } else {
      NativeBuffer.advance(buf, written.toInt)
      written.toInt
    }
  }
}
