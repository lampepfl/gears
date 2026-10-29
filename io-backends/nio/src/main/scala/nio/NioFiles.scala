package gears.async.io
package nio

import gears.async.io.Repeatable

import java.io.IOException
import java.nio.ByteBuffer

import NioCapabilities.Handle

/** The file ops (see `FileOps`), each a blocking task on the blocker pool, since `FileChannel` blocks for the disk:
  * opening, positional reads and writes, and `force`.
  */

/** Opens a regular file and publishes its handle (see `FileOps.open`). A cancel that arrives once the file is open
  * closes it again, since the handle never reaches the caller.
  */
final class OpenFile(path: String, write: Boolean, into: Slot[NioKinds.File]) extends NioBlocking {
  gears.async.io.internal.Checks.checkPath(path)
  override private[io] def slotRefusal: Throwable | Null = gears.async.io.internal.Checks.notEmpty(into)
  def block(): Unit = {
    val handle = NioHandles.openFile(path, write)
    gears.async.io.internal.ResourcePublication(handle)(opened => unlessCancelled(into.set(opened)))(opened =>
      NioHandles.close(opened)
    )
  }
}

/** A file op on the channel of a regular file: the handle is resolved when the task runs, so that one closed meanwhile
  * fails the op. A cancel does not interrupt it, since an interrupt closes a `FileChannel`
  * (`ClosedByInterruptException`), which would close the file under the caller: it runs to its end, as a native `pread`
  * does on kqueue. One whose handle is closed while it runs fails as an op on a closed handle does, whether the channel
  * refused it or it finished first (`onFile`), its buffer and offset left as they were, as on kqueue.
  */
sealed abstract class FileTask(val handle: Handle) extends NioBlocking with Repeatable {
  override protected def interruptible: Boolean = false

  /** Runs `io`, the task's call on its file, failing as an op on a closed handle does if the handle was closed
    * meanwhile: the JDK's `AsynchronousCloseException`, or a call that finished before the close took effect.
    */
  protected final inline def onFile[A](inline io: A): A = {
    val result =
      try io
      catch case _: java.nio.channels.ClosedChannelException => throw closedNow
    if !handle.isOpen then throw closedNow
    result
  }

  private def closedNow: Throwable = gears.async.io.internal.HandleClosedException.pending(handle)

  protected def regularFile: RegularFile = NioHandles.entry(handle) match {
    case file: RegularFile => file
    case other             => throw new IOException(s"Handle $handle is not a regular file")
  }
}

/** A read at an offset (see `FileOps.read`): fills `buf` from the file at the op's offset, which each completion
  * advances by the bytes read, so that submitting the op again goes on from there.
  */
final class PositionalRead(handle: Handle, buf: ByteBuffer, private var offset: Long) extends FileTask(handle) {
  gears.async.io.internal.Checks.checkFillable(buf)
  gears.async.io.internal.Checks.checkOffset(offset)
  override private[io] def refusal: Throwable | Null =
    if buf.hasRemaining() then null else gears.async.io.internal.Checks.bufferFull(buf)
  def block(): Unit = {
    val file = regularFile // a closed handle fails first
    // Into a view of `buf`, its position and the offset then moved together, so that a cancel before that leaves both
    // as they were, the op ready to be submitted again.
    val view = buf.duplicate()
    val read = onFile(file.read(view, offset)) // -1 at the end
    if read > 0 then
      unlessCancelled {
        buf.position(view.position())
        offset += read
      }: Unit
  }
}

/** A write at an offset (see `FileOps.write`): drains `buf` into the file at the op's offset, which each completion
  * advances by the bytes written.
  */
final class PositionalWrite(handle: Handle, buf: ByteBuffer, private var offset: Long) extends FileTask(handle) {
  gears.async.io.internal.Checks.checkOffset(offset)
  def block(): Unit = {
    val file = regularFile // a closed handle fails first, with nothing to write too
    val view = buf.duplicate() // as a read's, so that a cancel leaves the buffer and offset together
    val written = if buf.hasRemaining() then onFile(file.write(view, offset)) else 0
    if written > 0 then
      unlessCancelled {
        buf.position(view.position())
        offset += written
      }: Unit
  }
}

/** `force` (see `FileOps.sync`). */
final class FileSync(handle: Handle) extends FileTask(handle) {
  def block(): Unit = {
    val file = regularFile
    onFile(file.force())
  }
}
