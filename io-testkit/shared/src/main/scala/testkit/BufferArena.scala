package gears.async.io
package testkit

import java.nio.ByteBuffer

/** Buffers that every backend's ops can fill and drain, allocated from one arena and freed together when it closes: for
  * tests and demos written against `gears.async.io` alone, which need buffers but should not say how a platform makes
  * them. On Scala Native the kernel needs pointer-backed buffers, so an arena is a `Zone`; on the JVM, a shared foreign
  * memory arena, whose buffers are off the heap. A buffer must not be used once its arena has closed: on the JVM that
  * throws, and on Scala Native it reads freed memory.
  */
trait BufferArena extends AutoCloseable {

  /** A buffer of `capacity` bytes, cleared: position 0, limit `capacity`. */
  def allocate(capacity: Int): ByteBuffer

  /** A buffer holding `bytes`, flipped and ready to drain. Empty input still gets one byte of storage. */
  def of(bytes: Array[Byte]): ByteBuffer = {
    val buf = allocate(math.max(bytes.length, 1))
    buf.put(bytes)
    buf.flip()
    buf
  }

  /** Frees every buffer allocated from this arena. */
  def close(): Unit
}

object BufferArena {

  /** Opens an arena; close it when its buffers are no longer used. */
  def open(): BufferArena = PlatformArena.open()

  /** Opens an arena for `body` and closes it afterwards. */
  def scoped[A](body: BufferArena => A): A = {
    val arena = open()
    try body(arena)
    finally arena.close()
  }
}
