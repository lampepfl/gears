package gears.async.io.epoll.internal

import gears.async.io.posix.PosixErr.cError

import java.io.IOException
import scala.scalanative.linux.epoll
import scala.scalanative.linux.eventfd
import scala.scalanative.posix.errno as posixErrno
import scala.scalanative.posix.time.timespec
import scala.scalanative.posix.timeOps.*
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** epoll's calls that posixlib does not bind as this loop needs them. Its `epoll_wait` is `@blocking`, which tells the
  * GC that the thread is leaving (see `gears.async.io.posix.Unblocked`): a look that does not wait is bound here
  * without. And it has no wait to the nanosecond, which `epoll_pwait2` is (Linux 5.11, glibc 2.35).
  */
@extern
private[epoll] object EpollUnblocked {
  def epoll_wait(epfd: CInt, events: CVoidPtr, maxevents: CInt, timeoutMillis: CInt): CInt = extern

  def read(fildes: CInt, buf: CVoidPtr, nbyte: CSize): CSSize = extern

  def write(fildes: CInt, buf: CVoidPtr, nbyte: CSize): CSSize = extern

  @blocking
  def epoll_pwait2(epfd: CInt, events: CVoidPtr, maxevents: CInt, timeout: Ptr[timespec], sigmask: CVoidPtr): CInt =
    extern
}

/** An epoll instance and its events, over posixlib's `scala.scalanative.linux.epoll`, which owns the layout of an
  * event: buffers are opaque, and read through its accessors.
  */
object EpollLoop {

  opaque type EventQueue = Ptr[Byte]

  private val eventSize = epoll.scalanative_epoll_event_size()

  // What the calls are told and the events say, each read from the system's headers once.
  private val Add = epoll.EPOLL_CTL_ADD
  private val Modify = epoll.EPOLL_CTL_MOD
  private val Delete = epoll.EPOLL_CTL_DEL
  private val In = epoll.EPOLLIN
  private val Out = epoll.EPOLLOUT
  private val ReadHangUp = epoll.EPOLLRDHUP
  private val EdgeTriggered = epoll.EPOLLET

  /** What a read waits for: bytes, or the end of the stream, which a peer's hang-up or an error also is. */
  private val Readable = In | ReadHangUp | epoll.EPOLLHUP | epoll.EPOLLERR

  /** What a write waits for: room, or that there will never be any. */
  private val Writable = Out | epoll.EPOLLHUP | epoll.EPOLLERR

  def open(): Int = {
    val ep = epoll.epoll_create1(epoll.EPOLL_CLOEXEC)
    if ep < 0 then throw new IOException(s"Failed to open an epoll instance: ${cError()}")
    ep
  }

  /** A descriptor that `signal` makes readable, until `drain` takes what was signalled. */
  def openEventFd(): Int = {
    val fd = eventfd.eventfd(0.toUInt, eventfd.EFD_CLOEXEC | eventfd.EFD_NONBLOCK)
    if fd < 0 then throw new IOException(s"Failed to open an eventfd: ${cError()}")
    fd
  }

  def close(fd: Int): Unit = unistd.close(fd): Unit

  /** An event buffer for `nevents` events, in `zone`. */
  def allocateQueue(nevents: Int)(using zone: Zone): EventQueue = {
    require(nevents > 0, "Event buffer capacity must be positive")
    alloc[Byte](eventSize * nevents.toCSize)
  }

  private def control(ep: Int, op: Int, fd: Int, events: Int): Int = {
    val event = stackalloc[Byte](eventSize)
    epoll.scalanative_epoll_event_set(event, 0, events.toUInt, fd.toULong)
    epoll.epoll_ctl(ep, op, fd, event)
  }

  private def interest(read: Boolean, write: Boolean): Int =
    (if read then In | ReadHangUp else 0) | (if write then Out else 0) | EdgeTriggered

  /** Registers `fd`, edge-triggered, for reading, writing or both: reported at once if it is ready now. */
  def add(ep: Int, fd: Int, read: Boolean, write: Boolean): Unit =
    if control(ep, Add, fd, interest(read, write)) < 0 then
      throw new IOException(s"Failed to register descriptor $fd: ${cError()}")

  /** Changes what `fd` is registered for, which reports it anew if it is ready for that now. */
  def modify(ep: Int, fd: Int, read: Boolean, write: Boolean): Unit =
    if control(ep, Modify, fd, interest(read, write)) < 0 then
      throw new IOException(s"Failed to change descriptor $fd's registration: ${cError()}")

  /** As `modify`, as far as it can: a registration already gone, as its descriptor's close takes it, is no failure. */
  def modifyIfOpen(ep: Int, fd: Int, read: Boolean, write: Boolean): Unit =
    control(ep, Modify, fd, interest(read, write)): Unit

  /** Takes `fd`'s registration off, as far as it can. */
  def deleteIfOpen(ep: Int, fd: Int): Unit = control(ep, Delete, fd, 0): Unit

  /** Registers the eventfd `fd`, not edge-triggered: reported for as long as it has not been drained. */
  def addEventFd(ep: Int, fd: Int): Unit =
    if control(ep, Add, fd, In) < 0 then throw new IOException(s"Failed to register an eventfd: ${cError()}")

  /** Makes the eventfd readable: whether it is, a counter already at its most being readable already. */
  def signal(fd: Int): Boolean = {
    val one = stackalloc[CUnsignedLongLong]()
    !one = 1.toULong
    EpollUnblocked.write(fd, one, 8.toCSize) >= 0 || posixErrno.errno == posixErrno.EAGAIN
  }

  /** Takes what was signalled, so that the eventfd is readable again only once it is signalled again. */
  def drain(fd: Int): Unit = {
    val count = stackalloc[CUnsignedLongLong]()
    EpollUnblocked.read(fd, count, 8.toCSize): Unit
  }

  private def polled(count: Int): Int =
    if count >= 0 then count
    else if posixErrno.errno == posixErrno.EINTR then 0
    else throw new IOException(s"Failed to poll events: ${cError()}")

  /** Polls without waiting, so without telling the GC. */
  def pollNow(ep: Int, events: EventQueue, nEvents: Int): Int =
    polled(EpollUnblocked.epoll_wait(ep, events, nEvents, 0))

  /** Polls until there is an event. */
  def pollForever(ep: Int, events: EventQueue, nEvents: Int): Int =
    polled(epoll.epoll_wait(ep, events, nEvents, -1))

  /** Polls for up to `nanos` nanoseconds. */
  def pollTimeout(ep: Int, events: EventQueue, nEvents: Int, nanos: Long): Int = {
    val limit = stackalloc[timespec]()
    limit.tv_sec = (nanos / 1_000_000_000L).toInt
    limit.tv_nsec = (nanos % 1_000_000_000L).toInt
    polled(EpollUnblocked.epoll_pwait2(ep, events, nEvents, limit, null))
  }

  /** The descriptor the `index`th event of `events` is for. */
  def fd(events: EventQueue, index: Int): Int = {
    val data = stackalloc[CUnsignedLongLong]()
    epoll.scalanative_epoll_event_get(events, index, null, data)
    (!data).toInt
  }

  private def bits(events: EventQueue, index: Int): Int = {
    val value = stackalloc[CUnsignedInt]()
    epoll.scalanative_epoll_event_get(events, index, value, null)
    (!value).toInt
  }

  /** Whether the `index`th event says its descriptor is ready to be read, or will never be. */
  def isReadable(events: EventQueue, index: Int): Boolean = (bits(events, index) & Readable) != 0

  /** Whether the `index`th event says its descriptor is ready to be written, or will never be. */
  def isWritable(events: EventQueue, index: Int): Boolean = (bits(events, index) & Writable) != 0
}
