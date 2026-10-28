package gears.async.io
package internal

/** What a backend's timer op keeps, for its reactor or its caller: when this submission is due, its time limit, and the
  * deadline it is filed under in a timer heap, so that a timer's deadline may move while it is pending (`Deadline`):
  * one moved later stays filed under the old, and is filed again under the new when the old comes (`due`), which costs
  * a move nothing; one moved earlier tells its waiter (`TimerWaiter.moved`), which files it again, or wakes.
  */
private[io] trait TimerState extends TimerHeap.Entry {

  /** Its time from each submission, or negative for a deadline timer, whose deadline is `at`. */
  protected def nanos: Long

  /** When this submission is due; for a deadline timer, its deadline, moved from any thread. */
  @volatile private[io] var at: Long = 0L

  /** This submission's time limit, on `System.nanoTime`'s clock, or `Long.MaxValue` for none. */
  private[io] var limit: Long = Long.MaxValue

  /** The deadline it is filed under, for a move from another thread to compare. */
  @volatile private[io] var filed: Long = Long.MaxValue

  /** Who to tell of a move earlier while it is pending: its reactor, or the thread waiting on it; null when not
    * pending.
    */
  @volatile private[io] var waiter: TimerWaiter | Null = null

  /** Readies a submission at `now`, with a time limit of `timeoutMillis` unless negative. */
  private[io] def start(now: Long, timeoutMillis: Int): Unit = {
    if nanos >= 0 then at = now + nanos
    limit = if timeoutMillis < 0 then Long.MaxValue else now + timeoutMillis * 1_000_000L
  }

  /** When to file it: its deadline, or its limit if that comes first. */
  private[io] def key: Long = math.min(at, limit)

  /** What is due of it at `now`, filed under a key that has come: `TimerState.Complete` its deadline,
    * `TimerState.Expire` its limit, which comes first, or `TimerState.Again` neither, its deadline having moved later.
    */
  private[io] def due(now: Long): Int = {
    val deadline = at
    if limit < deadline && now >= limit then TimerState.Expire
    else if now >= deadline then TimerState.Complete
    else TimerState.Again
  }

  /** Moves a deadline timer's deadline (see `Deadline.moveTo`). */
  protected final def move(nanoTime: Long): Unit = {
    at = nanoTime
    val w = waiter
    if w != null && nanoTime < filed then w.moved(this)
  }
}

private[io] object TimerState {
  inline val Complete = 0
  inline val Expire = 1
  inline val Again = 2
}

/** What waits on a pending timer: told when its deadline moves earlier than it was filed under, from any thread. */
private[io] trait TimerWaiter {
  def moved(timer: TimerState): Unit
}
