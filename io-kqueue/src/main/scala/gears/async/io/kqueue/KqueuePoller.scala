package gears.async.io.kqueue

import gears.async.io.kqueue.internal.KqueueLoop
import gears.async.io.shareable.Poller

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

trait KqueuePoller extends Poller {
  private val kq = KqueueLoop.open()

  private val WakeEvent = 0.toUSize
  KqueueLoop.createAndRegisterEvents(kq, 1) { events =>
    KqueueLoop.addUser(events(0), WakeEvent) // edge-triggered, registered once
  }

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
    Zone {
      val events = KqueueLoop.allocateQueue(1)
      val polled =
        if timeout == 0 then KqueueLoop.pollEventsNow(kq, events, 1)
        else if timeout > 0 then
          KqueueLoop.pollEventsTimeout(
            kq,
            events,
            1,
            (timeout / 1_000_000_000L).toInt,
            (timeout % 1_000_000_000L).toInt
          )
        else KqueueLoop.pollEventsForever(kq, events, 1)
      // println(s"Polled $polled events on kqueue $id with timeout $timeout")
      var i = 0
      while i < polled do
        val event = events(i)
        if KqueueLoop.isUserEvent(event) then
          // println(s"Wake event received on kqueue $id")
          // handle wake event
          ()
        else println(s"Unhandled event on kqueue $id: $event")
        i += 1
    }
  }

  override def close(): Unit = {
    closed = true
    KqueueLoop.close(kq)
  }
}
