package gears.async.io
package node

import gears.async.io.node.host.NodeBuffer

import java.nio.ByteBuffer

/** Bytes between a program's buffers and Node's chunks, for each kind of buffer: on the heap, whose bytes go to
  * JavaScript packed into words, direct, and read-only; whole, and as a slice that begins part way into its array; at
  * every length around the 32 bytes that go at a call.
  */
class NodeBufferSuite extends munit.FunSuite {
  private val lengths = (0 to 70).toList ++ List(95, 96, 97, 127, 128, 129, 1000, 4096, 65536, 65537)

  private def pattern(length: Int, seed: Int): Array[Byte] =
    Array.tabulate(length)(i => (i * 131 + seed * 17 + 5).toByte)

  private def kinds(bytes: Array[Byte]): List[(String, ByteBuffer)] = {
    val direct = ByteBuffer.allocateDirect(bytes.length)
    direct.put(bytes)
    direct.flip()
    val padded = new Array[Byte](bytes.length + 7)
    System.arraycopy(bytes, 0, padded, 3, bytes.length)
    List(
      "on the heap" -> ByteBuffer.wrap(bytes.clone()),
      "a slice of the heap" -> ByteBuffer.wrap(padded, 3, bytes.length).slice(),
      "direct" -> direct,
      "read-only" -> ByteBuffer.wrap(bytes.clone()).asReadOnlyBuffer()
    )
  }

  test("a chunk drained from a buffer has the buffer's bytes, and the buffer is drained") {
    for length <- lengths; (kind, buf) <- kinds(pattern(length, length)) do {
      val chunk = NodeBuffer.drain(Array(buf), length)
      assertEquals(buf.remaining(), 0, s"$kind, $length bytes")
      val got = Array.tabulate(length)(i => chunk(i).toByte)
      assert(java.util.Arrays.equals(got, pattern(length, length)), s"$kind, $length bytes")
    }
  }

  test("a chunk copied from a buffer has the buffer's bytes, and the buffer is as it was") {
    for length <- lengths; (kind, buf) <- kinds(pattern(length, 3)) do {
      val chunk = NodeBuffer.copy(Array(buf), length)
      assertEquals(buf.remaining(), length, s"$kind, $length bytes")
      val got = Array.tabulate(length)(i => chunk(i).toByte)
      assert(java.util.Arrays.equals(got, pattern(length, 3)), s"$kind, $length bytes")
    }
  }

  test("a buffer filled from a chunk has the chunk's bytes, each buffer filled before the next") {
    for length <- lengths do {
      val expected = pattern(length, 9)
      val chunk = NodeBuffer.drain(Array(ByteBuffer.wrap(expected.clone())), length)
      val first = length / 3
      val padded = new Array[Byte](first + 5)
      val targets = List(
        "on the heap" -> Array(ByteBuffer.allocate(first), ByteBuffer.allocate(length - first)),
        "a slice of the heap, then direct" ->
          Array(ByteBuffer.wrap(padded, 2, first).slice(), ByteBuffer.allocateDirect(length - first))
      )
      for (kind, bufs) <- targets do {
        NodeBuffer.fill(bufs, chunk)
        val got = new Array[Byte](length)
        bufs.foreach { buf =>
          assertEquals(buf.remaining(), 0, s"$kind, $length bytes")
          buf.flip()
        }
        bufs(0).get(got, 0, first)
        bufs(1).get(got, first, length - first)
        assert(java.util.Arrays.equals(got, expected), s"$kind, $length bytes")
      }
    }
  }
}
