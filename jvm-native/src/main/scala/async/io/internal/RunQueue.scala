package gears.async.io
package internal

import java.util.concurrent.atomic.AtomicLong

/** A worker's queue of runnable tasks: a fixed ring, first in first out for the thread that owns it, from which another
  * thread can move about half at a time into a queue of its own. The design is Tokio's local run queue.
  *
  * Only the owner adds tasks, at the tail, and it takes them from the head. Another thread claims a run of tasks at the
  * head with one compare-and-swap, copies them into its own queue, then releases the claim. The head therefore holds
  * two indices, packed into one `Long` so that one compare-and-swap updates both: `real`, where the next task to take
  * is, and `claimed`, where the claimed tasks start. They differ only while another thread is copying. The owner may go
  * on taking tasks meanwhile, but does not reuse the claimed slots until the claim is released, since it counts free
  * space from `claimed`.
  *
  * Indices count up without bound, wrapping at 2^32, and only their differences and their low bits, as a slot, are
  * used. A task written into a slot is published by the owner's volatile write of the tail that follows it, and a
  * thread moving tasks reads the tail before the slots.
  */
private[io] final class RunQueue {
  import RunQueue.*

  private val buffer = new Array[Runnable](Capacity)

  private val head = new AtomicLong(0L)

  // Written by the owner only.
  @volatile private var tail = 0

  /** How many tasks are queued, as the owner sees it. */
  def size: Int = tail - real(head.get())

  def isEmpty: Boolean = size == 0

  /** Adds `task` at the tail, and returns false if the queue is full. On the owning thread only. */
  def push(task: Runnable): Boolean = {
    val t = tail
    if t - claimed(head.get()) >= Capacity then false
    else {
      buffer(t & Mask) = task
      tail = t + 1
      true
    }
  }

  /** Moves up to `limit` tasks from an owner-local backlog. A full destination leaves the next task in the backlog;
    * removing first would silently lose a continuation. Both queues must be owned by the caller.
    */
  def moveFrom(backlog: java.util.ArrayDeque[Runnable], limit: Int): Int = {
    var moved = 0
    while moved < limit && !backlog.isEmpty && push(backlog.peek()) do {
      backlog.poll()
      moved += 1
    }
    moved
  }

  /** Takes the task at the head, or returns null if there is none. On the owning thread only. */
  def pop(): Runnable | Null = {
    var h = head.get()
    while true do {
      val r = real(h)
      if r == tail then return null
      // With no other thread copying, `claimed` follows `real`; otherwise it stays where that thread's claim starts.
      val next = if claimed(h) == r then pack(r + 1, r + 1) else pack(claimed(h), r + 1)
      if head.compareAndSet(h, next) then {
        val task = buffer(r & Mask)
        buffer(r & Mask) = null // so that a finished task's continuation can be collected
        return task
      }
      h = head.get()
    }
    null
  }

  /** Moves about half of this queue's tasks to the tail of `into`, which the calling thread must own, and returns how
    * many it moved: none if this queue is empty, another thread is already moving tasks from it, or `into` is more than
    * half full.
    */
  def moveHalfTo(into: RunQueue): Int = {
    val intoTail = into.tail
    if intoTail - claimed(into.head.get()) > Capacity / 2 then return 0
    // Claim: advance `real` past the run to take, leaving `claimed` at its start.
    var h = head.get()
    var start = 0
    var count = 0
    var won = false
    while !won do {
      val r = real(h)
      if claimed(h) != r then return 0 // another thread is copying
      val available = tail - r
      count = available - available / 2
      if count <= 0 then return 0
      if head.compareAndSet(h, pack(r, r + count)) then {
        start = r
        won = true
      } else h = head.get()
    }
    var i = 0
    while i < count do {
      val slot = (start + i) & Mask
      into.buffer((intoTail + i) & Mask) = buffer(slot)
      buffer(slot) = null
      i += 1
    }
    // Release the claim: `claimed` catches up with `real`, which the owner may have moved on meanwhile.
    h = head.get()
    while !head.compareAndSet(h, pack(real(h), real(h))) do h = head.get()
    into.tail = intoTail + count // publishes the copied tasks in the calling thread's own queue
    count
  }
}

private[io] object RunQueue {

  /** How many tasks a queue holds; a power of two. */
  inline val Capacity = 256

  private inline val Mask = Capacity - 1

  private inline def pack(claimed: Int, real: Int): Long = (claimed.toLong << 32) | (real.toLong & 0xffffffffL)

  private inline def claimed(head: Long): Int = (head >>> 32).toInt

  private inline def real(head: Long): Int = head.toInt
}
