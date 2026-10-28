package gears.async.io
package kqueue

import gears.async.io.internal.Bracket.FileOperation
import gears.async.io.internal.{Bracket, PosixErr, Unblocked}

import java.io.IOException
import scala.scalanative.annotation.alwaysinline
import scala.scalanative.bsd.kevent
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.errno as posixErrno
import scala.scalanative.posix.time.timespec
import scala.scalanative.posix.timeOps.*
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.USize
import scala.scalanative.unsigned.{*, given}

import PosixErr.cError

object KqueueLoop {

  // posixlib owns the native layout. Keep buffers opaque so callers cannot
  // accidentally advance by one byte instead of one event.
  opaque type Event = Ptr[Byte]
  opaque type EventQueue = Ptr[Byte]

  object EventQueue {
    extension (queue: EventQueue) inline def apply(inline index: Int): Event = eventAt(queue, index)
  }

  private val eventSize = kevent.scalanative_kevent_size()

  @alwaysinline def eventAt(events: EventQueue, index: Int): Event =
    events + eventSize * index.toCSize

  def debugEvent(evt: Event): Unit = {
    val ident = stackalloc[USize]()
    val filter = stackalloc[CShort]()
    val flags = stackalloc[CUnsignedShort]()
    val fflags = stackalloc[CUnsignedInt]()
    val data = stackalloc[Size]()
    val udata = stackalloc[CVoidPtr]()
    kevent.scalanative_kevent_get(evt, 0, ident, filter, flags, fflags, data, udata)
    println(
      s"kevent{ident=${!ident}, filter=${!filter}, flags=${!flags}, fflags=${!fflags}, data=${!data}, udata=${!udata}}"
    )
  }

  @alwaysinline
  def ident(evt: Event): USize = {
    val value = stackalloc[USize]()
    kevent.scalanative_kevent_get(evt, 0, value, null, null, null, null, null)
    !value
  }

  @alwaysinline
  def filter(evt: Event): CShort = {
    val value = stackalloc[CShort]()
    kevent.scalanative_kevent_get(evt, 0, null, value, null, null, null, null)
    !value
  }

  @alwaysinline
  def flags(evt: Event): CUnsignedShort = {
    val value = stackalloc[CUnsignedShort]()
    kevent.scalanative_kevent_get(evt, 0, null, null, value, null, null, null)
    !value
  }

  @alwaysinline
  def data(evt: Event): Size = {
    val value = stackalloc[Size]()
    kevent.scalanative_kevent_get(evt, 0, null, null, null, null, value, null)
    !value
  }

  @alwaysinline
  def isError(evt: Event): Boolean = (flags(evt).toInt & kevent.EV_ERROR) != 0

  @alwaysinline
  def isEOF(evt: Event): Boolean = (flags(evt).toInt & kevent.EV_EOF) != 0

  @alwaysinline
  def errno(evt: Event): CInt = data(evt).toInt

  @alwaysinline
  def rwAvailable(evt: Event): CInt = data(evt).toInt

  @alwaysinline
  def isReadEvent(evt: Event): Boolean = filter(evt) == kevent.EVFILT_READ

  @alwaysinline
  def isWriteEvent(evt: Event): Boolean = filter(evt) == kevent.EVFILT_WRITE

  def fileIdent(evt: Event): Int = ident(evt).toInt

  /** Runs `op` with a stack-allocated buffer for `nevents` events. Inline, with an inline `op`, so no closure is
    * allocated.
    */
  inline def pollQueue(nevents: Int)(inline op: EventQueue => Unit): Unit = {
    require(nevents > 0, "Event buffer capacity must be positive")
    // Keep the multiplication outside stackalloc: Scala Native 0.5.11 can
    // discard it when lowering an inline allocation-size expression.
    val byteCount = eventSize * nevents.toCSize
    val events = stackalloc[Byte](byteCount)
    op(events)
  }

  /** posixlib does not expose EVFILT_TIMER. Its value is -7 on macOS and FreeBSD. */
  inline val EVFILT_TIMER = -7

  @alwaysinline
  def isTimerEvent(evt: Event): Boolean = filter(evt) == EVFILT_TIMER

  /** posixlib does not expose EVFILT_USER, a filter that only fires when triggered with `NOTE_TRIGGER`. */
  val EVFILT_USER: CShort = if LinktimeInfo.isFreeBSD then -11 else -10
  private val NOTE_TRIGGER: CUnsignedInt = 0x01000000.toUInt

  @alwaysinline
  def isUserEvent(evt: Event): Boolean = filter(evt) == EVFILT_USER

  /** Registers user event `id`, edge-triggered: each trigger is reported once. */
  def addUser(evt: Event, id: USize): Unit =
    kevent.scalanative_kevent_set(
      evt,
      0, // index within the event buffer
      id, // user event ID
      EVFILT_USER, // filter type
      (kevent.EV_ADD | kevent.EV_CLEAR).toUShort, // flags
      0.toUInt, // fflags
      0, // filter-specific data
      null // user data
    )

  /** Triggers user event `id`, registered with `addUser`, waking a poll on its kqueue. */
  def triggerUser(evt: Event, id: USize): Unit =
    kevent.scalanative_kevent_set(
      evt,
      0, // index within the event buffer
      id, // user event ID
      EVFILT_USER, // filter type
      0.toUShort, // flags
      NOTE_TRIGGER, // fflags
      0, // filter-specific data
      null // user data
    )

  /** Register a one-shot timer using kqueue's default millisecond units. */
  def addTimerOneShot(evt: Event, id: USize, milliseconds: Size): Unit = {
    if (LinktimeInfo.isMac || LinktimeInfo.isFreeBSD) {
      kevent.scalanative_kevent_set(
        evt,
        0, // index within the event buffer
        id, // timer ID
        EVFILT_TIMER, // filter type
        (kevent.EV_ADD | kevent.EV_ONESHOT).toUShort, // flags
        0.toUInt, // default units: milliseconds
        milliseconds, // timeout
        null // user data
      )
    } else {
      throw new UnsupportedOperationException("Kqueue timers require macOS or FreeBSD")
    }
  }

  def addFile(evt: Event, fd: Int, read: Boolean, clear: Boolean, oneShot: Boolean = false): Unit = {
    var flags = kevent.EV_ADD | kevent.EV_ENABLE
    if (clear) {
      flags |= kevent.EV_CLEAR
    }
    if (oneShot) {
      flags |= kevent.EV_ONESHOT
    }
    kevent.scalanative_kevent_set(
      evt,
      0, // index within the event buffer
      fd.toUSize, // file descriptor
      (if (read) kevent.EVFILT_READ else kevent.EVFILT_WRITE).toShort, // filter type
      flags.toUShort, // flags
      0.toUInt, // fflags
      0, // filter-specific data
      null // user data
    )
  }

  /** Removes a timer registered with `addTimerOneShot` before it fires. */
  def deleteTimer(evt: Event, id: USize): Unit =
    kevent.scalanative_kevent_set(
      evt,
      0, // index within the event buffer
      id, // timer ID
      EVFILT_TIMER, // filter type
      kevent.EV_DELETE.toUShort, // flags
      0.toUInt, // fflags
      0, // filter-specific data
      null // user data
    )

  def deleteFile(evt: Event, fd: Int, read: Boolean): Unit = {
    kevent.scalanative_kevent_set(
      evt,
      0, // index within the event buffer
      fd.toUSize, // file descriptor
      (if (read) kevent.EVFILT_READ else kevent.EVFILT_WRITE).toShort, // filter type
      kevent.EV_DELETE.toUShort, // flags
      0.toUInt, // fflags
      0, // filter-specific data
      null // user data
    )
  }

  /** Fills a stack-allocated buffer of `nEvents` changes with `f` and registers them. Inline, with an inline `f`, so no
    * closure is allocated.
    */
  inline def createAndRegisterEvents(
      kq: Int,
      nEvents: Int
  )(inline f: EventQueue => Unit): Unit = {
    pollQueue(nEvents) { events =>
      f(events)
      registerEvents(kq, events, nEvents)
    }
  }

  /** Deletes `fd`'s read or write filter, as far as it can: returns whether it is gone. A filter that is already gone
    * because `fd` was closed, reported as `ENOENT` or `EBADF`, counts as deleted, since kqueue removes a descriptor's
    * filters when it is closed. Any other failure is returned rather than thrown, since a filter left over only reports
    * an event the reactor drops.
    */
  def deleteFileIfOpen(kq: Int, fd: Int, read: Boolean): Boolean = {
    var deleted = true
    pollQueue(1) { events =>
      deleteFile(eventAt(events, 0), fd, read)
      if (Unblocked.kevent(kq, events, 1, null, 0, null) < 0) { // registers only, so it cannot block
        val code = posixErrno.errno
        deleted = code == posixErrno.ENOENT || code == posixErrno.EBADF
      }
    }
    deleted
  }

  def registerEvents(
      kq: Int,
      events: EventQueue,
      nEvents: Int
  ): Unit = {
    if (Unblocked.kevent(kq, events, nEvents, null, 0, null) < 0) { // registers only, so it cannot block
      throw new IOException(
        s"Failed to register events: ${cError()}"
      )
    }
  }

  /** An event buffer for `nevents` events, zeroed, in `zone`, for a loop that polls many times: it lives as long as the
    * zone.
    */
  def allocateQueue(nevents: Int)(using zone: Zone): EventQueue = {
    require(nevents > 0, "Event buffer capacity must be positive")
    alloc[Byte](eventSize * nevents.toCSize)
  }

  // A zero timeout for polls that must not wait, allocated once, in a zone kept for the process: setting a
  // stack-allocated one boxes its pointer.
  private val zeroTimeout: Ptr[timespec] = alloc[timespec]()(using Zone.open())

  /** Polls without waiting, so without telling the GC (see `Unblocked`). */
  def pollEventsNow(
      kq: Int,
      events: EventQueue,
      nEvents: Int
  ): Int = {
    val polledEvents = Unblocked.kevent(kq, null, 0, events, nEvents, zeroTimeout)
    polled(polledEvents)
  }

  def pollEventsForever(
      kq: Int,
      events: EventQueue,
      nEvents: Int
  ): Int = {
    val polledEvents = kevent.kevent(kq, null, 0, events, nEvents, null)
    polled(polledEvents)
  }

  /** Polls for up to the timeout, through posixlib's `@blocking` binding, since it may wait. */
  def pollEventsTimeout(
      kq: Int,
      events: EventQueue,
      nEvents: Int,
      seconds: Int,
      nanoseconds: Int
  ): Int = {
    val timeout = stackalloc[timespec]()
    timeout.tv_sec = seconds
    timeout.tv_nsec = nanoseconds
    val polledEvents = kevent.kevent(kq, null, 0, events, nEvents, timeout)
    polled(polledEvents)
  }

  /** The result of a poll: a signal interrupting it, `EINTR`, reports no events, so the loop turns and polls again; any
    * other failure, of the kqueue itself, throws.
    */
  @alwaysinline private def polled(polledEvents: Int): Int =
    if (polledEvents >= 0) polledEvents
    else if (posixErrno.errno == posixErrno.EINTR) 0
    else throw new IOException(s"Failed to poll events: ${cError()}")

  /** Opens a kqueue.
    */
  def open(): Int = {
    val kq = kevent.kqueue()
    if (kq < 0) {
      throw new IOException(
        s"Failed to create kqueue: ${cError()}"
      )
    }
    kq
  }

  def close(kq: Int): Unit = {
    if (unistd.close(kq) < 0) {
      throw new IOException(
        s"Failed to close kqueue: ${cError()}"
      )
    }
  }

  def scoped(f: FileOperation): Unit =
    Bracket.fileResource(open())(close)(f)
}
