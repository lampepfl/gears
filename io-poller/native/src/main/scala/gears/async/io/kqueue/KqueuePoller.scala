package gears.async.io.kqueue

import gears.async.io.eventpolling.EventPoller
import gears.async.io.kqueue.internal.KqueueLoop
import gears.async.io.shareable.ReadinessOp

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** The event poller of macOS and FreeBSD: a kqueue, with a filter for each direction of each descriptor it watches. */
trait KqueuePoller(maxEvents: Int) extends EventPoller {

  protected final def queueName: String = "kqueue"

  private val kq = KqueueLoop.open()

  private val zone = Zone.open()

  val events = KqueueLoop.allocateQueue(maxEvents)(using zone)

  private val WakeEvent = 0.toUSize
  KqueueLoop.createAndRegisterEvents(kq, 1) { events =>
    KqueueLoop.addUser(events(0), WakeEvent) // edge-triggered, registered once
  }

  /** A user event of this worker's own, which a look that must not wait triggers as it looks, so that there is always
    * something to report. A `kevent` given a timeout of zero does not return at once when nothing is pending: on macOS
    * (measured on 27.2) it takes some twelve microseconds, where one with an event to report takes a fraction of one.
    * The event says nothing: like a wake-up, it is neither a read nor a write, and is passed over.
    */
  private val PollMarker = 1.toUSize
  KqueueLoop.createAndRegisterEvents(kq, 1) { events =>
    KqueueLoop.addUser(events(0), PollMarker)
  }
  private val markerTrigger = KqueueLoop.allocateQueue(1)(using zone)
  KqueueLoop.triggerUser(markerTrigger(0), PollMarker)

  /** A filter for the direction that came, edge-triggered, or the one that went taken off: the other is left as it
    * is. A filter already gone because its descriptor closed is no failure of taking it off.
    */
  protected final def watch(fd: Int, before: Int, after: Int, certain: Boolean): Unit = {
    val came = after & ~before
    val went = before & ~after
    if (came & EventPoller.Read) != 0 then register(fd, read = true)
    if (came & EventPoller.Write) != 0 then register(fd, read = false)
    if (went & EventPoller.Read) != 0 then KqueueLoop.deleteFileIfOpen(kq, fd, read = true): Unit
    if (went & EventPoller.Write) != 0 then KqueueLoop.deleteFileIfOpen(kq, fd, read = false): Unit
  }

  private def register(fd: Int, read: Boolean): Unit =
    KqueueLoop.createAndRegisterEvents(kq, 1) { changes =>
      KqueueLoop.addFile(changes(0), fd, read, clear = true)
    }

  /** How much `event` reports its descriptor ready for, bytes to read or room to write, unless it also reports the end
    * of the stream or an error, which no later event follows.
    */
  private def available(event: KqueueLoop.Event): Long =
    if KqueueLoop.isEOF(event) || KqueueLoop.isError(event) then ReadinessOp.Unknown
    else KqueueLoop.data(event).toLong

  protected final def report(index: Int): Unit = {
    val event = events(index)
    // A wake-up, or the marker, has nothing to hand over: the worker looks at its inbox and timers after the poll.
    if KqueueLoop.isReadEvent(event) then dispatch(KqueueLoop.fileIdent(event), read = true, available(event))
    else if KqueueLoop.isWriteEvent(event) then dispatch(KqueueLoop.fileIdent(event), read = false, available(event))
  }

  protected final def trigger(): Unit =
    KqueueLoop.createAndRegisterEvents(kq, 1)(events => KqueueLoop.triggerUser(events(0), WakeEvent))

  /** With work left the look must not be kept: the one that triggers the marker. */
  protected final def lookNow(): Int = pollKqueue(0)

  /** With a timeout of zero and no marker: where nothing is pending, what the system takes over a timeout of zero is a
    * wait of microseconds that an answer on its way ends, at less than a poll that puts the thread to sleep costs.
    */
  override protected final def lookBeforeWaiting(): Int = pollBriefly()

  protected final def waitFor(timeout: Long): Int = pollKqueue(timeout)

  /** One poll of the kqueue into `events` by a worker about to wait: at once if there is something, and otherwise for
    * as long as the system takes over a timeout of zero (see `KqueueLoop.pollEventsBriefly`). The number of events.
    */
  protected def pollBriefly(): Int = KqueueLoop.pollEventsBriefly(kq, events, maxEvents)

  /** One poll of the kqueue into `events`: without waiting if `timeout` is 0, for at most `timeout` nanoseconds if it
    * is positive, and until there is something if it is negative. The number of events, the marker among them where
    * it did not wait.
    */
  protected def pollKqueue(timeout: Long): Int =
    if timeout == 0 then KqueueLoop.pollEventsNow(kq, markerTrigger, events, maxEvents)
    else if timeout > 0 then
      KqueueLoop.pollEventsTimeout(
        kq,
        events,
        maxEvents,
        (timeout / 1_000_000_000L).toInt,
        (timeout % 1_000_000_000L).toInt
      )
    else KqueueLoop.pollEventsForever(kq, events, maxEvents)

  protected final def closeQueue(): Unit =
    try KqueueLoop.close(kq)
    finally zone.close()
}
