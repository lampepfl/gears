package gears.async.io.shareable

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** The tasks given to a worker that it has not come to yet: put in from any thread, taken out in turn by the worker,
  * and, when it has said it has more than it will come to soon, half at a time by a worker with nothing to do.
  */
private[shareable] final class Inbox {
  private val tasks = new ConcurrentLinkedQueue[Runnable]

  /** How many tasks are in the inbox, or about to be: what tells one task waiting its turn from a backlog. */
  private val queued = new AtomicInteger(0)

  /** Whether the worker has tasks to spare: set as it calls for a thief, and until a turn leaves little behind. */
  @volatile private var surplus = false

  def isEmpty: Boolean = tasks.isEmpty()

  def size: Int = queued.get()

  /** Puts `task` last, from any thread: how many were there before it. */
  def add(task: Runnable): Int = {
    val before = queued.getAndIncrement()
    tasks.add(task)
    before
  }

  /** The next task, for the worker or a thief, or null. */
  def take(): Runnable | Null = {
    val task = tasks.poll()
    if task != null then queued.decrementAndGet()
    task
  }

  /** Whether `task` was still here, and has been taken back. */
  def takeBack(task: Runnable): Boolean = {
    val removed = tasks.remove(task)
    if removed then queued.decrementAndGet()
    removed
  }

  def declareSurplus(): Unit = surplus = true

  def clearSurplus(): Unit = if surplus then surplus = false

  /** How many tasks a thief may take half of now: none unless the worker has said it has some to spare. */
  def spare: Int = if surplus then queued.get() else 0

  /** For a worker with nothing to do, on its own thread, whose inbox `thief` is: half of the tasks here, the oldest, if
    * the worker has said it has some to spare. One is returned for the thief to run now, and the rest are put in
    * `thief`; null if there is nothing to take. Half at once, not one at a time: a thief that came back for each would
    * get few of them before the worker had run the rest, and how many would be down to how the two happened to
    * interleave. A worker that is merely awake does not take what another is about to run.
    */
  def takeHalfInto(thief: Inbox): Runnable | Null =
    if !surplus then null
    else
      var more = (queued.get() + 1) / 2 - 1 // besides the first
      val first = take()
      if first != null then
        while more > 0 do
          val task = take()
          if task == null then more = 0
          else
            thief.add(task): Unit
            more -= 1
      first
}
