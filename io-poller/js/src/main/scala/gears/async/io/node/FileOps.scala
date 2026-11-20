package gears.async.io
package node

import gears.async.io.node.base.CallOp
import gears.async.io.node.base.HandleCall
import gears.async.io.node.base.RepeatableOp
import gears.async.io.node.base.SingleUseOp
import gears.async.io.node.host.FifoSource
import gears.async.io.node.host.NodeBuffer
import gears.async.io.node.host.NodeErr
import gears.async.io.node.host.NodeStream
import gears.async.io.node.internal.Fs
import gears.async.io.node.internal.Net
import gears.async.io.shareable.HandleOwner

import java.io.IOException
import java.nio.ByteBuffer
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

private[node] object FileOps {

  /** The most one call reads or writes: a larger buffer takes several performances, as it may anywhere. */
  inline val LargestCall = 1 << 20

  /** For a read or a write as a stream: at the position its handle carries. */
  inline val AtStreamPosition = -1L
}

/** Opens a path without blocking, and hands over what it is once the system has said so: a call Node cannot take back,
  * so a cancel or a time limit that comes before it returns is kept, and what was opened is then closed again, its slot
  * left empty.
  */
private[node] abstract class AbstractOpenPathOp[A <: AnyRef](path: String, write: Boolean, into: Slot[A])
    extends SingleUseOp
    with CallOp {
  override def onEntry(): Unit = Slots.requireEmpty(into)

  /** What the descriptor is handed over as, or a failure if the file is not of the kind the op opens. */
  protected def opened(fd: Int, stats: js.Dynamic): A

  /** Closes the descriptor of what is not to be handed over, then fails the op. */
  private def abandon(fd: Int, failure: Throwable): Unit = {
    val closed: js.Function1[js.Dynamic, Unit] = _ => complete(failure)
    Fs.close(fd, closed)
  }

  private val gotDescriptor: js.Function2[js.Dynamic, js.Dynamic, Unit] = (failure, descriptor) =>
    if NodeErr.failed(failure) then {
      val came = interruptedBy
      complete(if came != null then came else NodeErr.io(failure))
    } else {
      val fd = descriptor.asInstanceOf[Int]
      val gotStats: js.Function2[js.Dynamic, js.Dynamic, Unit] = (failure, stats) => {
        val came = interruptedBy
        if came != null then abandon(fd, came)
        else if NodeErr.failed(failure) then abandon(fd, NodeErr.io(failure))
        else
          try {
            into.set(opened(fd, stats))
            complete(null)
          } catch case refused: Exception => abandon(fd, refused)
      }
      Fs.fstat(fd, gotStats)
    }

  protected final def call(): Unit = {
    claimForCall()
    NodeErr.guard(Fs.open(path, FifoSource.flags(write), gotDescriptor))
  }
}

/** A read of a file, at an offset, which goes on from where it left off when performed again, or as a stream, at the
  * position its handle carries. The call fills a buffer of its own, which is copied out only if the call's outcome
  * stands: stopped meanwhile, the op fails as what stopped it says, the caller's buffers untouched.
  */
private[node] abstract class AbstractFileReadOp(protected val handle: FileHandle, into: ReadInto, offset: Long)
    extends RepeatableOp
    with HandleCall[FileHandle] {
  private val streaming = offset == FileOps.AtStreamPosition
  private var at = offset
  private var chunk: Uint8Array | Null = null

  override def onEntry(): Unit = into.check()

  // On a failure Node gives no count, so it is taken as a number only once there is none.
  private val read: js.Function2[js.Dynamic, js.Dynamic, Unit] = (failure, count) => {
    if streaming then handle.endStream(read = true)
    val dropped = returned()
    complete(
      if dropped != null then dropped
      else if NodeErr.failed(failure) then NodeErr.io(failure)
      else
        try {
          val size = count.asInstanceOf[Int]
          into.ready() // refused, the bytes stay the file's: the position is where it was
          into.take(if size == 0 then null else chunk.nn.subarray(0, size))
          if streaming then handle.position += size else at += size
          null
        } catch case refused: Exception => refused
    )
    chunk = null
  }

  protected final def call(): Unit = calling {
    if handle.writable then throw new IOException(s"$handle was opened for writing, not reading")
    val buffer = new Uint8Array(math.min(into.room, FileOps.LargestCall))
    if streaming then handle.beginStream(read = true, this)
    chunk = buffer
    try Fs.read(handle.fd, buffer, 0, buffer.length, (if streaming then handle.position else at).toDouble, read)
    catch {
      case js.JavaScriptException(failure) =>
        if streaming then handle.endStream(read = true)
        throw NodeErr.io(failure)
    }
  }
}

/** A write of a file, at an offset, which goes on from where it left off, or as a stream. The buffers are passed over
  * by what the call wrote, if its outcome stands.
  */
private[node] abstract class AbstractFileWriteOp(
    protected val handle: FileHandle,
    bufs: Array[ByteBuffer],
    offset: Long
) extends RepeatableOp
    with HandleCall[FileHandle] {
  private val streaming = offset == FileOps.AtStreamPosition
  private var at = offset

  private val wrote: js.Function2[js.Dynamic, js.Dynamic, Unit] = (failure, count) => {
    if streaming then handle.endStream(read = false)
    val dropped = returned()
    complete(
      if dropped != null then dropped
      else if NodeErr.failed(failure) then NodeErr.io(failure)
      else {
        val size = count.asInstanceOf[Int]
        NodeBuffer.skip(bufs, size)
        if streaming then handle.position += size else at += size
        null
      }
    )
  }

  /** With nothing remaining it is done, writing nothing, on a file that is open. */
  protected final def call(): Unit =
    if NodeBuffer.remaining(bufs) == 0 then {
      handle.checkOpen()
      complete(null)
    } else
      calling {
        if !handle.writable then throw new IOException(s"$handle was opened for reading, not writing")
        // Copied without consuming: the buffers are passed over by what the call wrote, if it completes.
        val chunk = NodeBuffer.copy(bufs, math.min(NodeBuffer.room(bufs), FileOps.LargestCall))
        if streaming then handle.beginStream(read = false, this)
        try Fs.write(handle.fd, chunk, 0, chunk.length, (if streaming then handle.position else at).toDouble, wrote)
        catch {
          case js.JavaScriptException(failure) =>
            if streaming then handle.endStream(read = false)
            throw NodeErr.io(failure)
        }
      }
}

/** A sync of a file's written bytes to its device. */
private[node] abstract class AbstractSyncOp(protected val handle: FileHandle)
    extends RepeatableOp
    with HandleCall[FileHandle] {
  private val synced: js.Function1[js.Dynamic, Unit] = failure => {
    val dropped = returned()
    complete(if dropped != null then dropped else if NodeErr.failed(failure) then NodeErr.io(failure) else null)
  }

  protected final def call(): Unit = calling(NodeErr.guard(Fs.fsync(handle.fd, synced)))
}

private[node] object Fifos {

  /** A FIFO's read end, over its descriptor (see `FifoSource`). */
  def readEnd(fd: Int, owner: HandleOwner): ReadEnd = new ReadEnd("FIFO's read end", owner, new FifoSource(fd))

  /** A FIFO's write end: a stream of Node's over its descriptor. */
  def writeEnd(fd: Int, owner: HandleOwner): WriteEnd = {
    val socket = new Net.Socket(js.Dynamic.literal(fd = fd, readable = false, writable = true))
    new WriteEnd(
      "FIFO's write end",
      owner,
      new NodeStream(socket.asInstanceOf[js.Dynamic], readable = false, writable = true)
    )
  }
}
