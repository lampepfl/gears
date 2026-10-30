package gears.io.demos

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import DemoIO.{FrameReader, InvalidFrame, StreamProtocol}

/** `FrameReader` over reads simulated from a byte source: each read takes what the buffer's limit allows. */
class FrameReaderSuite extends munit.FunSuite {

  private def framed(message: String): Array[Byte] = {
    val bytes = message.getBytes(StandardCharsets.UTF_8)
    ByteBuffer.allocate(StreamProtocol.HeaderLength + bytes.length).putInt(bytes.length).put(bytes).array()
  }

  /** Reads into `buf` from `source` as a socket would, at most `most` bytes and no further than the buffer's limit. */
  private def read(source: ByteBuffer, buf: ByteBuffer, most: Int = Int.MaxValue): Unit = {
    val n = math.min(math.min(source.remaining(), buf.remaining()), most)
    val slice = source.slice()
    slice.limit(n)
    buf.put(slice)
    source.position(source.position() + n)
  }

  /** Reads until `reader` gives its message. */
  private def readMessage(source: ByteBuffer, buf: ByteBuffer, reader: FrameReader, most: Int): String = {
    var message: String | Null = null
    while message == null do {
      read(source, buf, most)
      message = reader.advance()
    }
    message
  }

  test("reads exactly one frame, leaving what follows it unread") {
    val source = ByteBuffer.wrap(framed("hello") ++ "EXTRA".getBytes(StandardCharsets.UTF_8))
    val buf = ByteBuffer.allocate(64)
    assertEquals(readMessage(source, buf, new FrameReader(buf), most = Int.MaxValue), "hello")
    assertEquals(source.remaining(), 5)
  }

  test("reads a body larger than its buffer, however the reads are split") {
    val message = "abcdefghij" * 10
    for most <- Seq(1, 2, 3, 7, Int.MaxValue) do {
      val source = ByteBuffer.wrap(framed(message))
      val buf = ByteBuffer.allocate(8)
      assertEquals(readMessage(source, buf, new FrameReader(buf), most), message)
      assertEquals(source.remaining(), 0)
    }
  }

  test("an empty message is whole once its header is in") {
    val source = ByteBuffer.wrap(framed("") ++ Array[Byte](1, 2, 3))
    val buf = ByteBuffer.allocate(16)
    assertEquals(readMessage(source, buf, new FrameReader(buf), most = Int.MaxValue), "")
    assertEquals(source.remaining(), 3)
  }

  test("a negative or oversized length fails once the header is in, before any of the body is read") {
    for length <- Seq(-1, 1025) do {
      val source = ByteBuffer.allocate(4 + 2048).putInt(length).flip()
      source.limit(source.capacity())
      val buf = ByteBuffer.allocate(64)
      val reader = new FrameReader(buf, max = 1024)
      val failure = intercept[InvalidFrame](while true do { read(source, buf); reader.advance() })
      assertEquals(failure.length, length)
      assertEquals(source.position(), StreamProtocol.HeaderLength)
    }
  }
}
