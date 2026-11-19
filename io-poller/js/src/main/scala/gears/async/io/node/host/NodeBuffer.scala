package gears.async.io.node.host

import java.nio.ByteBuffer
import scala.scalajs.js
import scala.scalajs.js.typedarray.Int8Array
import scala.scalajs.js.typedarray.TypedArrayBuffer
import scala.scalajs.js.typedarray.TypedArrayBufferOps.*
import scala.scalajs.js.typedarray.Uint8Array
import scala.scalajs.js.typedarray.byteArray2Int8Array

/** Bytes between the caller's buffers and Node's chunks, which are `Uint8Array`s, as Node's own `Buffer` is: the
  * backend has them as that throughout, and reaches a `ByteBuffer` through the memory under one. Node keeps a chunk it
  * is given until it has sent it, and hands over chunks of its own, so bytes are copied between a chunk and a buffer,
  * of any kind: on the heap, direct or read-only. A direct buffer is the one that costs a single copy, of the block;
  * the others cost two.
  */
private[node] object NodeBuffer {

  // A buffer on the heap is an array of WebAssembly's, which JavaScript cannot reach, and an array converted to one of
  // JavaScript's goes a byte at a call across. Its bytes go 32 at a call instead, as eight words, the first byte of
  // each its lowest: poured into a chunk by JavaScript (`NodeGlue.pour`), or handed here by JavaScript, word by word,
  // as it spills a chunk (`NodeGlue.spill`).

  /** `count` bytes of `bytes` from `at`, at most four, as a word. */
  private inline def word(bytes: Array[Byte], at: Int, count: Int): Int =
    if count == 4 then
      (bytes(at) & 0xff) | ((bytes(at + 1) & 0xff) << 8) | ((bytes(at + 2) & 0xff) << 16) | (bytes(at + 3) << 24)
    else {
      var value = 0
      var i = count - 1
      while i >= 0 do {
        value = (value << 8) | (bytes(at + i) & 0xff)
        i -= 1
      }
      value
    }

  // Where a chunk being spilled goes: one array at a time, on one thread.
  private var spillInto: Array[Byte] | Null = null
  private var spillAt = 0
  private var spillLeft = 0

  private def unpack(into: Array[Byte], at: Int, word: Int, count: Int): Unit =
    if count >= 4 then {
      into(at) = word.toByte
      into(at + 1) = (word >>> 8).toByte
      into(at + 2) = (word >>> 16).toByte
      into(at + 3) = (word >>> 24).toByte
    } else {
      var i = 0
      while i < count do {
        into(at + i) = (word >>> (i * 8)).toByte
        i += 1
      }
    }

  /** Takes the next 32 bytes of the chunk being spilled, or what is left of it. */
  private val spilled: js.Function8[Int, Int, Int, Int, Int, Int, Int, Int, Unit] = (a, b, c, d, e, f, g, h) => {
    val into = spillInto.nn
    val at = spillAt
    val left = spillLeft
    unpack(into, at, a, left)
    if left > 4 then unpack(into, at + 4, b, left - 4)
    if left > 8 then unpack(into, at + 8, c, left - 8)
    if left > 12 then unpack(into, at + 12, d, left - 12)
    if left > 16 then unpack(into, at + 16, e, left - 16)
    if left > 20 then unpack(into, at + 20, f, left - 20)
    if left > 24 then unpack(into, at + 24, g, left - 24)
    if left > 28 then unpack(into, at + 28, h, left - 28)
    spillAt = at + 32
    spillLeft = left - 32
  }

  /** The bytes remaining in `bufs`, together. */
  def remaining(bufs: Array[ByteBuffer]): Long = {
    var total = 0L
    var i = 0
    while i < bufs.length do {
      total += bufs(i).remaining()
      i += 1
    }
    total
  }

  /** `remaining(bufs)`, or `Int.MaxValue` if it is more. */
  def room(bufs: Array[ByteBuffer]): Int = math.min(remaining(bufs), Int.MaxValue.toLong).toInt

  /** A chunk of Node's as a buffer over the same memory, at position 0 with its limit at the chunk's length. */
  def wrap(chunk: Uint8Array): ByteBuffer = TypedArrayBuffer.wrap(chunk.buffer, chunk.byteOffset, chunk.length)

  /** The same bytes as the array WebAssembly's arrays convert to and from, which is of signed bytes. */
  private def signed(chunk: Uint8Array): Int8Array = new Int8Array(chunk.buffer, chunk.byteOffset, chunk.length)

  /** Copies `count` bytes of `chunk`, from `from`, into `buf` at its position, which they advance. A direct buffer is
    * memory JavaScript has, so the bytes are copied as a block; one on the heap is an array of WebAssembly's, which
    * JavaScript cannot reach, so they go by way of an array, converted as a block too. Byte by byte, each crossing
    * between the two, is what is left for a buffer that is neither.
    */
  private def put(buf: ByteBuffer, chunk: Uint8Array, from: Int, count: Int): Unit =
    if buf.hasArrayBuffer() then {
      val position = buf.position()
      NodeGlue.copyIn(chunk, from, count, buf.arrayBuffer(), buf.arrayBufferOffset() + position)
      buf.position(position + count)
    } else if buf.hasArray() then {
      spillInto = buf.array()
      spillAt = buf.arrayOffset() + buf.position()
      spillLeft = count
      NodeGlue.spill(chunk, from, count, spilled)
      spillInto = null
      buf.position(buf.position() + count)
    } else buf.put(wrap(chunk.subarray(from, from + count))): Unit

  /** Copies `count` bytes of `buf`, from its position, which they advance, into `chunk` at `at`: as `put` does. A
    * read-only buffer hides its memory, and is copied by way of an array.
    */
  private def get(buf: ByteBuffer, chunk: Uint8Array, at: Int, count: Int): Unit =
    if buf.hasArrayBuffer() then {
      val position = buf.position()
      NodeGlue.copyOut(buf.arrayBuffer(), buf.arrayBufferOffset() + position, count, chunk, at)
      buf.position(position + count)
    } else if buf.hasArray() then {
      val bytes = buf.array()
      val start = buf.arrayOffset() + buf.position()
      var i = 0
      while count - i >= 32 do {
        val o = start + i
        NodeGlue.pour(
          chunk,
          at + i,
          32,
          word(bytes, o, 4),
          word(bytes, o + 4, 4),
          word(bytes, o + 8, 4),
          word(bytes, o + 12, 4),
          word(bytes, o + 16, 4),
          word(bytes, o + 20, 4),
          word(bytes, o + 24, 4),
          word(bytes, o + 28, 4)
        )
        i += 32
      }
      if i < count then {
        val o = start + i
        val left = count - i
        def part(n: Int): Int = if left > n * 4 then word(bytes, o + n * 4, math.min(4, left - n * 4)) else 0
        NodeGlue.pour(chunk, at + i, left, part(0), part(1), part(2), part(3), part(4), part(5), part(6), part(7))
      }
      buf.position(buf.position() + count)
    } else {
      // Read-only, on the heap: its array is hidden, so it is copied out first.
      val bytes = new Array[Byte](count)
      buf.get(bytes)
      signed(chunk).set(byteArray2Int8Array(bytes), at)
    }

  /** Copies the first `count` bytes of `chunk` into `buf`, which has room for them. */
  def fillFrom(buf: ByteBuffer, chunk: Uint8Array, count: Int): Unit = if count > 0 then put(buf, chunk, 0, count)

  /** Copies `chunk` into `bufs` in order, each filled before the next is begun; `chunk` is no longer than their room.
    */
  def fill(bufs: Array[ByteBuffer], chunk: Uint8Array): Unit = {
    var from = 0
    var i = 0
    while from < chunk.length do {
      val buf = bufs(i)
      val count = math.min(buf.remaining(), chunk.length - from)
      if count > 0 then {
        put(buf, chunk, from, count)
        from += count
      }
      i += 1
    }
  }

  /** Takes `n` bytes out of `bufs` in order, each drained before the next is begun, as a chunk for Node to keep. */
  def drain(bufs: Array[ByteBuffer], n: Int): Uint8Array = {
    val chunk = NodeGlue.allocate(n)
    var at = 0
    var i = 0
    while at < n do {
      val buf = bufs(i)
      val count = math.min(buf.remaining(), n - at)
      if count > 0 then {
        get(buf, chunk, at, count)
        at += count
      }
      i += 1
    }
    chunk
  }

  /** The first `n` bytes of `bufs` in order, as a chunk for Node to keep, the buffers left as they were. */
  def copy(bufs: Array[ByteBuffer], n: Int): Uint8Array = {
    val chunk = NodeGlue.allocate(n)
    var at = 0
    var i = 0
    while at < n do {
      val buf = bufs(i)
      val count = math.min(buf.remaining(), n - at)
      if count > 0 then {
        if buf.hasArrayBuffer() then NodeGlue.copyOut(buf.arrayBuffer(), buf.arrayBufferOffset() + buf.position(), count, chunk, at)
        else get(buf.duplicate(), chunk, at, count)
        at += count
      }
      i += 1
    }
    chunk
  }

  /** Passes over `n` bytes of `bufs` in order, as a write that took them does. */
  def skip(bufs: Array[ByteBuffer], n: Int): Unit = {
    var left = n
    var i = 0
    while left > 0 do {
      val buf = bufs(i)
      val step = math.min(buf.remaining(), left)
      buf.position(buf.position() + step)
      left -= step
      i += 1
    }
  }
}
