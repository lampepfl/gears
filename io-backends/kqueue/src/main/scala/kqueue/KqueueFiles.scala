package gears.async.io
package kqueue

import gears.async.io.Repeatable
import gears.async.io.internal.NativeBuffer
import gears.async.io.internal.PosixErr.cError

import java.io.IOException
import java.nio.ByteBuffer
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import KqueueBackend.Handle

/** The file ops (see `FileOps`), each a blocking task on the blocker pool, since the operating system's file I/O blocks
  * for the disk: `open`, `pread`, `pwrite` and `fsync`, through posixlib's `@blocking` bindings, so that a collection
  * need not wait for the disk either. Each is a use of its handle's descriptor, which a close waits for; one whose
  * handle was closed meanwhile fails once its call is done (`KqueueHandles.checkStillOpen`), as on NIO.
  */

/** Opens a regular file and publishes its handle (see `FileOps.open`). A cancel that arrives once the file is open
  * closes it again, since the handle never reaches the caller.
  */
final class OpenFile(path: String, write: Boolean, into: Slot[KqueueKinds.File]) extends KqueueBlocking {
  gears.async.io.internal.Checks.checkPath(path)
  override private[io] def slotRefusal: Throwable | Null = gears.async.io.internal.Checks.notEmpty(into)
  def block(): Unit = {
    val handle = KqueueHandles.openFile(path, write)
    if !unlessCancelled(into.set(handle)) then KqueueHandles.close(handle)
  }
}

/** A read at an offset (see `FileOps.read`): fills `buf` from the file at the op's offset, which each completion
  * advances by the bytes read, so that submitting the op again goes on from there.
  */
final class PositionalRead(handle: Handle, buf: ByteBuffer, private var offset: Long)
    extends KqueueBlocking
    with Repeatable {
  gears.async.io.internal.Checks.checkFillable(buf)
  gears.async.io.internal.Checks.checkOffset(offset)
  override private[io] def refusal: Throwable | Null =
    if buf.hasRemaining() then null else gears.async.io.internal.Checks.bufferFull(buf)
  def block(): Unit = KqueueHandles.inUse(handle) {
    val fd = handle.fd
    NativeBuffer.staged(buf, copyIn = false, copyOut = true) { b =>
      val read = unistd.pread(fd, NativeBuffer.atPosition(b), b.remaining().toCSize, offset.toSize)
      if read < 0 then throw new IOException(s"Failed to read descriptor $fd at $offset: ${cError()}")
      KqueueHandles.checkStillOpen(handle)
      val n = read.toInt
      unlessCancelled {
        NativeBuffer.advance(b, n)
        offset += n
      }
    }
  }
}

/** A write at an offset (see `FileOps.write`): drains `buf` into the file at the op's offset, which each completion
  * advances by the bytes written.
  */
final class PositionalWrite(handle: Handle, buf: ByteBuffer, private var offset: Long)
    extends KqueueBlocking
    with Repeatable {
  gears.async.io.internal.Checks.checkOffset(offset)
  def block(): Unit = KqueueHandles.inUse(handle) { // a closed handle fails first, with nothing to write too
    if buf.hasRemaining() then writeAt(handle.fd)
  }

  private def writeAt(fd: Int): Unit = NativeBuffer.staged(buf, copyIn = true, copyOut = false) { b =>
    val written = unistd.pwrite(fd, NativeBuffer.atPosition(b), b.remaining().toCSize, offset.toSize)
    if written < 0 then throw new IOException(s"Failed to write descriptor $fd at $offset: ${cError()}")
    KqueueHandles.checkStillOpen(handle)
    val n = written.toInt
    unlessCancelled {
      NativeBuffer.advance(b, n)
      offset += n
    }
  }
}

/** `fsync` (see `FileOps.sync`). */
final class FileSync(handle: Handle) extends KqueueBlocking with Repeatable {
  def block(): Unit = KqueueHandles.inUse(handle) {
    val fd = handle.fd
    if unistd.fsync(fd) < 0 then throw new IOException(s"Failed to sync descriptor $fd: ${cError()}")
    KqueueHandles.checkStillOpen(handle)
  }
}
