package gears.async.io
package node

import gears.async.io.node.base.AbstractReadinessOp
import gears.async.io.node.host.ByteSink
import gears.async.io.node.host.ByteSource
import gears.async.io.node.host.NodeBuffer
import gears.async.io.shareable.Moved

import java.nio.ByteBuffer
import scala.scalajs.js.typedarray.TypedArrayBufferOps.*
import scala.scalajs.js.typedarray.Uint8Array

private[node] object Slots {

  /** An op performed with a slot not yet cleared fails, doing nothing: what it opened or took would have nowhere to go.
    */
  def requireEmpty(slot: Slot[?]): Unit =
    if !slot.isEmpty then
      throw new IllegalArgumentException(
        "The op's slot still holds a value, not yet cleared, which the op would overwrite"
      )
}

/** Where a read puts what it read: the caller's buffers, or a slot, as a buffer of a pool's. One read op serves both,
  * over a stream or a file.
  */
private[node] sealed trait ReadInto {

  /** Checked as the read is entered: an `IllegalArgumentException` if it could take nothing. */
  def check(): Unit

  /** The most bytes one read may bring. */
  def room: Int

  /** Asked before any byte is taken from where it is read: fails if it could not be put, so that a read refused has
    * taken none.
    */
  def ready(): Unit = ()

  /** Takes what was read, at most `room` bytes; null at the end, when nothing was. */
  def take(chunk: Uint8Array | Null): Unit

  /** The one buffer the read is into, if it is into one whose memory JavaScript has: a source then copies into it as it
    * takes the bytes. Null otherwise.
    */
  def single: ByteBuffer | Null = null
}

/** Into the caller's buffers, one or several, each filled before the next is begun. */
private[node] final class IntoBuffers(bufs: Array[ByteBuffer]) extends ReadInto {
  def check(): Unit =
    if NodeBuffer.remaining(bufs) == 0 then
      throw new IllegalArgumentException(
        "A read into a buffer with no room could only add nothing, which means the end of the stream"
      )

  def room: Int = NodeBuffer.room(bufs)

  def take(chunk: Uint8Array | Null): Unit = if chunk != null then NodeBuffer.fill(bufs, chunk)

  override val single: ByteBuffer | Null = if bufs.length == 1 && bufs(0).hasArrayBuffer() then bufs(0) else null
}

/** Into a slot, as a buffer of `pool`'s: the chunk Node handed over, with no copy. */
private[node] final class IntoPool(pool: NodePool, slot: Slot[Received]) extends ReadInto {
  def check(): Unit = Slots.requireEmpty(slot)

  def room: Int = pool.bufferSize

  override def ready(): Unit = {
    pool.checkFree()
    if !slot.isEmpty then throw new IllegalArgumentException("The read's slot was filled while it waited")
  }

  def take(chunk: Uint8Array | Null): Unit = slot.set(pool.received(chunk))
}

/** A read of a stream: at once if bytes, or the end, have come, else as the handle's waiter until they do. */
private[node] abstract class AbstractStreamReadOp(handle: StreamReadable, into: ReadInto)
    extends AbstractReadinessOp[StreamReadable](handle) {
  override def onEntry(): Unit = into.check()

  protected final def isRead: Boolean = true

  protected final def attempt(): Moved = {
    into.ready()
    val buf = into.single
    if buf != null then {
      val count = handle.source.readInto(buf)
      if count == ByteSource.NoneYet then Moved.Blocked
      else if count == ByteSource.AtEnd then Moved.EndOfStream
      else Moved(count)
    } else {
      val chunk = handle.source.read(into.room)
      if chunk == null then Moved.Blocked
      else if chunk eq ByteSource.Ended then {
        into.take(null)
        Moved.EndOfStream
      } else {
        into.take(chunk)
        Moved(chunk.length)
      }
    }
  }
}

/** A write to a stream, from the caller's buffers, one or several, each drained before the next is begun: at once if
  * the stream takes bytes, else as the handle's waiter until it does. With nothing to write, it is done, moving
  * nothing.
  */
private[node] abstract class AbstractStreamWriteOp(handle: StreamWritable, bufs: Array[ByteBuffer])
    extends AbstractReadinessOp[StreamWritable](handle) {
  protected final def isRead: Boolean = false

  protected final def attempt(): Moved =
    if NodeBuffer.remaining(bufs) == 0 then Moved(0)
    else {
      val written = handle.sink.write(bufs)
      if written == ByteSink.Blocked then Moved.Blocked else Moved(written)
    }
}
