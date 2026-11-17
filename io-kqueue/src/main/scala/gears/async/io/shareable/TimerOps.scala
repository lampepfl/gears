package gears.async.io.shareable

import gears.async.io.Deadline
import gears.async.io.shareable.TimerHeapOp.CancelledSentinel

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

abstract class AbstractMakeTimerOp(duration: FiniteDuration) extends RepeatableAbstractTimerHeapOp {

  /** When this performance's `duration` is up: a timer counts from each submission. */
  @volatile private var initialDeadline: Long = -1L

  override def onEntry(): Unit = {
    super.onEntry()
    initialDeadline = TimerHeap.deadlineAfter(duration.toNanos)
  }

  override def minDeadline: Long = {
    val sup = super.minDeadline
    if sup >= 0 then return math.min(sup, initialDeadline)
    else initialDeadline
  }

  override def ownDeadline: Long = initialDeadline

  override protected def hasOwnDeadline: Boolean = true

  def wakeup(resumedAt: Long): Try[Unit] | CancelledSentinel.type =
    checkCancellationBeforeWork(WorkpoolScheduler.SuccessUnit)
}

abstract class AbstractMakeDeadlineOp(initial: Long) extends RepeatableAbstractTimerHeapOp with Deadline {
  private val myDeadline = AtomicLong(initial)

  override def deadline: Long = myDeadline.get()

  override def moveTo(nanoTime: Long): Unit =
    if nanoTime < 0 then throw new IllegalArgumentException("Deadline must be non-negative")
    // Compared with the very deadline it replaces: of movers racing each other, whichever leaves the deadline earlier
    // than the heap knows of is then sure to tell it.
    val earlier = nanoTime < myDeadline.getAndSet(nanoTime)
    // The worker may be sleeping until the old deadline. If it is moved later, the heap finds out when that comes round.
    if earlier then requestTimersUpdate()

  override def minDeadline: Long = {
    val sup = super.minDeadline
    val own = myDeadline.get()
    if sup >= 0 then return math.min(sup, own)
    else own
  }

  override def ownDeadline: Long = myDeadline.get()

  override protected def hasOwnDeadline: Boolean = true

  def wakeup(resumedAt: Long): Try[Unit] | CancelledSentinel.type =
    checkCancellationBeforeWork(WorkpoolScheduler.SuccessUnit)
}
