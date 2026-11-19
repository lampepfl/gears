package gears.async.io.kqueue

import gears.async.io.posix.NativeBuffer
import gears.async.io.shareable.LendingPool

import java.nio.ByteBuffer
import scala.scalanative.unsafe.Zone

/** A pool of buffers over native memory, which reads fill with no copy. The memory is a zone's, freed as the pool
  * closes.
  */
final class NativePool(bufferSize: Int, buffers: Int) extends LendingPool(bufferSize, buffers) {
  private val zone = Zone.open()

  protected def allocate(): ByteBuffer = NativeBuffer.allocate(bufferSize)(using zone)

  protected def free(): Unit = zone.close()
}
