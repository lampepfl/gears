package gears.async.io.bench

import java.nio.ByteBuffer

/** The JVM's part of the bench: direct buffers, which the channels fill with no copy. */
object BenchPlatform {
  def buffer(size: Int): ByteBuffer = ByteBuffer.allocateDirect(size)
}
