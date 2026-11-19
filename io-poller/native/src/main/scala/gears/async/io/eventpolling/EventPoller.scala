package gears.async.io.eventpolling

import gears.async.io.shareable.PoolSource
import gears.async.io.shareable.TimerHeap.TimerOp

import java.io.IOException

/** A worker's event source on Scala Native: the system's queue of events for the descriptors it is asked to watch,
  * kqueue on macOS and FreeBSD (`KqueuePoller`) and epoll on Linux (`EpollPoller`). It is what this backend's handles
  * are listened for by and what its ops wait on, whichever of the two the program was linked for.
  *
  * What the two have in common is here: which handle each descriptor is, and which of reading and writing it is
  * watched for; how the worker waits, looking before it sleeps; what a wake-up does; and what becomes of a queue that
  * fails. A poller says how its queue is told to watch a descriptor (`watch`), looked at and waited on (`lookNow`,
  * `lookBeforeWaiting`, `waitFor`), what each event it gave says (`report`), and how it is woken and closed
  * (`trigger`, `closeQueue`).
  */
trait EventPoller extends PoolSource {

  /** Its timers include every op of this backend's: the worker it is part of takes its pool's ops. */
  type Timer >: EventPollingOp <: TimerOp

  /** It listens for this backend's handles that are waited for, each of which has a descriptor. */
  type Handle = EventPollingWaitable

  // ---- what a poller says of its queue

  /** What the queue is called, in what is said of its failures. */
  protected def queueName: String

  /** Has the queue watch `fd` for the directions `after`, where it watched it for `before`: `EventPoller.Read`,
    * `EventPoller.Write`, both or neither. One direction comes or goes at a time. The watch is edge-triggered, and
    * reports a descriptor that is ready as it begins. `certain` is whether a failure is to be thrown: taking a watch
    * off a descriptor that may have closed, which took the watch with it, is done as far as it can be.
    */
  protected def watch(fd: Int, before: Int, after: Int, certain: Boolean): Unit

  /** Looks at the queue without waiting, for a worker with work left, which must not be kept: how many events. */
  protected def lookNow(): Int

  /** Looks at the queue for a worker about to wait: how many events. `lookNow`, unless the queue has a cheaper way to
    * catch what is on its way than a wait that puts the thread to sleep.
    */
  protected def lookBeforeWaiting(): Int = lookNow()

  /** Waits on the queue for at most `timeout` nanoseconds, or until there is something if that is negative: how many
    * events.
    */
  protected def waitFor(timeout: Long): Int

  /** What the `index`th event of the last look or wait says, by `dispatch`. A wake-up says nothing. */
  protected def report(index: Int): Unit

  /** Makes a wait on the queue return, from any thread. */
  protected def trigger(): Unit

  /** Closes the queue, and what the poller opened beside it. */
  protected def closeQueue(): Unit

  // ---- the worker's heap of timers

  /** Whether the worker's heap of timers is being edited (see `TimerHeapImpl.claimHeap`). */
  private val heapClaimed = new java.util.concurrent.atomic.AtomicBoolean(false)

  /** A flag, taken with one atomic operation and given up with another: a thread that finds it taken, which is rare, as
    * nearly every edit is the worker's own, tries again at once a few times, the edit being a few instructions long,
    * and then gives way between tries, in case whoever holds it is not running.
    */
  protected final def claimHeap(): Unit =
    if !heapClaimed.compareAndSet(false, true) then
      var tries = 0
      while !heapClaimed.compareAndSet(false, true) do
        tries += 1
        if tries < 64 then Thread.onSpinWait() else Thread.`yield`()

  protected final def releaseHeap(): Unit = heapClaimed.set(false)

  // ---- the handles listened for

  /** Guards `handles` and `watched`, and the watches they describe: a direction is listened for, or given up, from
    * whichever thread first waits on a handle or closes it, and two for one descriptor must not cross.
    */
  private val handlesLock = new Object

  /** The handle this listens for, by descriptor number, which is all an event names. The waiters are the handle's own.
    * Read by the worker's thread without the lock: all it can miss is that a close has just taken one out, and a closed
    * handle has no waiter.
    */
  @volatile private var handles = new Array[EventPollingWaitable | Null](64)

  /** The directions each descriptor is watched for. Under the lock. */
  private var watched = new Array[Byte](64)

  /** Has the queue report `handle` each time it becomes readable, or writable, and at once if it is now: a watch added
    * once and left until the handle closes, when the system takes it with the descriptor, or gives this listener up
    * (`stopListening`). An op that waits meanwhile costs no system call.
    */
  override final def startListening(handle: EventPollingWaitable, read: Boolean): Unit = {
    val fd = handle.fd
    handlesLock.synchronized {
      if fd >= handles.length then
        val size = Math.max(fd + 1, 2 * handles.length)
        handles = java.util.Arrays.copyOf(handles, size)
        watched = java.util.Arrays.copyOf(watched, size)
      // A number met again is another descriptor's, whose watches went with the last to have it.
      val before = if handles(fd) eq handle then watched(fd).toInt else 0
      val after = before | (if read then EventPoller.Read else EventPoller.Write)
      handles(fd) = handle
      watch(fd, before, after, certain = true)
      watched(fd) = after.toByte
    }
  }

  /** Takes the direction's watch off, and the handle from `handles` with its last. */
  override final def stopListening(handle: EventPollingWaitable, read: Boolean): Unit = {
    val fd = handle.fd
    handlesLock.synchronized {
      if fd < handles.length && (handles(fd) eq handle) then
        val before = watched(fd).toInt
        val after = before & ~(if read then EventPoller.Read else EventPoller.Write)
        if !closed && after != before then watch(fd, before, after, certain = false)
        watched(fd) = after.toByte
        if after == 0 then handles(fd) = null
    }
  }

  /** Before the handle's descriptor is released, so that its number, another handle's next, never finds this one. */
  override final def forgetHandle(handle: EventPollingWaitable): Unit = {
    val fd = handle.fd
    handlesLock.synchronized {
      if fd < handles.length && (handles(fd) eq handle) then
        handles(fd) = null
        watched(fd) = 0
    }
  }

  /** `fd` became ready: its handle's waiter does its work, or, finding it not ready after all, waits on for the next
    * time. With no waiter the handle only counts it, for an op that is about to wait (see
    * `HandleReadiness.registerWaiter`), and to give this listener up once that has happened too often. `available` is
    * how much it is ready for, where the queue says, or `ReadinessOp.Unknown`.
    */
  protected final def dispatch(fd: Int, read: Boolean, available: Long): Unit = {
    val current = handles
    val handle = if fd < current.length then current(fd) else null
    if handle != null then handle.readiness(read).report(this, available)
  }

  // ---- waking, failing and closing

  /** Set before the queue is closed, which the worker does as it leaves its loop. */
  @volatile private var closed = false

  /** A worker that has left has nothing to wake, and a `wake` racing its `close` may find the queue gone: neither is a
    * failure. Should its descriptor have been reused by then, the worst this does is wake another once.
    */
  final def wake(): Unit =
    if hasGivenUp then wakeWaiting()
    else if !closed then
      try trigger()
      catch
        case _: IOException if closed => ()
        // The queue is failing: its worker is then between polls, not waiting in one, or is about to give it up.
        case _: IOException => wakeWaiting()

  /** Polls that have failed since one last succeeded. Worker thread only. */
  private var pollFailures = 0

  /** A poll failed, other than by being interrupted. A queue that fails is not something to recover from by trying for
    * ever, which would keep the worker turning at full speed and its ops waiting: after a few attempts, a little apart
    * in case the failure passes, it is given up and its owner told (`pollerFailed`), once. Nothing is rebuilt: what
    * broke it, most likely its descriptor closed from elsewhere, is a bug to be seen, not papered over.
    */
  private def pollFailed(failure: IOException): Unit = {
    pollFailures += 1
    if pollFailures < EventPoller.PollAttempts then Thread.sleep(pollFailures.toLong) // a millisecond, then two
    else giveUp(new IOException(s"$queueName $id gave up after $pollFailures failed polls", failure))
  }

  protected final def waitForDeadline(deadlineTask: Long): Unit = {
    val now = System.nanoTime()
    // A wake-up is reported once, and the worker's budgets may have left work behind after the one that came with it:
    // then, as when a deadline is already due, this only looks at what the queue has, and does not wait.
    val timeout =
      if !isEmpty() then 0L
      else if deadlineTask >= 0 then Math.max(deadlineTask - now, 0)
      else -1L
    val polled =
      if hasGivenUp then
        waitWithoutQueue(timeout)
        0
      else
        try
          val found =
            if timeout == 0 then lookNow()
            else
              // About to wait, but first a look: under load something is there already, and a wait costs more, as
              // it tells the GC that the thread is leaving (see `Unblocked`) and puts it to sleep.
              val ready = lookBeforeWaiting()
              if ready > 0 then ready else waitFor(timeout)
          pollFailures = 0
          found
        catch
          case failure: IOException =>
            pollFailed(failure)
            0
    var i = 0
    while i < polled do
      report(i)
      i += 1
  }

  override final def close(): Unit = {
    closed = true
    // A queue that was given up may have nothing left to close.
    try closeQueue()
    catch case _: IOException if hasGivenUp => ()
  }
}

object EventPoller {

  /** The directions a descriptor is watched for, as bits. */
  inline val Read = 1
  inline val Write = 2

  /** How many polls may fail one after another before the queue is given up. */
  private inline val PollAttempts = 3
}
