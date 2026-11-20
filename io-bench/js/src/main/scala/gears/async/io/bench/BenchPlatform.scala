package gears.async.io.bench

import java.nio.ByteBuffer

/** Scala.js's part of the bench: direct buffers, which are over typed arrays that Node reads and writes. */
object BenchPlatform {
  def buffer(size: Int): ByteBuffer = ByteBuffer.allocateDirect(size)

  /** A buffer for a program that reads and fills it by way of arrays: on the heap, where that is a copy within
    * WebAssembly. A direct buffer's memory is JavaScript's, and Scala.js moves bytes between it and an array one at a
    * call.
    */
  def arrayBuffer(size: Int): ByteBuffer = ByteBuffer.allocate(size)
}
