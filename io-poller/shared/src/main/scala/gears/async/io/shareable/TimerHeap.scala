package gears.async.io.shareable

import gears.async.Cancellable
import gears.async.io.shareable.Poller

import TimerHeap.{TimerHeap, TimerOp}

object TimerHeap {

  trait TimerHeap extends TimerSource {

    def debugTimerState(): String

    /** Takes the next timer due by `now` out of the heap, or gives null if none is. A non-null result is guaranteed to
      * have succeeded `claimAtDeadline` before returning. A due timer that could not be claimed is dropped.
      */
    def nextReadyTimer(now: Long): Timer | Null

    /** When to look at the heap next, which may be before anything is due, or -1 if it is empty. */
    def peekNextDeadline(): Long
  }

  trait TimerSource {

    /** The timers this source accepts: for a worker, its pool's ops and what its scheduler runs later. */
    type Timer <: TimerOp

    /** `timerOp` has a deadline earlier than the heap knows of. A later one needs no call: see `nextReadyTimer`. */
    def notifyDeadlineChange(timerOp: TimerOp): Unit
    def addTimer(timerOp: Timer): Unit

    /** Whether `timerOp` was taken out of the heap, and so is guaranteed to not fire. If false then it was not in the
      * heap to begin with.
      */
    def removeTimer(timerOp: TimerOp): Boolean
  }

  trait TimerOp {
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
  *
  * Whoever waits for its earliest deadline is told when that changes (`earliestChanged`), and when a removal leaves it
  * empty (`emptied`), by whatever its platform has for that: a worker's poller is woken, and Node's event loop has its
  * one timer set again.
  */
abstract class BinaryTimerHeap extends TimerHeap {

  /** Claims the heap for one edit, or one look at it, by the calling thread, and gives it up again: what guards the
    * heap, which is its platform's to choose. Edits are short, never wait, and never claim the heap again inside one.
    * The worker's own thread makes nearly all of them; other threads make some, a future that was moved to another
    * worker and takes its op off this one's heap, the pool's monitor looking for a due timer, whoever schedules or
    * cancels something to run later. So a platform picks what costs least with nobody else there: on the JVM a lock is
    * that, and on Scala Native, where entering a monitor is several atomic operations and a look-up of the current
    * thread, a flag is.
    */
  protected def claimHeap(): Unit
  protected def releaseHeap(): Unit

  private inline def editing[T](inline edit: T): T = {
    claimHeap()
    try edit
    finally releaseHeap()
  }

  /** A timer has become the earliest, or the earliest has moved earlier: called with the heap not claimed. */
  protected def earliestChanged(): Unit

  /** A removal has left the heap empty: called with the heap not claimed. Nothing, unless whoever waits must be told
    * that there is nothing left to wait for.
    */
  protected def emptied(): Unit = ()

  private var heap = new Array[TimerOp](16)
  private var size = 0

  /** Whether any timer is held. */
  final def hasTimers: Boolean = editing(size > 0)

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
  final def nextReadyTimer(now: Long): Timer | Null = editing {
    var next: Timer | Null = null
    var looking = true
    while looking do
      var head: TimerOp | Null = null
      if size == 0 || { head = heap(0); head }.heapKey > now then looking = false
      else if head.deadlineNow > now then rekey(head)
      else
        takeAt(0)
        if head.claimAtDeadline() then
          next = head.asInstanceOf[Timer] // only what `addTimer` took is in the heap
          looking = false
    next
  }

  final def peekNextDeadline(): Long = editing {
    if size > 0 then heap(0).heapKey else -1
  }

  final def debugTimerState() = editing {
    heap.iterator.take(size).mkString("[", ", ", "]")
  }

  /** Tells whoever waits only of a new earliest deadline. */
  final def addTimer(timerOp: Timer): Unit = {
    if !timerOp.hasDeadline then throw new IllegalArgumentException("Only an op with a deadline goes on the heap")
    val isNext = editing {
      if size == heap.length then heap = java.util.Arrays.copyOf(heap, 2 * size)
      timerOp.heapKey = timerOp.deadlineNow
      size += 1
      siftUp(timerOp, size - 1)
      heap(0) eq timerOp
    }
    if isNext then earliestChanged()
  }

  /** Doesn't wake the worker, which at worst wakes for a timer no longer there, and finds nothing to do.
    *
    * A timer that has fired was taken out as it did, and is found so with no lock: its place is read as it was last
    * written, which is the performer's own write as it put the timer in, a place it was moved to since, or the heap's
    * as it took it out, of which the performer, resumed by the firing, sees the last. A place that is stale is not
    * "none", and is then looked at again under the lock.
    */
  final override def removeTimer(timerOp: TimerOp): Boolean =
    if timerOp.heapIndex < 0 then false
    else
      var empty = false
      val held = editing {
        val held = holds(timerOp)
        if held then
          takeAt(timerOp.heapIndex)
          empty = size == 0
        held
      }
      if empty then emptied()
      held

  final def notifyDeadlineChange(timerOp: TimerOp): Unit = {
    val isNext = editing {
      val held = holds(timerOp)
      if held then rekey(timerOp)
      held && (heap(0) eq timerOp)
    }
    if isNext then earliestChanged()
  }
}

/** The heap of a worker, which is woken where it waits on its poller for a new earliest deadline: otherwise it wakes
  * in time as it is.
  */
abstract class TimerHeapImpl extends BinaryTimerHeap with Poller {
  protected final def earliestChanged(): Unit = wake()
}
