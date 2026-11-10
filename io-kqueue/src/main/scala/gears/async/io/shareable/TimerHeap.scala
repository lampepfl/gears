package gears.async.io.shareable

import gears.async.Cancellable
import gears.async.io.shareable.Poller

import scala.util.Try

import TimerHeap.{TimerOp, TimerSource}

object TimerHeap {
  trait TimerSource[A <: TimerOp] {

    /** `timerOp` has a deadline earlier than the heap knows of. A later one needs no call: see `nextReadyTimer`. */
    def notifyDeadlineChange(timerOp: TimerOp): Unit
    def debugTimerState(): String
    def addTimer(timerOp: A): Unit

    /** Whether `timerOp` was taken out of the heap, and so is guaranteed to not fire. If false then it was not in the
      * heap to begin with.
      */
    def removeTimer[A1 >: A <: TimerOp](timerOp: A1): Boolean

    /** Takes the next timer due by `now` out of the heap, or gives null if none is. A non-null result is guaranteed to
      * have succeeded `claimAtDeadline` before returning. A due timer that could not be claimed is dropped.
      */
    def nextReadyTimer(now: Long): A | Null

    /** When to look at the heap next, which may be before anything is due, or -1 if it is empty. */
    def peekNextDeadline(): Long
  }

  trait TimerOp extends Cancellable {
    def deadlineNow: Long

    /** called under the heap's lock when retrieved. If true then the heap now owns the claim for this timer. If false
      * then the timer will be dropped by the heap.
      */
    def claimAtDeadline(): Boolean

    /** Whether there is a deadline at all. If not, `deadlineNow` means nothing, and the op stays off the heap: only
      * what completes it can fire it.
      */
    def hasDeadline: Boolean = true

    /** Fires the timer. Precondition: claimAtDeadline returned true.
      */
    def timerWakeup(actAfter: Long): Unit

    /** Where the heap holds this timer, or -1. Guarded by the heap's lock, as is `heapKey`. */
    private[shareable] var heapIndex = -1

    /** The deadline the heap orders this timer by: at most `deadlineNow`, unless a `notifyDeadlineChange` is to come.
      */
    private[shareable] var heapKey = 0L
  }

  /** `nanos` from now on `System.nanoTime`'s clock, or the end of that clock if `nanos` goes past it: a deadline that
    * wrapped around to negative would be due at once.
    */
  def deadlineAfter(nanos: Long): Long = {
    val now = System.nanoTime()
    val deadline = now + nanos
    if nanos > 0 && deadline < now then Long.MaxValue else deadline
  }
}

/** A binary heap of timers, each knowing its place, so that removing a timer or bringing it forward takes logarithmic
  * time. Its order is by `heapKey` and never by a deadline read afresh, as a deadline can move at any moment.
  */
abstract class TimerHeap[A <: TimerOp] extends Poller with TimerSource[A] {
  private object timerLock

  private var heap = new Array[TimerOp](16)
  private var size = 0

  private def place(timerOp: TimerOp, index: Int): Unit = {
    heap(index) = timerOp
    timerOp.heapIndex = index
  }

  private def siftUp(timerOp: TimerOp, start: Int): Unit = {
    var index = start
    var moving = index > 0
    while moving do
      val parent = (index - 1) / 2
      if timerOp.heapKey < heap(parent).heapKey then
        place(heap(parent), index)
        index = parent
        moving = index > 0
      else moving = false
    place(timerOp, index)
  }

  private def siftDown(timerOp: TimerOp, start: Int): Unit = {
    var index = start
    var moving = true
    while moving do
      val left = 2 * index + 1
      if left >= size then moving = false
      else
        val right = left + 1
        val child = if right < size && heap(right).heapKey < heap(left).heapKey then right else left
        if heap(child).heapKey < timerOp.heapKey then
          place(heap(child), index)
          index = child
        else moving = false
    place(timerOp, index)
  }

  private def takeAt(index: Int): Unit = {
    heap(index).heapIndex = -1
    size -= 1
    val last = heap(size)
    heap(size) = null
    if index < size then
      siftDown(last, index)
      if last.heapIndex == index then siftUp(last, index)
  }

  private def holds(timerOp: TimerOp): Boolean = {
    val index = timerOp.heapIndex
    index >= 0 && index < size && (heap(index) eq timerOp)
  }

  private def rekey(timerOp: TimerOp): Unit = {
    val previous = timerOp.heapKey
    timerOp.heapKey = timerOp.deadlineNow
    if timerOp.heapKey < previous then siftUp(timerOp, timerOp.heapIndex)
    else siftDown(timerOp, timerOp.heapIndex)
  }

  /** A timer moved later is still at its old place. It is put right here, once that place comes up. */
  final def nextReadyTimer(now: Long): A | Null = timerLock.synchronized {
    var next: A | Null = null
    var looking = true
    while looking do
      var head: TimerOp | Null = null
      if size == 0 || { head = heap(0); head }.heapKey > now then looking = false
      else if head.deadlineNow > now then rekey(head)
      else
        takeAt(0)
        if head.claimAtDeadline() then
          next = head.asInstanceOf[A]
          looking = false
    next
  }

  final def peekNextDeadline(): Long = timerLock.synchronized {
    if size > 0 then heap(0).heapKey else -1
  }

  final def debugTimerState() = timerLock.synchronized {
    heap.iterator.take(size).mkString("[", ", ", "]")
  }

  /** Wakes the worker only for a new earliest deadline: otherwise it wakes in time as it is. */
  final def addTimer(timerOp: A): Unit = {
    if !timerOp.hasDeadline then throw new IllegalArgumentException("Only an op with a deadline goes on the heap")
    val isNext = timerLock.synchronized {
      if size == heap.length then heap = java.util.Arrays.copyOf(heap, 2 * size)
      timerOp.heapKey = timerOp.deadlineNow
      size += 1
      siftUp(timerOp, size - 1)
      heap(0) eq timerOp
    }
    if isNext then wake()
  }

  /** Doesn't wake the worker, which at worst wakes for a timer no longer there, and finds nothing to do. */
  final override def removeTimer[A1 >: A <: TimerOp](timerOp: A1): Boolean = timerLock.synchronized {
    val held = holds(timerOp)
    if held then takeAt(timerOp.heapIndex)
    held
  }

  final def notifyDeadlineChange(timerOp: TimerOp): Unit = {
    val isNext = timerLock.synchronized {
      val held = holds(timerOp)
      if held then rekey(timerOp)
      held && (heap(0) eq timerOp)
    }
    if isNext then wake()
  }
}
