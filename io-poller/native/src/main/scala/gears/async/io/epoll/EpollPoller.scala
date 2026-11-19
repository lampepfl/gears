package gears.async.io.epoll

import gears.async.io.epoll.internal.EpollLoop
import gears.async.io.eventpolling.EventPoller
import gears.async.io.shareable.ReadinessOp

import java.io.IOException
import scala.scalanative.unsafe.Zone

/** The event poller of Linux: an epoll instance, with one registration for each descriptor it watches, edge-triggered,
  * for whichever of reading and writing it is listened for.
  *
  * Where a kqueue has a filter for each direction, epoll has one registration for a descriptor, with the directions
  * as bits of it, added, changed or taken off as a direction comes or goes. An event says that a descriptor is ready
  * and not for how much, so a handle's waiter is told nothing of how much there is (`ReadinessOp.Unknown`), and
  * finds its handle drained only by trying.
  */
trait EpollPoller(maxEvents: Int) extends EventPoller {

  protected final def queueName: String = "epoll"

  private val ep = EpollLoop.open()

  /** What a wake-up makes readable: an eventfd, registered once, not edge-triggered, and drained as it is reported. */
  private val wakeFd =
    try
      val fd = EpollLoop.openEventFd()
      try
        EpollLoop.addEventFd(ep, fd)
        fd
      catch
        case failure: Throwable =>
          EpollLoop.close(fd)
          throw failure
    catch
      case failure: Throwable =>
        EpollLoop.close(ep)
        throw failure

  private val zone = Zone.open()
  private val events = EpollLoop.allocateQueue(maxEvents)(using zone)

  /** The registration added with the first direction, changed for another, which reports the descriptor anew if it is
    * ready, and taken off with the last. One already gone because its descriptor closed is no failure of changing it
    * or taking it off.
    */
  protected final def watch(fd: Int, before: Int, after: Int, certain: Boolean): Unit = {
    val read = (after & EventPoller.Read) != 0
    val write = (after & EventPoller.Write) != 0
    if before == 0 then EpollLoop.add(ep, fd, read, write)
    else if after == 0 then EpollLoop.deleteIfOpen(ep, fd)
    else if certain then EpollLoop.modify(ep, fd, read, write)
    else EpollLoop.modifyIfOpen(ep, fd, read, write)
  }

  protected final def report(index: Int): Unit = {
    val fd = EpollLoop.fd(events, index)
    // A wake-up has nothing to hand over: the worker looks at its inbox and timers after the poll.
    if fd == wakeFd then EpollLoop.drain(wakeFd)
    else
      if EpollLoop.isReadable(events, index) then dispatch(fd, read = true, ReadinessOp.Unknown)
      if EpollLoop.isWritable(events, index) then dispatch(fd, read = false, ReadinessOp.Unknown)
  }

  protected final def trigger(): Unit =
    if !EpollLoop.signal(wakeFd) then throw new IOException("Failed to signal an eventfd")

  protected final def lookNow(): Int = pollEpoll(0)

  protected final def waitFor(timeout: Long): Int = pollEpoll(timeout)

  /** One poll into `events`: without waiting if `timeout` is 0, for at most `timeout` nanoseconds if it is positive,
    * and until there is something if it is negative. The number of events.
    */
  protected def pollEpoll(timeout: Long): Int =
    if timeout == 0 then EpollLoop.pollNow(ep, events, maxEvents)
    else if timeout > 0 then EpollLoop.pollTimeout(ep, events, maxEvents, timeout)
    else EpollLoop.pollForever(ep, events, maxEvents)

  protected final def closeQueue(): Unit = {
    EpollLoop.close(wakeFd)
    EpollLoop.close(ep)
    zone.close()
  }
}
