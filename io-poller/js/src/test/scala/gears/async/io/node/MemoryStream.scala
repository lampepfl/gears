package gears.async.io
package node

import gears.async.io.node.host.ByteSink
import gears.async.io.node.host.ByteSource
import gears.async.io.node.host.NodeBuffer

import java.io.IOException
import java.nio.ByteBuffer
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/** A stream held in memory, for the suites: bytes between a read end and a write end within the program, at most
  * `Capacity` of them, so that a writer waits for its reader. The backend has no `Pipes`: Node makes no pipe of the
  * system's but for a child process, and one that is only memory is nothing a program needs from a backend. As a
  * fixture, though, it puts the backend's stream ops, the same that read and write its sockets, over a source and a
  * sink with no system behind them, where every wait and every wake-up is the backend's own.
  */
private[node] final class MemoryStream extends ByteSource with ByteSink {
  private val chunks = js.Array[Uint8Array]()
  private var held = 0
  private var writerGone = false
  private var readerGone = false

  private def tell(listener: (() => Unit) | Null): Unit = if listener != null then listener()

  def read(max: Int): Uint8Array | Null =
    if held == 0 then if writerGone then ByteSource.Ended else null
    else {
      val first = chunks(0)
      val taken =
        if first.length <= max then {
          chunks.shift()
          first
        } else {
          chunks(0) = first.subarray(max)
          first.subarray(0, max)
        }
      held -= taken.length
      tell(onWritable)
      taken
    }

  def write(bufs: Array[ByteBuffer]): Int =
    if readerGone then throw new IOException("Broken pipe: its read end is closed")
    else if held == MemoryStream.Capacity then ByteSink.Blocked
    else {
      val chunk = NodeBuffer.drain(bufs, math.min(MemoryStream.Capacity - held, NodeBuffer.room(bufs)))
      chunks.push(chunk)
      held += chunk.length
      tell(onReadable)
      chunk.length
    }

  def close(done: () => Unit): Boolean = true

  /** The read end has closed: what is held is dropped, and writes fail from here on. */
  def closeReader(): Unit = {
    readerGone = true
    chunks.length = 0
    held = 0
    tell(onWritable)
  }

  /** The write end has closed: the stream ends after what is held. */
  def closeWriter(): Unit = {
    writerGone = true
    tell(onReadable)
  }
}

private[node] object MemoryStream {
  inline val Capacity = 65536
}

/** One side of a memory stream, as the source or the sink its handle closes. */
private final class MemorySide(stream: MemoryStream, reads: Boolean) extends ByteSource with ByteSink {
  def read(max: Int): Uint8Array | Null = stream.read(max)
  def write(bufs: Array[ByteBuffer]): Int = stream.write(bufs)

  def close(done: () => Unit): Boolean = {
    if reads then stream.closeReader() else stream.closeWriter()
    true
  }
}

/** Supplies the stream fixture from memory, for a suite over the Node.js backend itself. */
trait MemoryStreamFixture extends gears.async.io.testkit.StreamFixture {
  val backend: NodeBackend.type

  protected def stream(
      io: Io[backend.Interface]
  )(using gears.async.Async.Spawn): (io.ops.Readable, io.ops.Writable) = {
    val memory = new MemoryStream
    val reads = new MemorySide(memory, reads = true)
    val writes = new MemorySide(memory, reads = false)
    val owner = io.asInstanceOf[NodeRun].owner
    val in = new ReadEnd("memory stream's read end", owner, reads)
    val out = new WriteEnd("memory stream's write end", owner, writes)
    // The handles gave each side its listener; the stream tells through them.
    memory.onReadable = () => { val tell = reads.onReadable; if tell != null then tell() }
    memory.onWritable = () => { val tell = writes.onWritable; if tell != null then tell() }
    (in, out)
  }
}
