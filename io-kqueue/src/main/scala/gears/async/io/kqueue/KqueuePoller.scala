package gears.async.io.kqueue

import gears.async.io.kqueue.internal.KqueueLoop
import gears.async.io.shareable.BaseHandle
import gears.async.io.shareable.EventSource
import gears.async.io.shareable.Poller
import gears.async.io.shareable.ReadinessOp

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

trait KqueuePoller(maxEvents: Int) extends Poller, EventSource {
  private val kq = KqueueLoop.open()

  private val zone = Zone.open()

  val events = KqueueLoop.allocateQueue(maxEvents)(using zone)

  type ThisWorkerSource = KqueuePoller
  def asThisWorkerSource: this.type & ThisWorkerSource = this

  private val WakeEvent = 0.toUSize
  KqueueLoop.createAndRegisterEvents(kq, 1) { events =>
    KqueueLoop.addUser(events(0), WakeEvent) // edge-triggered, registered once
  }

  /** Guards `handles`, which a close clears from its own thread. */
  private val handlesLock = new Object

  /** The handle this kqueue listens for, by descriptor number, which is all an event names. The waiters are the
    * handle's own.
    */
  private var handles = new Array[KqueueHandle | Null](64)

  /** Has the kqueue report `handle` each time it becomes readable, or writable, and at once if it is now: an
    * edge-triggered filter, added once and left until the handle closes, when the kernel takes it with the
    * descriptor, or gives this listener up (`stopListening`). An op that waits meanwhile costs no system call.
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

  /** Takes the filter off the kqueue, and the handle from `handles` unless this still listens for its other
    * direction.
    */
  override def stopListening(handle: BaseHandle, read: Boolean): Unit = handle match
    case handle: KqueueHandle =>
      val fd = handle.fd
      if !closed then KqueueLoop.deleteFileIfOpen(kq, fd, read): Unit
      if !handle.listenedBy(!read, this) then
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
    * time. With no waiter the handle only counts it, for an op that is about to wait (see `awaitReadiness`), and to
    * give this listener up once that has happened too often.
    */
  private def dispatch(fd: Int, read: Boolean, available: Long): Unit = {
    val handle = handlesLock.synchronized(if fd < handles.length then handles(fd) else null)
    if handle != null then
      val op = handle.readyWaiter(read, this)
      // If a cancel settles the op as its claim is given back, its performer clears it.
      if op != null && !op.readyWakeup(available, this) then op.releaseReady(): Unit
  }

  /** How much `event` reports its descriptor ready for, bytes to read or room to write, unless it also reports the
    * end of the stream or an error, which no later event follows.
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
    if !closed then
      try KqueueLoop.createAndRegisterEvents(kq, 1)(events => KqueueLoop.triggerUser(events(0), WakeEvent))
      catch case _: java.io.IOException if closed => ()

  protected def waitForDeadline(deadlineTask: Long): Unit = {
    val now = System.nanoTime()
    // A wake-up is reported once, and the worker's budgets may have left work behind after the one that came with it:
    // then, as when a deadline is already due, this only looks at what the kqueue has, and does not wait.
    val timeout =
      if !isEmpty() then 0L
      else if deadlineTask >= 0 then Math.max(deadlineTask - now, 0)
      else -1L
    // First without waiting: under load something is there already, and a poll that may wait costs more, since it
    // tells the GC that the thread is leaving (see `Unblocked`).
    val ready = KqueueLoop.pollEventsNow(kq, events, maxEvents)
    val polled =
      if ready > 0 || timeout == 0 then ready
      else if timeout > 0 then
        KqueueLoop.pollEventsTimeout(
          kq,
          events,
          maxEvents,
          (timeout / 1_000_000_000L).toInt,
          (timeout % 1_000_000_000L).toInt
        )
      else KqueueLoop.pollEventsForever(kq, events, maxEvents)
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
    KqueueLoop.close(kq)
    zone.close()
  }
}
