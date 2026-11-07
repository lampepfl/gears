package gears.async.io.shareable

import scala.util.Try
import java.util.concurrent.atomic.AtomicReference
import gears.async.Async
import java.util.concurrent.CancellationException
import gears.async.Listener
import scala.util.Failure
import scala.util.control.NonFatal
import gears.async.io.Repeatable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.TimeoutException
import gears.async.io.Deadline

object TimerHeapOp {
  sealed trait Hooks {
    def onEntry(): Unit = ()
  }
}

trait TimerHeapOp extends WorkpoolScheduler.AsyncOp, WorkpoolScheduler.ReentrantOp


sealed abstract class AbstractTimerHeapOp extends TimerHeapOp with TimerHeapOp.Hooks {
  private val state = AtomicReference[Try[Unit]](null)
  private val timersLock = new Object

  /** Guards `state` against `listener`, so that a result can't arrive between `onComplete` seeing none and registering
    * its listener, which would then wait for ever.
    */
  private val listenerLock = new Object

  @volatile private var listener: Listener[Try[Unit]] | Null = null

  /** Guarded by `listenerLock`. The waiter on `settling`: a slot of its own, as a cancelled `await` is resumed before
    * its listener is dropped from `listener`.
    */
  private var settlingListener: Listener[Unit] | Null = null

  @volatile private var worker: TimerHeap.TimerSource[?] | Null = null

  @volatile private var timeoutDeadline = -1L

  def minDeadline: Long = timersLock.synchronized(timeoutDeadline)

  final def deadlineNow: Long = timersLock.synchronized {
    minDeadline
  }

  def ownDeadline: Long = -1L

  /** Whether `ownDeadline` means anything. It can't say so itself: a deadline that has passed may be negative. */
  protected def hasOwnDeadline: Boolean = false

  /** Read while the op is being performed, as `clearTimeout` drops the time limit. */
  final override def hasDeadline: Boolean = hasOwnDeadline || timeoutDeadline >= 0

  final def setTimeout(t: Int, source: TimerHeap.TimerSource[?]): Unit = timersLock.synchronized {
    worker = source
    if t < 0 then timeoutDeadline = -1L
    else
      timeoutDeadline = TimerHeap.deadlineAfter(t * 1000000L)
  }
  final def clearTimeout(): Unit = timersLock.synchronized {
    worker = null
    timeoutDeadline = -1L
  }

  /** Forgets the last performance's outcome, for the next performance of a repeatable op. */
  protected def reset(): Unit =
    listenerLock.synchronized {
      state.set(null)
      listener = null
      settlingListener = null
    }

  def settled: Boolean = state.get() != null

  /** Completes when the op settles, for a performer cancelled after its timer left the heap. */
  final lazy val settling: Async.Source[Unit] = new Async.Source[Unit] {
    override def poll(k: Listener[Unit]): Boolean =
      settled && k.completeNow((), this)

    override def onComplete(k: Listener[Unit]): Unit = {
      val alreadySettled = listenerLock.synchronized {
        val current = settled
        if !current then {
          if settlingListener != null then throw new IllegalStateException("Listener already registered")
          settlingListener = k
        }
        current
      }
      if alreadySettled then k.completeNow((), this)
    }

    override def dropListener(k: Listener[Unit]): Unit = listenerLock.synchronized {
      if settlingListener eq k then settlingListener = null
    }
  }

  /** The op's work once it is due, past any time limit, giving its outcome. Runs only for a consumer guaranteed to
    * receive that outcome, with the consumer's lock held, so it must be short and must not block. The `resumedAt`
    * parameter indicates the current time.
    */
  def wakeup(resumedAt: Long): Try[Unit]

  /** Always settles the op: a performer whose `Worker.withdraw` found it firing waits for this before it leaves. The
    * op's work is done only with its listener locked, so that a cancel either leaves it undone or gets its outcome
    * delivered. No listener means a cancelled one: see `WorkpoolScheduler.selectWorker`.
    */
  final def timerWakeup(resumedAt: Long): Unit =
    val consumer = listenerLock.synchronized {
      val waiting = listener
      listener = null
      waiting
    }
    if consumer != null && consumer.acquireLock() then
      val timeoutDeadline0 = timeoutDeadline
      val ownDeadline0 = ownDeadline
      // The limit decides only if it has passed and came first: at a tie the op has made its own deadline.
      // A throwing `wakeup` becomes the outcome: only `complete` releases the consumer's lock, and resumes it.
      val result =
        try
          if timeoutDeadline0 >= 0 && resumedAt >= timeoutDeadline0 && (ownDeadline0 < 0 || timeoutDeadline0 < ownDeadline0)
          then Failure(new TimeoutException("operation timed out"))
          else wakeup(resumedAt)
        catch case NonFatal(ex) => Failure(ex)
      settle(result)
      consumer.complete(result, this)
    else
      settle(Failure(new CancellationException()))

  /** Takes no op lock: the heap reads `deadlineNow` under its own lock, so that order is heap, then op. */
  final def requestTimersUpdate(): Unit = {
    val w = worker
    if w != null then
      w.notifyDeadlineChange(this)
  }

  private def settle(result: Try[Unit]): Unit = {
    var settlingWaiter: Listener[Unit] | Null = null
    val l = listenerLock.synchronized {
      if !state.compareAndSet(null, result) then throw new IllegalStateException("Already settled")
      val waiting = listener
      listener = null
      settlingWaiter = settlingListener
      settlingListener = null
      waiting
    }
    if settlingWaiter != null then settlingWaiter.nn.completeNow((), settling)
    if l != null then l.completeNow(result, this)
  }

  final override def poll(k: Listener[Try[Unit]]): Boolean =
    state.get() match
      case null => false
      case data => k.completeNow(data, this)

  final override def onComplete(k: Listener[Try[Unit]]): Unit = {
    val result = listenerLock.synchronized {
      val current = state.get()
      if current == null then {
        if listener != null then
          throw new IllegalStateException("Listener already registered")
        listener = k
      }
      current
    }
    if result != null then k.completeNow(result, this)
  }

  /** Best effort, as `Source` has it: `settle` may already have taken the listener. */
  final override def dropListener(k: Listener[Try[Unit]]): Unit = listenerLock.synchronized {
    if listener eq k then listener = null
  }
}

abstract class SingleUseAbstractTimerHeapOp extends AbstractTimerHeapOp {
  @volatile private var spent = false
  final def requestEntry(): Unit = {
    if spent then throw new IllegalStateException("Already consumed")
    spent = true
    onEntry()
  }
  final def requestExit(): Unit = {
    if !spent then throw new IllegalStateException("Not spent")
  }
}

abstract class RepeatableAbstractTimerHeapOp extends AbstractTimerHeapOp with Repeatable {
  @volatile private var entered = false
  final def requestEntry(): Unit = {
    if entered then throw new IllegalStateException("Concurrent entry not allowed")
    entered = true
    reset()
    onEntry()
  }
  final def requestExit(): Unit = {
    if !entered then throw new IllegalStateException("Not entered")
    entered = false
  }
}

abstract class AbstractMakeTimerOp(duration: FiniteDuration)
    extends RepeatableAbstractTimerHeapOp {

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

  def wakeup(resumedAt: Long): Try[Unit] = WorkpoolScheduler.SuccessUnit
}

abstract class AbstractMakeDeadlineOp(initial: Long) extends RepeatableAbstractTimerHeapOp with Deadline {
  @volatile private var myDeadline: Long = initial

  override def deadline: Long = myDeadline

  override def moveTo(nanoTime: Long): Unit =
    if nanoTime < 0 then throw new IllegalArgumentException("Deadline must be non-negative")
    val earlier = nanoTime < myDeadline
    myDeadline = nanoTime
    // The worker may be sleeping until the old deadline. If it is moved later, the heap finds out when that comes round.
    if earlier then requestTimersUpdate()

  override def minDeadline: Long = {
    val sup = super.minDeadline
    if sup >= 0 then return math.min(sup, myDeadline)
    else myDeadline
  }

  override def ownDeadline: Long = myDeadline

  override protected def hasOwnDeadline: Boolean = true

  def wakeup(resumedAt: Long): Try[Unit] = WorkpoolScheduler.SuccessUnit
}
