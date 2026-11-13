package gears.async.io.kqueue

import gears.async.io.BufferPool
import gears.async.io.ByteStreams
import gears.async.io.Deadline
import gears.async.io.Handles
import gears.async.io.Interface
import gears.async.io.Pipes
import gears.async.io.Received
import gears.async.io.Repeatable
import gears.async.io.Slot
import gears.async.io.shareable.LendingPool

import java.nio.ByteBuffer
import scala.concurrent.duration.FiniteDuration

sealed trait KqueueInterface extends Interface {
  override type Op = KqueueOp

  override def timer(duration: FiniteDuration): KqueueTimerOp & Repeatable = {
    if duration.length < 0 then throw new IllegalArgumentException(s"A timer's time is not negative: $duration")
    KqueueMakeTimer(duration)
  }

  override def deadlineTimer(nanoTime: Long): KqueueDeadlineOp & Repeatable = KqueueMakeDeadline(nanoTime)
}

sealed trait KqueuePipeOps extends Pipes { self: KqueueByteStreams & KqueueInterface =>
  override def pipe(read: Slot[Readable], write: Slot[Writable]): KqueuePipeOp = KqueueMakePipe(read, write)
}

sealed trait KqueueHandles extends Handles { self: KqueueInterface =>
  override type Handle = KqueueHandle

  override def close(handle: KqueueHandle): KqueueCloseOp = KqueueCloseHandle(handle)
}

sealed trait KqueueByteStreams extends ByteStreams { self: KqueueInterface & KqueueHandles =>
  override type Readable = KqueueReadable
  override type Writable = KqueueWritable

  override def read(handle: Readable, buf: ByteBuffer): KqueueReadableOp & Repeatable =
    KqueueRead(handle, buf)

  override def write(handle: Writable, buf: ByteBuffer): KqueueWritableOp & Repeatable =
    KqueueWrite(handle, buf)

  override def read(handle: Readable, bufs: Array[ByteBuffer]): KqueueReadableOp & Repeatable =
    KqueueScatterRead(handle, bufs)

  override def write(handle: Writable, bufs: Array[ByteBuffer]): KqueueWritableOp & Repeatable =
    KqueueGatherWrite(handle, bufs)

  override def read(handle: Readable, pool: BufferPool, into: Slot[Received]): KqueueReadableOp & Repeatable =
    pool match
      case pool: LendingPool => KqueuePooledRead(handle, pool, into)
      case _ => throw new IllegalArgumentException("A pooled read takes a pool of this backend's (Backend.pool)")
}

object KqueueOps extends KqueueInterface, KqueueHandles, KqueueByteStreams, KqueuePipeOps {}
