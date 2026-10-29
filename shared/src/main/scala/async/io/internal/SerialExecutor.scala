package gears.async.io.internal

/** An executor whose tasks run serially on one owning thread. Unlike a work-stealing scheduler, tasks submitted here
  * must never migrate. Useful for cancellation and other mutations of thread-owned resources.
  */
trait SerialExecutor {
  def isCurrent: Boolean

  /** Queues a task on the owning thread, waking it if necessary. May be called from any thread. */
  def execute(task: Runnable): Unit

  /** Runs locally without queueing, or dispatches to the owning thread. */
  inline def dispatch(inline body: Unit): Unit =
    if isCurrent then body else execute(() => body)
}
