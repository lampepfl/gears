package gears.async.io.node.host

import java.io.IOException
import java.nio.ByteBuffer
import scala.scalajs.js
import scala.scalajs.js.typedarray.TypedArrayBufferOps.*
import scala.scalajs.js.typedarray.Uint8Array

/** A stream of Node's, a `net.Socket`, read and written without waiting.
  *
  * It is read as Node's flowing streams are: Node hands over each chunk as it takes it from the system, and a read that
  * is waiting takes it there and then. A chunk no read is waiting for is kept here and Node is told to pause, which has
  * it keep what else it takes, up to its high-water mark, and then leave the rest to the system, so a peer that sends
  * to a program that does not read is held back as the system would hold it; the read that takes the last of what is
  * kept has Node resume. What is kept is the chunks Node had handed over by the time it paused: one, as a rule.
  *
  * It is written one chunk at a time, of at most `ChunkSize` bytes, the next not taken until Node has handed the last to
  * the system (its `writableLength` says what it holds): Node itself takes whatever it is given, without bound, and a
  * write it has taken cannot be taken back, so a write here would block before Node has anything of it, and one that
  * waited and was then cancelled has written nothing. What Node holds, then, is one chunk at most, as a socket's send buffer a little larger.
  *
  * Its listeners are Node's for the life of the stream, its functions made once: an op that waits adds none.
  */
private[node] final class NodeStream(socket: js.Dynamic, readable: Boolean, writable: Boolean)
    extends ByteSource
    with ByteSink {
  private var endSeen = false
  private var error: IOException | Null = null
  private var closed = false
  private var flushWatched = false
  private var shutDown = !writable

  private def tellReader(): Unit = {
    val tell = onReadable
    if tell != null then tell()
  }

  private def tellWriter(): Unit = {
    val tell = onWritable
    if tell != null then tell()
  }

  // The chunks handed over that no read has taken: the first, from `firstAt`, and any that came behind it.
  private var first: Uint8Array | Null = null
  private var firstAt = 0
  private var firstLength = 0
  private val behind = scala.collection.mutable.ArrayDeque.empty[Uint8Array]
  private var paused = false

  private val dataSeen: js.Function1[Uint8Array, Unit] = chunk => {
    if first == null then {
      first = chunk
      firstAt = 0
      firstLength = chunk.length
    } else behind.append(chunk)
    tellReader()
    // Whatever a waiting read did not take is kept, and Node hands over no more until it is taken.
    if first != null && !paused then {
      paused = true
      socket.pause(): Unit
    }
  }

  /** `count` bytes were taken of the first chunk kept: the next takes its place, and Node resumes after the last. */
  private def taken(count: Int): Unit = {
    firstAt += count
    if firstAt == firstLength then
      if behind.isEmpty then {
        first = null
        if paused then {
          paused = false
          if !closed then socket.resume(): Unit
        }
      } else {
        val next = behind.removeHead()
        first = next
        firstAt = 0
        firstLength = next.length
      }
  }
  private val endOfStream: js.Function0[Unit] = () => {
    endSeen = true
    tellReader()
  }
  private val failed: js.Function1[js.Dynamic, Unit] = failure => {
    if error == null then error = NodeErr.io(failure)
    tellReader()
    tellWriter()
  }
  private val closedSeen: js.Function0[Unit] = () => {
    closed = true
    tellReader()
    tellWriter()
  }

  /** Called once Node has handed the system everything it was given before a write found it still holding some: only
    * then is a writer waiting to be told. A write that Node hands straight to the system, as most are, has no callback
    * at all.
    */
  private val flushed: js.Function1[js.Dynamic, Unit] = failure => {
    flushWatched = false
    if NodeErr.failed(failure) && error == null then error = NodeErr.io(failure)
    tellWriter()
  }

  /** What Node holds of the bytes written, not yet handed to the system. */
  private def unsent: Int = socket.writableLength.asInstanceOf[Int]

  socket.on("error", failed)
  socket.on("close", closedSeen)
  if readable then {
    socket.on("end", endOfStream)
    socket.on("data", dataSeen)
  }

  def read(max: Int): Uint8Array | Null = {
    val chunk = if closed then null else first
    if chunk != null then {
      val count = math.min(max, firstLength - firstAt)
      val part = if count == firstLength then chunk else chunk.subarray(firstAt, firstAt + count)
      taken(count)
      part
    } else if error != null then throw error.nn
    else if endSeen || closed then ByteSource.Ended
    else null
  }

  override def readInto(buf: ByteBuffer): Int = {
    val chunk = if closed then null else first
    if chunk != null then {
      val position = buf.position()
      val count = math.min(buf.remaining(), firstLength - firstAt)
      NodeGlue.copyIn(chunk, firstAt, count, buf.arrayBuffer(), buf.arrayBufferOffset() + position)
      buf.position(position + count)
      taken(count)
      count
    } else if error != null then throw error.nn
    else if endSeen || closed then ByteSource.AtEnd
    else ByteSource.NoneYet
  }

  /** Node holds bytes written before: the writer is told once they have gone. An empty chunk is written behind them,
    * whose callback comes then, in order.
    */
  private def blocked(): Int = {
    if !flushWatched then {
      flushWatched = true
      try socket.write(NodeStream.Nothing, flushed): Unit
      catch {
        case js.JavaScriptException(failure) =>
          flushWatched = false
          if error == null then error = NodeErr.io(failure)
          throw error.nn
      }
    }
    ByteSink.Blocked
  }

  def write(bufs: Array[ByteBuffer]): Int = {
    if shutDown then throw new IOException("The stream is shut down for writing")
    if error != null then throw error.nn
    if closed then throw new IOException("Broken pipe: the stream has closed")
    if bufs.length == 1 && bufs(0).hasArrayBuffer() then {
      // One buffer whose memory JavaScript has: asked, copied and written in one call.
      val buf = bufs(0)
      val position = buf.position()
      val count = math.min(NodeStream.ChunkSize, buf.remaining())
      val written =
        try NodeGlue.putFrom(socket, buf.arrayBuffer(), buf.arrayBufferOffset() + position, count)
        catch {
          case js.JavaScriptException(failure) => // refused outright
            if error == null then error = NodeErr.io(failure)
            throw error.nn
        }
      if written < 0 then blocked()
      else {
        buf.position(position + count)
        count
      }
    } else if unsent > 0 then blocked()
    else {
      val chunk = NodeBuffer.drain(bufs, math.min(NodeStream.ChunkSize, NodeBuffer.room(bufs)))
      try NodeGlue.put(socket, chunk)
      catch {
        case js.JavaScriptException(failure) => // refused outright
          if error == null then error = NodeErr.io(failure)
          throw error.nn
      }
      chunk.length
    }
  }

  /** Ends the stream for writing: its end goes after what was written. */
  def shutdown(): Unit =
    if !shutDown then {
      shutDown = true
      if !closed then socket.end(): Unit
    }

  /** Closes the stream. What Node still holds of the bytes written is sent first, by Node, after this returns, as the
    * system sends what a closed socket's buffer holds.
    */
  def close(done: () => Unit): Boolean = {
    first = null
    behind.clear()
    if !closed then
      if unsent > 0 && error == null then socket.destroySoon(): Unit
      else socket.destroy(): Unit
    true
  }
}

private[node] object NodeStream {

  /** The most a write hands Node at once. */
  inline val ChunkSize = 65536

  val ignore: js.Function1[js.Dynamic, Unit] = _ => ()

  private val Nothing = new Uint8Array(0)
}
