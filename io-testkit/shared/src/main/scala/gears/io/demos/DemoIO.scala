package gears.io.demos

import gears.async.io.Slot

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/** What the demos share on every platform: decoding buffers, the stream demos' framing, and reading a slot. */
object DemoIO {

  /** Decodes the bytes between position and limit as UTF-8 without moving the position. */
  def text(buf: ByteBuffer): String = {
    val bytes = new Array[Byte](buf.remaining())
    buf.duplicate().get(bytes)
    new String(bytes, StandardCharsets.UTF_8)
  }

  /** Appends the bytes between position and limit to `out`, consuming them. */
  def drainTo(buf: ByteBuffer, out: ByteArrayOutputStream): Unit = {
    val bytes = new Array[Byte](buf.remaining())
    buf.get(bytes)
    out.write(bytes)
  }

  /** The stream demos prefix each message with a four-byte big-endian length. */
  object StreamProtocol {
    inline val HeaderLength = 4
    inline val MaxMessageLength = 1024 * 1024

    /** Fills `buf` with the framed message and flips it, ready to send. */
    def frame(message: String, buf: ByteBuffer): Unit = {
      val bytes = message.getBytes(StandardCharsets.UTF_8)
      buf.clear()
      buf.putInt(bytes.length)
      buf.put(bytes)
      buf.flip()
    }

    /** Decodes the length header at the start of `header`; negative values mean an invalid frame. */
    def messageLength(header: ByteBuffer): Int = header.getInt(0)

    /** Appends one framed message at the buffer's position; returns false, leaving it untouched, if it does not fit.
      */
    def append(message: String, buf: ByteBuffer): Boolean = {
      val bytes = message.getBytes(StandardCharsets.UTF_8)
      if HeaderLength + bytes.length > buf.remaining() then false
      else {
        buf.putInt(bytes.length)
        buf.put(bytes)
        true
      }
    }

    /** Appends one framed message to a byte stream. */
    def appendTo(out: ByteArrayOutputStream, message: String): Unit = {
      val bytes = message.getBytes(StandardCharsets.UTF_8)
      out.write(bytes.length >>> 24)
      out.write(bytes.length >>> 16)
      out.write(bytes.length >>> 8)
      out.write(bytes.length)
      out.write(bytes)
    }
  }

  /** A frame whose header gives a length that is negative or above the most a reader takes. */
  final class InvalidFrame(val length: Int) extends IOException(s"Invalid message size: $length")

  /** Reads one framed message through `buf` by limiting each read to exactly what is still expected: the header, then
    * the body, a buffer's worth at a time, so that nothing past the frame is ever read, and what follows it stays for
    * the next reader or a graceful close. After each read into `buf`, which appends at its position, `advance` takes
    * what the read brought and returns the message once it is whole, or null with `buf` limited to what is expected
    * next; a header whose length is negative or above `max` fails at once, with `InvalidFrame`, before any of the body
    * is read or room made for it.
    */
  final class FrameReader(buf: ByteBuffer, max: Int = StreamProtocol.MaxMessageLength) {
    private var body: Array[Byte] | Null = null // once the header is in, room for the body
    private var filled = 0
    buf.clear().limit(StreamProtocol.HeaderLength)

    def advance(): String | Null = {
      if body == null then {
        if buf.hasRemaining() then return null // the rest of the header
        val length = StreamProtocol.messageLength(buf)
        if length < 0 || length > max then throw new InvalidFrame(length)
        body = new Array[Byte](length)
      } else {
        buf.flip()
        val n = buf.remaining()
        buf.get(body.nn, filled, n)
        filled += n
      }
      val whole = body.nn
      buf.clear()
      if filled == whole.length then new String(whole, StandardCharsets.UTF_8)
      else {
        buf.limit(math.min(whole.length - filled, buf.capacity()))
        null
      }
    }
  }

  /** Reassembles framed messages from a byte stream that arrives in arbitrary chunks, as a pipelined connection's do,
    * many frames to a read: where one frame at a time is expected, `FrameReader` reads exactly it.
    */
  final class FrameParser {
    private var pending = new Array[Byte](0)

    /** Takes the bytes between position and limit of `buf`. */
    def feed(buf: ByteBuffer): Unit = {
      val chunk = new Array[Byte](buf.remaining())
      buf.get(chunk)
      pending = pending ++ chunk
    }

    /** The next complete message, or null if the pending bytes do not hold one yet. */
    def next(): String | Null = {
      if pending.length < StreamProtocol.HeaderLength then return null
      val length = ByteBuffer.wrap(pending).getInt(0)
      require(length >= 0 && length <= StreamProtocol.MaxMessageLength, s"Invalid frame length $length")
      val end = StreamProtocol.HeaderLength + length
      if pending.length < end then null
      else {
        val message = new String(pending, StreamProtocol.HeaderLength, length, StandardCharsets.UTF_8)
        pending = pending.drop(end)
        message
      }
    }
  }

  /** Whether `slot` is empty, taking out any value it holds. */
  def isEmpty(slot: Slot[?]): Boolean =
    try {
      slot.clear()
      false
    } catch case _: IllegalStateException => true
}
