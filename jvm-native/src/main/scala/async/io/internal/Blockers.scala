package gears.async.io
package internal

import gears.async.io.Slot

import java.util.concurrent.CancellationException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A blocking task run by `Blockers` off a reactor's thread, for a backend's blocking ops: `block` is the body, and
  * `settled` tells the backend it has returned. Cancelling it skips `block` if it has not started, and interrupts the
  * blocker thread if it is running. Interruption is cooperative: blocking JDK calls such as `Thread.sleep` throw
  * `InterruptedException`, and long computations should check `Thread.interrupted()`. Native calls like `getaddrinfo`
  * cannot be interrupted and run to the end, with their result discarded. Either way the completion's `onCancel` waits
  * until `block` has returned, since until then it may still write to the op's buffers and slots.
  *
  * A subclass writes its result, into its slot or the op's buffer, through `publish` or `unlessCancelled`, as the last
  * thing `block` does: once that write is made the op has as good as finished, so a cancel, or its time limit, loses
  * (`cancelUnlessCommitted`) and the op completes with what was written, as one that finished at submission does,
  * rather than being cancelled with its result in the caller's slot; before it, the cancel wins and the write is
  * skipped.
  *
  * A backend that runs a blocking op on the calling thread, as NIO's `NioOnCaller` does, runs it with `runHere`, under
  * the same rules.
  */
trait BlockingRun {
  def block(): Unit

  /** `block` returned, with `failure` if it threw. A cancelled task is detached, so neither outcome reaches its
    * completion, but the reactor learns from it that `block` has returned, which is when its `onCancel` runs.
    */
  protected def settled(failure: Throwable | Null): Unit

  /** Whether a cancel interrupts the thread running `block`: not for a task whose interruption would do harm, such as
    * one on a JDK `FileChannel`, which an interrupt closes; such a task runs to its end, its result discarded unless
    * already written, as a native call's is.
    */
  protected def interruptible: Boolean = true

  /** Whether a cancel is delivered without waiting for `block` to return: for a one-shot task that touches nothing of
    * the op's but through `publish`, which a cancel already keeps from writing, such as a resolve, so that a cancel or
    * a time limit is not held up by a native call that cannot be interrupted (`getaddrinfo`, which can take tens of
    * seconds). Its `block` runs on to its end on the blocker thread, its outcome discarded; one-shot, so it is never
    * restarted meanwhile.
    */
  protected def detachable: Boolean = false

  // Guarded by `this`: the thread running `block`, whether the task was cancelled, and whether `block` has returned.
  private var runner: Thread | Null = null
  private var cancelled = false
  private var ended = false
  private var committed = false // the result was written, so a cancel loses

  /** What `block` threw, if anything: written by the thread that ran it, before `end`, and read there. */
  private var thrown: Throwable | Null = null

  /** Called by the thread about to run `block`; false if the task was cancelled before it started, which ends it: the
    * pool has let go of it, as it has of one whose `block` returned.
    */
  private def begin(): Boolean = synchronized {
    if cancelled then {
      ended = true
      notifyAll()
      false
    } else {
      runner = Thread.currentThread()
      true
    }
  }

  /** Makes the task ready to run again, for a `Repeatable` op submitted again: called by the reactor on each
    * submission, before the task goes to the pool, once its previous run has ended or its cancel has been delivered.
    */
  private[io] def restart(): Unit = synchronized {
    require(runner == null, "A blocking task cannot be restarted while it is running")
    cancelled = false
    ended = false
    committed = false
    thrown = null
  }

  /** Called by the thread that ran `block`, after it. Once this returns no cancel can interrupt the thread, so any
    * interrupt that raced in is cleared and cannot leak into the thread's next task.
    */
  private def end(): Unit = {
    synchronized {
      runner = null
      ended = true
      notifyAll()
    }
    Thread.interrupted()
  }

  /** Runs `block` on the calling thread, unless the task was cancelled first, and returns whether it ran; what it threw
    * is then in `thrown`.
    */
  private def runBlock(): Boolean =
    if !begin() then false
    else {
      try block()
      catch case t: Throwable => thrown = t
      finally end()
      true
    }

  /** Runs the task on a blocker thread: `block`, then `settled` with its outcome. A task cancelled before it started is
    * skipped, and settled all the same, so that the reactor learns the pool has let go of it (see `interrupt`).
    */
  private[io] def runAndSettle(): Unit = {
    val ran = runBlock()
    settled(if ran then thrown else null)
  }

  /** Runs `block` on the calling thread, as a blocker thread would, without settling the task: skipped, with
    * `CancellationException`, if the task was cancelled before it started, and the same thrown once it returns if it
    * was interrupted meanwhile, its result unpublished; otherwise what it threw, if anything, is rethrown.
    */
  def runHere(): Unit = {
    // An interrupt the thread already had is not a cancel of this task: kept aside, so that it neither ends `block` at
    // once nor closes a `FileChannel` it uses, and restored after.
    val interruptedBefore = Thread.interrupted()
    try {
      if !runBlock() || synchronized(cancelled) then throw new CancellationException()
      val failure = thrown
      if failure != null then throw failure
    } finally if interruptedBefore then Thread.currentThread().interrupt()
  }

  /** Marks the task cancelled and interrupts its thread if it is running. Returns true if the pool still holds it,
    * running `block`, which may touch the op's buffers and slots until it returns, or queued to run it, which it will
    * skip: either way the blocker thread reports through `settled` when it has let go, and `waitForEnd` waits for that.
    * False once it has ended, or for a `detachable` task, whose cancel need not wait. For a task given to the pool, or
    * run with `runHere`: one never submitted has not ended either, and a reactor only cancels what it holds pending.
    */
  def interrupt(): Boolean = synchronized {
    cancelled = true
    val running = runner
    if running != null && interruptible then running.interrupt()
    !ended && !detachable
  }

  /** Writes `value` into `into` unless the task has been cancelled. It holds the lock `interrupt` takes, so the write
    * either happens entirely before the cancel or not at all.
    */
  protected final def publish[A <: AnyRef](into: Slot[A], value: A): Unit = unlessCancelled(into.set(value))

  /** Runs `write`, a write to the op's buffers or slots, unless the task has been cancelled, under the lock `interrupt`
    * takes: the write either happens entirely before the cancel or not at all. Returns whether it ran.
    */
  protected final inline def unlessCancelled(write: => Unit): Boolean = synchronized {
    if cancelled then false
    else {
      write
      committed = true
      true
    }
  }

  /** Marks the task cancelled, so that its result is not written, unless it has been already: true if so, for a cancel
    * or a time limit that takes the op, which then interrupts it (`interrupt`); false once the result is written, when
    * the op is as good as finished and completes with it, so the cancel loses.
    */
  private[io] def cancelUnlessCommitted(): Boolean = synchronized {
    if committed then false
    else {
      cancelled = true
      true
    }
  }

  /** What `block` threw, once it has returned (`waitForEnd`): for a reactor that closes while a task whose result is
    * written still runs, which then gets its outcome.
    */
  private[io] def thrownBy: Throwable | Null = synchronized(thrown)

  /** Waits until a running `block` has returned. An interrupt of the waiting thread is kept for later. */
  def waitForEnd(): Unit = {
    var interrupted = false
    synchronized {
      while !ended do
        try wait()
        catch case _: InterruptedException => interrupted = true
    }
    if interrupted then Thread.currentThread().interrupt()
  }
}

/** The blocker pool, shared by every backend: daemon platform threads that run blocking tasks off the reactors'
  * threads, one started whenever a task finds none idle, and each ending once no task has come to it for
  * `IdleTimeoutMillis`, so that a program with no blocking ops keeps none, and one with many at once is not held to a
  * fixed few. Platform threads, not virtual ones: a virtual thread started from another inherits its scheduler, so from
  * a future carried by a reactor pool (`NioBackend` on the JVM) a blocking op would take one of the pool's workers, and
  * a reactor closing on that worker would wait for it forever.
  */
object Blockers {

  /** How long an idle blocker thread waits for a task before ending. */
  val IdleTimeoutMillis = 60_000L

  private val queue = new LinkedBlockingQueue[BlockingRun]()

  /** Idle threads, waiting for a task, that no task has claimed yet: `run` claims one, or starts a thread if there is
    * none. Which thread takes which task does not matter, only that there is one for each.
    */
  private val idle = new AtomicInteger(0)
  private val started = new AtomicInteger(0)

  /** Runs `task` on the pool, unless it is cancelled first, and settles it either way (see `runAndSettle`). */
  def run(task: BlockingRun): Unit = if claim() then queue.put(task) else start(task)

  /** Claims an idle thread for a task, if there is one. */
  private def claim(): Boolean = {
    var count = idle.get()
    while count > 0 do
      if idle.compareAndSet(count, count - 1) then return true
      else count = idle.get()
    false
  }

  private def start(first: BlockingRun): Unit = {
    val thread = new Thread(() => loop(first), s"reactor-blocker-${started.incrementAndGet()}")
    thread.setDaemon(true)
    thread.start()
  }

  /** Runs `first`, then the tasks that come, until none has come for `IdleTimeoutMillis`. */
  private def loop(first: BlockingRun): Unit = {
    var task: BlockingRun | Null = first
    while task != null do {
      Thread.interrupted() // Never start a task with a stale interrupt.
      try task.runAndSettle()
      catch case t: Throwable => t.printStackTrace() // A backend's `settled` threw: the thread goes on.
      task = next()
    }
  }

  /** Waits for the next task, idle: null once none has come for `IdleTimeoutMillis`, unless a task claimed this thread
    * meanwhile, in which case that task is on its way and the wait goes on.
    */
  private def next(): BlockingRun | Null = {
    idle.incrementAndGet()
    var claimed = false
    var task: BlockingRun | Null = null
    while task == null do
      try
        if claimed then task = queue.take()
        else {
          task = queue.poll(IdleTimeoutMillis, TimeUnit.MILLISECONDS)
          if task == null then
            if claim() then return null // timed out, and no task claimed this thread: it leaves
            else claimed = true // a task did, and is on its way
        }
      catch case _: InterruptedException => () // A late interrupt must not end the thread.
    task
  }
}
