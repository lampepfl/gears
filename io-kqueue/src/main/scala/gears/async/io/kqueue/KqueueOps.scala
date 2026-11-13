package gears.async.io.kqueue

import gears.async.io.BufferPool
import gears.async.io.ByteStreams
import gears.async.io.Deadline
import gears.async.io.Pipes
import gears.async.io.Received
import gears.async.io.Repeatable
import gears.async.io.Slot

import java.nio.ByteBuffer
import scala.concurrent.duration.FiniteDuration

sealed trait KqueueBaseOps extends ByteStreams {
  override type Op = KqueueOp
  override type Handle = KqueueHandle
  override type Readable = KqueueReadable
  override type Writable = KqueueWritable
}

sealed trait KqueuePipeOps extends KqueueBaseOps with Pipes {
  override def pipe(read: Slot[Readable], write: Slot[Writable]): KqueuePipeOp = KqueueMakePipe(read, write)
}

sealed trait KqueueCoreOps extends KqueueBaseOps {
  override def read(handle: Readable, buf: ByteBuffer): KqueueReadableOp & Repeatable =
    KqueueRead(handle, buf)

  override def write(handle: Writable, buf: ByteBuffer): KqueueWritableOp & Repeatable =
    KqueueWrite(handle, buf)

  override def read(handle: Readable, bufs: Array[ByteBuffer]): KqueueReadableOp & Repeatable = ???

  override def write(handle: Writable, bufs: Array[ByteBuffer]): KqueueWritableOp & Repeatable = ???

  override def timer(duration: FiniteDuration): KqueueTimerOp & Repeatable = {
    if duration.length < 0 then throw new IllegalArgumentException(s"A timer's time is not negative: $duration")
    KqueueMakeTimer(duration)
  }

  override def deadlineTimer(nanoTime: Long): KqueueDeadlineOp & Repeatable = KqueueMakeDeadline(nanoTime)

  override def read(handle: Readable, pool: BufferPool, into: Slot[Received]): KqueueReadableOp & Repeatable = ???

  override def close(handle: KqueueHandle): KqueueCloseOp = KqueueCloseHandle(handle)
}

object KqueueOps extends KqueueBaseOps with KqueueCoreOps with KqueuePipeOps {}
