package gears.async.io.node.host

import java.nio.ByteBuffer
import scala.scalajs.js.typedarray.Uint8Array

/** Where a stream's bytes come from, as Node has them, read without waiting: a read says at once what there is, and
  * would-block and the end of the stream are values, not failures. Whoever owns the source, its handle, is told when a
  * read that would have blocked may no longer (`onReadable`), once for the handle, not for each op that waits.
  */
trait ByteSource {

  /** At most `max` bytes that have arrived, as a chunk of Node's own; `ByteSource.Ended` at the end of the stream; or
    * null if a read would block. Fails with an `IOException` if the stream has failed.
    */
  def read(max: Int): Uint8Array | Null

  /** As `read`, into `buf`, which has room and whose memory JavaScript has (`hasArrayBuffer`), its position advanced:
    * how many bytes, `ByteSource.NoneYet` if a read would block, or `ByteSource.AtEnd`. A source that can copy as it
    * takes does both in one step.
    */
  def readInto(buf: ByteBuffer): Int = {
    val chunk = read(buf.remaining())
    if chunk == null then ByteSource.NoneYet
    else if chunk eq ByteSource.Ended then ByteSource.AtEnd
    else {
      NodeBuffer.fillFrom(buf, chunk, chunk.length)
      chunk.length
    }
  }

  /** Called when bytes, the end or a failure may have come: set by the source's handle. */
  var onReadable: (() => Unit) | Null = null

  /** An op begins to wait for the source, or stops: for a source that must do something to learn what has come. */
  def listening(on: Boolean): Unit = ()

  /** Closes the source: true if it is closed as this returns, else `done` is called once it is. */
  def close(done: () => Unit): Boolean
}

object ByteSource {

  /** What `read` gives at the end of the stream. */
  val Ended: Uint8Array = new Uint8Array(0)

  /** What `readInto` gives if a read would block, and at the end of the stream. */
  inline val NoneYet = 0
  inline val AtEnd = -1
}

/** Where a stream's bytes go, as Node takes them, written without waiting. */
trait ByteSink {

  /** Takes bytes from `bufs`, which have some, in order: how many, at least one, or `ByteSink.Blocked` if a write would
    * block, when none was taken. Fails with an `IOException` if the stream takes no more.
    */
  def write(bufs: Array[ByteBuffer]): Int

  /** Called when a write that would have blocked may no longer, or writes have begun to fail. */
  var onWritable: (() => Unit) | Null = null

  /** Closes the sink: true if it is closed as this returns, else `done` is called once it is. */
  def close(done: () => Unit): Boolean
}

object ByteSink {
  inline val Blocked = -1
}
