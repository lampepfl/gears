package gears.async.io.kqueue

import gears.async.io.kqueue.internal.KqueueLoop
import gears.async.io.shareable.BaseHandle
import gears.async.io.shareable.EventSource
import gears.async.io.shareable.Poller
import gears.async.io.shareable.ReadinessOp
import gears.async.io.shareable.TimerHeap.TimerOp

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

trait KqueuePoller(maxEvents: Int) extends Poller, EventSource {

  /** Its timers include every kqueue op: the worker it is part of takes its pool's ops. */
  type Timer >: KqueueOp <: TimerOp
  private val kq = KqueueLoop.open()

  private val zone = Zone.open()

  val events = KqueueLoop.allocateQueue(maxEvents)(using zone)

  private val WakeEvent = 0.toUSize
  KqueueLoop.createAndRegisterEvents(kq, 1) { events =>
    KqueueLoop.addUser(events(0), WakeEvent) // edge-triggered, registered once
  }

  /** Guards writes to `handles`, which a close clears from its own thread. This worker's thread, which alone puts
    * handles there, reads it without: all it can miss is that a close has just taken one out, and a closed handle has
    * no waiter.
    */
  private val handlesLock = new Object

  /** The handle this kqueue listens for, by descriptor number, which is all an event names. The waiters are the
    * handle's own.
    */
  @volatile private var handles = new Array[KqueueHandle | Null](64)

  /** Has the kqueue report `handle` each time it becomes readable, or writable, and at once if it is now: an
    * edge-triggered filter, added once and left until the handle closes, when the kernel takes it with the descriptor,
    * or gives this listener up (`stopListening`). An op that waits meanwhile costs no system call.
    */
  override def startListening(handle: BaseHandle, read: Boolean): Unit = handle match
    case handle: KqueueHandle =>
      val fd = handle.fd
      handlesLock.synchronized {
        if fd >= handles.length then handles = java.util.Arrays.copyOf(handles, Math.max(fd + 1, 2 * handles.length))
        handles(fd) = handle
      }
      KqueueLoop.createAndRegisterEvents(kq, 1) { changes =>
        KqueueLoop.addFile(changes(0), fd, read, clear = true)
      }
    case _ => throw new IllegalArgumentException(s"Not a handle of this backend's: $handle")

  /** Takes the filter off the kqueue, and the handle from `handles` unless this still listens for its other direction.
    */
  override def stopListening(handle: BaseHandle, read: Boolean): Unit = handle match
    case handle: KqueueHandle =>
      val fd = handle.fd
      if !closed then KqueueLoop.deleteFileIfOpen(kq, fd, read): Unit
      if !handle.readiness(!read).listenedBy(this) then
        handlesLock.synchronized {
          if fd < handles.length && (handles(fd) eq handle) then handles(fd) = null
        }
    case _ => ()

  /** Before the handle's descriptor is released, so that its number, another handle's next, never finds this one. */
  override def forgetHandle(handle: BaseHandle): Unit = handle match
    case handle: KqueueHandle =>
      val fd = handle.fd
      handlesLock.synchronized {
        if fd < handles.length && (handles(fd) eq handle) then handles(fd) = null
      }
    case _ => ()

  /** `fd` became ready: its handle's waiter does its work, or, finding it not ready after all, waits on for the next
    * time. With no waiter the handle only counts it, for an op that is about to wait (see
    * `HandleReadiness.registerWaiter`), and to give this listener up once that has happened too often.
    */
  private def dispatch(fd: Int, read: Boolean, available: Long): Unit = {
    val current = handles
    val handle = if fd < current.length then current(fd) else null
    if handle != null then handle.readiness(read).report(this, available)
  }

  /** How much `event` reports its descriptor ready for, bytes to read or room to write, unless it also reports the end
    * of the stream or an error, which no later event follows.
    */
  private def available(event: KqueueLoop.Event): Long =
    if KqueueLoop.isEOF(event) || KqueueLoop.isError(event) then ReadinessOp.Unknown
    else KqueueLoop.data(event).toLong

  /** Set before the kqueue is closed, which the worker does as it leaves its loop. */
  @volatile private var closed = false

  /** A worker that has left has nothing to wake, and a `wake` racing its `close` may find the kqueue gone: neither is a
    * failure. Should the descriptor have been reused by then, the worst this does is wake another kqueue once.
    */
  def wake(): Unit =
    if broken then wakeWaiting()
    else if !closed then
      try KqueueLoop.createAndRegisterEvents(kq, 1)(events => KqueueLoop.triggerUser(events(0), WakeEvent))
      catch
        case _: java.io.IOException if closed => ()
        // The kqueue is failing: its worker is then between polls, not waiting in one, or is about to give it up.
        case _: java.io.IOException => wakeWaiting()

  /** How many polls may fail one after another before the kqueue is given up. */
  private inline val PollAttempts = 3

  /** Polls that have failed since one last succeeded. Worker thread only. */
  private var pollFailures = 0

  /** Set once the kqueue is given up: from then on this waits without it, and reports no handle ready. */
  @volatile private var broken = false

  /** The worker's thread, once it has waited here: what a wake-up unparks when there is no kqueue to trigger. */
  @volatile private var waitingThread: Thread | Null = null

  private def wakeWaiting(): Unit = {
    val thread = waitingThread
    if thread != null then java.util.concurrent.locks.LockSupport.unpark(thread)
  }

  /** One poll of the kqueue into `events`: without waiting if `timeout` is 0, for at most `timeout` nanoseconds if it
    * is positive, and until there is something if it is negative. The number of events.
    */
  protected def pollKqueue(timeout: Long): Int =
    if timeout == 0 then KqueueLoop.pollEventsNow(kq, events, maxEvents)
    else if timeout > 0 then
      KqueueLoop.pollEventsTimeout(
        kq,
        events,
        maxEvents,
        (timeout / 1_000_000_000L).toInt,
        (timeout % 1_000_000_000L).toInt
      )
    else KqueueLoop.pollEventsForever(kq, events, maxEvents)

  /** A poll failed, other than by being interrupted. A kqueue that fails is not something to recover from by trying for
    * ever, which would keep the worker turning at full speed and its ops waiting: after a few attempts, a little apart
    * in case the failure passes, it is given up and its owner told (`pollerFailed`), once. Nothing is rebuilt: what
    * broke it, most likely its descriptor closed from elsewhere, is a bug to be seen, not papered over.
    */
  private def pollFailed(failure: java.io.IOException): Unit = {
    pollFailures += 1
    if pollFailures < PollAttempts then Thread.sleep(pollFailures.toLong) // a millisecond, then two
    else
      broken = true
      pollerFailed(new java.io.IOException(s"kqueue $id gave up after $pollFailures failed polls", failure))
  }

  /** Waiting with no kqueue: parked until the deadline or a wake-up, so that the worker still fires its timers and runs
    * what it is given, and the program can unwind. No handle is ever reported ready.
    */
  private def waitWithoutKqueue(timeout: Long): Unit =
    if timeout > 0 then java.util.concurrent.locks.LockSupport.parkNanos(this, timeout)
    else if timeout < 0 then java.util.concurrent.locks.LockSupport.park(this)

  protected def waitForDeadline(deadlineTask: Long): Unit = {
    val now = System.nanoTime()
    // A wake-up is reported once, and the worker's budgets may have left work behind after the one that came with it:
    // then, as when a deadline is already due, this only looks at what the kqueue has, and does not wait.
    val timeout =
      if !isEmpty() then 0L
      else if deadlineTask >= 0 then Math.max(deadlineTask - now, 0)
      else -1L
    if waitingThread == null then waitingThread = Thread.currentThread()
    val polled =
      if broken then
        waitWithoutKqueue(timeout)
        0
      else
        try
          // First without waiting: under load something is there already, and a poll that may wait costs more,
          // since it tells the GC that the thread is leaving (see `Unblocked`).
          val ready = pollKqueue(0)
          val found = if ready > 0 || timeout == 0 then ready else pollKqueue(timeout)
          pollFailures = 0
          found
        catch
          case failure: java.io.IOException =>
            pollFailed(failure)
            0
    var i = 0
    while i < polled do
      val event = events(i)
      // A wake-up has nothing to hand over: the worker looks at its inbox and timers as `waitForDeadline` returns.
      if KqueueLoop.isReadEvent(event) then dispatch(KqueueLoop.fileIdent(event), read = true, available(event))
      else if KqueueLoop.isWriteEvent(event) then dispatch(KqueueLoop.fileIdent(event), read = false, available(event))
      i += 1
  }

  override def close(): Unit = {
    closed = true
    // A kqueue that was given up may have nothing left to close.
    try KqueueLoop.close(kq)
    catch case _: java.io.IOException if broken => ()
    zone.close()
  }
}
