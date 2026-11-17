package gears.async.io.nio

import gears.async.io.BufferPool
import gears.async.io.ByteStreams
import gears.async.io.Handles
import gears.async.io.Interface
import gears.async.io.Pipes
import gears.async.io.Received
import gears.async.io.Repeatable
import gears.async.io.Slot
import gears.async.io.shareable.LendingPool

import java.nio.ByteBuffer
import scala.concurrent.duration.FiniteDuration

sealed trait NioInterface extends Interface {
  override type Op = NioOp

  override def timer(duration: FiniteDuration): NioTimerOp & Repeatable = {
    if duration.length < 0 then throw new IllegalArgumentException(s"A timer's time is not negative: $duration")
    NioMakeTimer(duration)
  }

  override def deadlineTimer(nanoTime: Long): NioDeadlineOp & Repeatable = NioMakeDeadline(nanoTime)
}

sealed trait NioPipeOps extends Pipes { self: NioByteStreams & NioInterface =>
  override def pipe(read: Slot[Readable], write: Slot[Writable]): NioOp = NioMakePipe(read, write)
}

sealed trait NioHandles extends Handles { self: NioInterface =>
  override type Handle = NioHandle

  override def close(handle: NioHandle): NioCloseOp = NioCloseHandle(handle)
}

sealed trait NioByteStreams extends ByteStreams { self: NioInterface & NioHandles =>
  override type Readable = NioReadable
  override type Writable = NioWritable

  override def read(handle: Readable, buf: ByteBuffer): NioOp & Repeatable = NioRead(handle, buf)

  override def write(handle: Writable, buf: ByteBuffer): NioOp & Repeatable = NioWrite(handle, buf)

  override def read(handle: Readable, bufs: Array[ByteBuffer]): NioOp & Repeatable = NioScatterRead(handle, bufs)

  override def write(handle: Writable, bufs: Array[ByteBuffer]): NioOp & Repeatable = NioGatherWrite(handle, bufs)

  override def read(handle: Readable, pool: BufferPool, into: Slot[Received]): NioOp & Repeatable =
    pool match
      case pool: LendingPool => NioPooledRead(handle, pool, into)
      case _ => throw new IllegalArgumentException("A pooled read takes a pool of this backend's (Backend.pool)")
}

object NioOps extends NioInterface, NioHandles, NioByteStreams, NioPipeOps {}
