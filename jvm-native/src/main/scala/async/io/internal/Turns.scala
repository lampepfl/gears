package gears.async.io.internal

import java.util.concurrent.atomic.AtomicReference

/** The turn to read a handle, or to write it, which one op pending at a time holds, on any submitter (see
  * `Checks.alreadyPending`). An op on a stream that finds it held is refused, since two reads would take the stream's
  * bytes in no order and two writes would interleave theirs. An op on a datagram socket or a listener, each of whose
  * receives, sends and accepts is whole, waits its turn instead (`enqueue`), behind those waiting already, as Go's fd
  * lock queues the reads and writes of a socket's concurrent callers: the turn passes to it once the op holding it lets
  * go (`release`), and it is told so (`onTurn`), on the thread letting go, to go on on its own submitter.
  *
  * The holder is taken and let go with one compare-and-set while nobody waits; the queue is under a lock.
  */
final class Turns {
  private val holder = new AtomicReference[AnyRef | Null](null)

  // The ops waiting, in the order they came, each with what it is told once given the turn; `waiters` is its size,
  // read without the lock.
  private val queue = new java.util.ArrayDeque[Turns.Waiter]()
  @volatile private var waiters = 0

  /** Takes the turn for `op` if it is free and nobody waits for it: true if it did. */
  def take(op: AnyRef): Boolean = waiters == 0 && holder.compareAndSet(null, op)

  /** Whether `op` holds the turn. */
  def holds(op: AnyRef): Boolean = holder.get() eq op

  /** Queues `op` for the turn, behind those waiting already: `onTurn` runs once it holds it, on the thread passing it,
    * perhaps this one, before this returns, if the turn was let go meanwhile.
    */
  def enqueue(op: AnyRef, onTurn: Runnable): Unit = {
    synchronized {
      queue.add(Turns.Waiter(op, onTurn))
      waiters += 1
    }
    passOn() // the holder may have let go before `op` was queued, finding nobody waiting
  }

  /** Takes `op` out of the queue, if it waits there: true if it did, false if it was never queued or holds the turn
    * now.
    */
  def withdraw(op: AnyRef): Boolean = synchronized {
    val it = queue.iterator()
    var found = false
    while !found && it.hasNext do
      if it.next().op eq op then {
        it.remove()
        waiters -= 1
        found = true
      }
    found
  }

  /** Lets go of the turn `op` holds, passing it to the op waiting longest; nothing if `op` does not hold it. */
  def release(op: AnyRef): Unit =
    if holder.compareAndSet(op, null) && waiters > 0 then passOn()

  /** Gives the free turn to the op waiting longest, if one waits, and tells it. */
  private def passOn(): Unit = {
    val next = synchronized {
      val head = queue.peek()
      if head != null && holder.compareAndSet(null, head.op) then {
        queue.poll()
        waiters -= 1
        head
      } else null
    }
    if next != null then next.onTurn.run()
  }

  override def toString: String = s"Turns(held by ${holder.get()}, $waiters waiting)"
}

object Turns {
  private final case class Waiter(op: AnyRef, onTurn: Runnable)
}
