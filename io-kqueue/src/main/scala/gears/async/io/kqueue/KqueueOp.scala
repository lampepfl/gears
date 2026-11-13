package gears.async.io.kqueue

import gears.async.Async
import gears.async.Listener
import gears.async.io.Deadline
import gears.async.io.Io
import gears.async.io.Repeatable
import gears.async.io.Slot
import gears.async.io.posix.PosixKernelHandle
import gears.async.io.posix.PosixPipeOpener
import gears.async.io.posix.PosixReadOps
import gears.async.io.posix.PosixWriteOps
import gears.async.io.Received
import gears.async.io.shareable.AbstractCloseOp
import gears.async.io.shareable.AbstractGatherWriteOp
import gears.async.io.shareable.AbstractPooledReadOp
import gears.async.io.shareable.AbstractScatterReadOp
import gears.async.io.shareable.LendingPool
import gears.async.io.shareable.AbstractMakeDeadlineOp
import gears.async.io.shareable.AbstractMakePipeOp
import gears.async.io.shareable.AbstractMakeTimerOp
import gears.async.io.shareable.AbstractReadOp
import gears.async.io.shareable.AbstractWriteOp
import gears.async.io.shareable.RepeatableAbstractTimerHeapOp
import gears.async.io.shareable.SingleUseAbstractTimerHeapOp
import gears.async.io.shareable.TimerHeap
import gears.async.io.shareable.TimerHeapOp
import gears.async.io.shareable.TimerHeapOp.Outcome
import gears.async.io.shareable.WorkpoolScheduler

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Try

sealed trait KqueueOp extends TimerHeapOp

sealed trait KqueueReadableOp extends KqueueOp

sealed trait KqueueWritableOp extends KqueueOp

sealed trait KqueueTimerOp extends KqueueOp

sealed trait KqueuePipeOp extends KqueueOp

sealed trait KqueueDeadlineOp extends KqueueOp with Deadline

sealed trait KqueueCloseOp extends KqueueOp

trait KqueueReadOps extends PosixReadOps:
  type ReadableHandle = KqueueReadable
  type WorkerSource = KqueuePoller

trait KqueueWriteOps extends PosixWriteOps:
  type WriteableHandle = KqueueWritable
  type WorkerSource = KqueuePoller

private[kqueue] final class KqueueRead(handle: KqueueReadable, buf: ByteBuffer)
    extends AbstractReadOp[KqueueReadable, KqueuePoller](handle, buf),
      KqueueReadableOp,
      KqueueReadOps

private[kqueue] final class KqueueWrite(handle: KqueueWritable, buf: ByteBuffer)
    extends AbstractWriteOp[KqueueWritable, KqueuePoller](handle, buf),
      KqueueWritableOp,
      KqueueWriteOps

private[kqueue] final class KqueueScatterRead(handle: KqueueReadable, bufs: Array[ByteBuffer])
    extends AbstractScatterReadOp[KqueueReadable, KqueuePoller](handle, bufs),
      KqueueReadableOp,
      KqueueReadOps

private[kqueue] final class KqueuePooledRead(handle: KqueueReadable, pool: LendingPool, into: Slot[Received])
    extends AbstractPooledReadOp[KqueueReadable, KqueuePoller](handle, pool, into),
      KqueueReadableOp,
      KqueueReadOps

private[kqueue] final class KqueueGatherWrite(handle: KqueueWritable, bufs: Array[ByteBuffer])
    extends AbstractGatherWriteOp[KqueueWritable, KqueuePoller](handle, bufs),
      KqueueWritableOp,
      KqueueWriteOps

private[kqueue] final class KqueueCloseHandle(handle: KqueueHandle) extends AbstractCloseOp(handle) with KqueueCloseOp

private[kqueue] final class KqueueMakePipe(readSlot: Slot[KqueueReadable], writeSlot: Slot[KqueueWritable])
    extends AbstractMakePipeOp(readSlot, writeSlot)
    with KqueuePipeOpener
    with KqueuePipeOp

private[kqueue] final class KqueueMakeTimer(duration: FiniteDuration)
    extends AbstractMakeTimerOp(duration)
    with KqueueTimerOp

private[kqueue] final class KqueueMakeDeadline(initial: Long)
    extends AbstractMakeDeadlineOp(initial)
    with KqueueDeadlineOp
