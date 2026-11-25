package gears.async.io.bench

import java.nio.ByteBuffer
import scala.scalanative.memory.PointerBuffer
import scala.scalanative.unsafe.*

/** Scala Native's part of the bench: buffers over native memory, kept for the life of the process. */
object BenchPlatform {
  private val zone = Zone.open()

  def buffer(size: Int): ByteBuffer = zone.synchronized(PointerBuffer.wrap(alloc[Byte](size)(using zone), size))

  /** A buffer for a program that reads and fills it by way of arrays: native memory here too. */
  def arrayBuffer(size: Int): ByteBuffer = buffer(size)
}
