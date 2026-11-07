package gears.async.io.kqueue

import gears.async.io.BufferPool
import gears.async.io.Deadline
import gears.async.io.Ops
import gears.async.io.PipeOps
import gears.async.io.Received
import gears.async.io.Repeatable
import gears.async.io.Slot

import java.nio.ByteBuffer
import scala.concurrent.duration.FiniteDuration

sealed trait KqueueBaseOps extends Ops[KqueueOp, KqueueHandle] {
  override type Readable = KqueueReadable
  override type Writable = KqueueWritable
}

sealed trait KqueuePipeOps extends KqueueBaseOps with PipeOps[KqueueOp, KqueueHandle] {
  override def pipe(read: Slot[Readable], write: Slot[Writable]): KqueueOp = ???
}

sealed trait KqueueCoreOps extends KqueueBaseOps {
  override def read(handle: Readable, buf: ByteBuffer): KqueueReadableOp & Repeatable = ???

  override def write(handle: Writable, buf: ByteBuffer): KqueueWritableOp & Repeatable = ???

  override def read(handle: Readable, bufs: Array[ByteBuffer]): KqueueReadableOp & Repeatable = ???

  override def write(handle: Writable, bufs: Array[ByteBuffer]): KqueueWritableOp & Repeatable = ???

  override def timer(duration: FiniteDuration): KqueueTimerOp & Repeatable = {
    if duration.length < 0 then throw new IllegalArgumentException(s"A timer's time is not negative: $duration")
    KqueueMakeTimer(duration)
  }

  override def deadlineTimer(nanoTime: Long): KqueueDeadlineOp & Repeatable = KqueueMakeDeadline(nanoTime)

  override def read(handle: Readable, pool: BufferPool, into: Slot[Received]): KqueueReadableOp & Repeatable = ???

  override def close(handle: KqueueHandle): KqueueCloseOp = ???
}

object KqueueOps extends KqueueBaseOps with KqueueCoreOps with KqueuePipeOps {}
