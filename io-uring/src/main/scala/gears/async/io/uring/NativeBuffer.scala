package gears.async.io
package uring

import java.nio.ByteBuffer
import scala.scalanative.memory.PointerBuffer
import scala.scalanative.memory.PointerBufferOps.given
import scala.scalanative.runtime.ByteArray
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

private[uring] object NativeBuffer {

  def addressable(buf: ByteBuffer): Boolean = buf.hasPointer() || buf.hasArray()

  inline def staged[A](buf: ByteBuffer, inline copyIn: Boolean, inline copyOut: Boolean)(
      inline body: ByteBuffer => A
  ): A =
    if addressable(buf) then body(buf)
    else {
      val zone = Zone.open()
      try {
        val stand = standIn(buf, stagingMemory(buf.remaining())(using zone), copyIn)
        val result = body(stand)
        settle(buf, stand, copyOut)
        result
      } finally zone.close()
    }

  inline def staged[A](bufs: Array[ByteBuffer], inline copyIn: Boolean, inline copyOut: Boolean)(
      inline body: Array[ByteBuffer] => A
  ): A =
    if allAddressable(bufs) then body(bufs)
    else {
      val stands = bufs.clone()
      val zone = Zone.open()
      try {
        var i = 0
        while i < bufs.length do {
          if !addressable(bufs(i)) then
            stands(i) = standIn(bufs(i), stagingMemory(bufs(i).remaining())(using zone), copyIn)
          i += 1
        }
        val result = body(stands)
        i = 0
        while i < bufs.length do {
          if !(stands(i) eq bufs(i)) then settle(bufs(i), stands(i), copyOut)
          i += 1
        }
        result
      } finally zone.close()
    }

  def allAddressable(bufs: Array[ByteBuffer]): Boolean = {
    var i = 0
    while i < bufs.length && addressable(bufs(i)) do i += 1
    i == bufs.length
  }

  def stagingMemory(size: Int)(using zone: Zone): Ptr[Byte] = alloc[Byte](math.max(size, 1))

  def standIn(buf: ByteBuffer, memory: Ptr[Byte], copyIn: Boolean): ByteBuffer = {
    val size = buf.remaining()
    val stand = PointerBuffer.wrap(memory, math.max(size, 1))
    stand.limit(size)
    if copyIn then {
      stand.put(buf.duplicate())
      stand.position(0)
    }
    stand
  }

  def settle(buf: ByteBuffer, stand: ByteBuffer, copyOut: Boolean): Unit = {
    val moved = stand.position()
    if copyOut then {
      val got = stand.duplicate()
      got.flip()
      buf.put(got)
    } else buf.position(buf.position() + moved)
  }

  def atPosition(buf: ByteBuffer): Ptr[Byte] =
    if buf.hasPointer() then buf.pointer() + buf.position()
    else buf.array().asInstanceOf[ByteArray].atUnsafe(buf.arrayOffset() + buf.position())

  def advance(buf: ByteBuffer, n: Int): Unit = buf.position(buf.position() + n)
}
