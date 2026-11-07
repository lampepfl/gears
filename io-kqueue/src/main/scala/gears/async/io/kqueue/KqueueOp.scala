package gears.async.io.kqueue

import gears.async.Async
import gears.async.io.Deadline
import gears.async.io.Io
import gears.async.io.Repeatable

import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration
import gears.async.Listener
import scala.util.Try
import java.util.concurrent.atomic.AtomicReference
import scala.util.Failure
import gears.async.io.shareable.WorkpoolScheduler
import gears.async.io.shareable.TimerHeap
import gears.async.io.shareable.TimerHeapOp
import gears.async.io.shareable.SingleUseAbstractTimerHeapOp
import gears.async.io.shareable.RepeatableAbstractTimerHeapOp
import gears.async.io.shareable.AbstractMakeTimerOp
import gears.async.io.shareable.AbstractMakeDeadlineOp

sealed trait KqueueOp extends TimerHeapOp

sealed abstract class SingleUseAbstractKqueueOp extends SingleUseAbstractTimerHeapOp

sealed abstract class RepeatableAbstractKqueueOp extends RepeatableAbstractTimerHeapOp

sealed trait KqueueReadableOp extends KqueueOp

sealed trait KqueueWritableOp extends KqueueOp

sealed trait KqueueTimerOp extends KqueueOp

sealed trait KqueueDeadlineOp extends KqueueOp with Deadline

sealed trait KqueueCloseOp extends KqueueOp

private[kqueue] final class KqueueMakeTimer(duration: FiniteDuration)
    extends AbstractMakeTimerOp(duration)
    with KqueueTimerOp

private[kqueue] final class KqueueMakeDeadline(initial: Long)
  extends AbstractMakeDeadlineOp(initial) with KqueueDeadlineOp
