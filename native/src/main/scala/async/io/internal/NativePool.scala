package gears.async.io
package internal

import scala.scalanative.memory.PointerBuffer
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** A `BufferPool` in C memory, for the Native backends: one allocation for all the buffers, in a zone of the pool's own
  * that its close frees, sliced into a `ByteBuffer` per slot, with the free list of `FreeListPool`. A buffer's address
  * is its slot's `address`, for a backend that receives straight into it.
  */
final class NativePool(bufferSize: Int, buffers: Int) extends FreeListPool(bufferSize, buffers) {
  private val zone = Zone.open()
  private val memory: Ptr[Byte] = alloc[Byte]((bufferSize.toLong * buffers).toCSize)(using zone)

  /** One of the pool's buffers, with its address. */
  final class NativeSlot(index: Int, val address: Ptr[Byte])
      extends Slot(index, PointerBuffer.wrap(address, bufferSize))

  type S = NativeSlot

  fill(i => new NativeSlot(i, memory + i.toLong * bufferSize))

  protected def freeMemory(): Unit = zone.close()
}
