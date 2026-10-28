package gears.async.io
package internal

/** What a reactor must do at a time, in a binary min-heap ordered by deadline, then submission order: its timers, and
  * the ops with a time limit (see `Submitter.submit`), which time out when their deadline comes. Each entry knows its
  * index, so it can be removed when it is cancelled, or when an op finishes before its time. On the reactor's thread
  * only.
  */
final class TimerHeap[T <: TimerHeap.Entry] {
  private var heap = new Array[TimerHeap.Entry | Null](16)
  private var size = 0

  def isEmpty: Boolean = size == 0
  def peek: T = heap(0).nn.asInstanceOf[T]

  def add(timer: T): Unit = {
    require(timer.heapIndex < 0, s"$timer is already in the heap")
    if size == heap.length then heap = java.util.Arrays.copyOf(heap, size * 2)
    place(timer, size)
    size += 1
    siftUp(size - 1)
  }

  def poll(): T = {
    val timer = peek
    remove(timer)
    timer
  }

  def remove(timer: T): Unit = {
    val index = timer.heapIndex
    size -= 1
    val last = heap(size).nn
    heap(size) = null
    timer.heapIndex = -1
    if index != size then {
      place(last, index)
      siftDown(index)
      siftUp(index)
    }
  }

  private def before(a: TimerHeap.Entry, b: TimerHeap.Entry): Boolean =
    a.when < b.when || (a.when == b.when && a.order < b.order)

  private def place(timer: TimerHeap.Entry, index: Int): Unit = {
    heap(index) = timer
    timer.heapIndex = index
  }

  private def siftUp(start: Int): Unit = {
    var index = start
    val timer = heap(index).nn
    var moving = true
    while moving && index > 0 do {
      val parent = (index - 1) / 2
      if before(timer, heap(parent).nn) then {
        place(heap(parent).nn, index)
        index = parent
      } else moving = false
    }
    place(timer, index)
  }

  private def siftDown(start: Int): Unit = {
    var index = start
    val timer = heap(index).nn
    var moving = true
    while moving do {
      val left = 2 * index + 1
      if left >= size then moving = false
      else {
        val right = left + 1
        val child = if right < size && before(heap(right).nn, heap(left).nn) then right else left
        if before(heap(child).nn, timer) then {
          place(heap(child).nn, index)
          index = child
        } else moving = false
      }
    }
    place(timer, index)
  }
}

object TimerHeap {

  /** An entry's place in the heap: when it is due (`System.nanoTime`), its place in submission order, and its index in
    * the heap, -1 when not in it.
    */
  trait Entry {
    var when = 0L
    var order = 0L
    var heapIndex = -1
  }
}
