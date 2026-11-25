package gears.async.io
package node

import gears.async.io.node.host.NodeBuffer

import java.io.IOException
import java.nio.ByteBuffer
import scala.scalajs.js.typedarray.Uint8Array

/** The Node.js backend's pool. Node's streams hand the backend chunks of their own, so a pooled read hands over such a
  * chunk, of at most `bufferSize` bytes, rather than copy it into a buffer allocated here: the pool allocates nothing,
  * and counts the chunks held, at most `buffers`, which is what makes it a window on the stream.
  */
private[node] final class NodePool(val bufferSize: Int, val buffers: Int) extends BufferPool {
  require(bufferSize > 0 && buffers > 0, s"A pool has buffers of some size: $buffers of $bufferSize bytes")
  private var held = 0
  private var closed = false

  def available: Int = buffers - held

  /** Fails if the pool has no buffer for a read that has bytes to hand over, or is closed: asked before the bytes are
    * taken from their stream, so that a read refused has taken none. Nothing else runs between this and `received`,
    * there being one thread.
    */
  private[node] def checkFree(): Unit = {
    if closed then throw new IOException("The pool is closed")
    if held == buffers then throw new IOException(s"The pool has no buffer free: all $buffers are held")
  }

  /** What a read received, as one of the pool's buffers, held from here until it is released: `chunk`, or nothing at
    * the end of the stream.
    */
  private[node] def received(chunk: Uint8Array | Null): Received = {
    checkFree()
    val bytes: ByteBuffer = if chunk == null then ByteBuffer.allocate(0) else NodeBuffer.wrap(chunk)
    held += 1
    new Received {
      private var released = false
      def buffer: ByteBuffer = bytes
      def release(): Unit = {
        if released then throw new IllegalStateException("Released already")
        released = true
        held -= 1
      }
    }
  }

  def close(): Unit =
    if !closed then {
      if held > 0 then throw new IllegalArgumentException(s"$held of the pool's buffers are still held")
      closed = true
    }
}
