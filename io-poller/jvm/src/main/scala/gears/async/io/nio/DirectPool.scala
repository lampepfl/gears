package gears.async.io.nio

import gears.async.io.shareable.LendingPool

import java.nio.ByteBuffer

/** A pool of direct buffers, which the JDK's channels fill with no copy. The memory is the garbage collector's to free.
  */
final class DirectPool(bufferSize: Int, buffers: Int) extends LendingPool(bufferSize, buffers) {
  protected def allocate(): ByteBuffer = ByteBuffer.allocateDirect(bufferSize)

  protected def free(): Unit = ()
}
