package gears.async.io
package nio

import gears.async.io.shareable.HandleOwner

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.NonReadableChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** The file calls, which block for the disk, on the JDK's file channels. For threads that may block, never a worker's.
  */
private[nio] object NioFiles {

  /** Opens the regular file at `path`, which must exist; anything else is refused. */
  def open(path: String, write: Boolean, owner: HandleOwner): NioFile = {
    val file = Path.of(path)
    if !Files.isRegularFile(file) then throw new IOException(s"Not a regular file: $path")
    val channel = FileChannel.open(file, if write then StandardOpenOption.WRITE else StandardOpenOption.READ)
    try new NioFile(channel, owner)
    catch
      case failure: Throwable =>
        channel.close()
        throw failure
  }

  /** A file's channel used the way it was not opened fails as I/O does, not as a program's error. */
  private inline def asIo[T](inline body: T): T =
    try body
    catch
      case _: NonReadableChannelException => throw new IOException("The file was opened for writing, not reading")
      case _: NonWritableChannelException => throw new IOException("The file was opened for reading, not writing")

  /** Fills `buf` from `file` at `offset`, moving its position: the bytes read, 0 at the end of the file. */
  def readAt(file: NioFile, buf: ByteBuffer, offset: Long): Int = asIo(Math.max(file.file.read(buf, offset), 0))

  /** Drains `buf` into `file` at `offset`, moving its position: the bytes written. */
  def writeAt(file: NioFile, buf: ByteBuffer, offset: Long): Int = asIo(file.file.write(buf, offset))

  def sync(file: NioFile): Unit = file.file.force(true)
}
