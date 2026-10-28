package gears.async.io
package nio

import gears.async.Cancellable

import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.locks.LockSupport

/** NIO's waits on the calling thread, for `NioOnCaller`: a command waits on its handle's own selector, a timer parks
  * until its time, and a blocking op runs on the caller. Nothing is interrupted and nothing spins; a cancel wakes the
  * wait. They began as the way of waiting of `VThreadBackend`, a backend with no reactor since retired (see "Tried and
  * dropped" in docs/io/jvm.md).
  */
private[nio] object WaitsHere {

  /** For `select`'s action: the op tries again itself, so the selected keys need nothing done. */
  val Ignore: java.util.function.Consumer[SelectionKey] = _ => ()

  /** One daemon thread that interrupts a blocking op run on a caller when its time limit is up, since nothing on the
    * caller's own thread could (`NioOnCaller`).
    */
  lazy val timeouts: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { body =>
    val thread = new Thread(body, "gears-io-nio-timeouts")
    thread.setDaemon(true)
    thread
  }
}

/** A wait on the calling thread. A `Cancellable`, so that the caller's group can end it. */
private[nio] sealed trait WaitHere extends Cancellable {

  /** Parks the caller until the op is done, or its handle may be ready, or `cancel` has been called, or, unless
    * `timeoutNanos` is negative, that many nanoseconds have passed, and returns: true if the wait finished the op, as a
    * timer's does once its time has passed, so there is nothing to try; false if the caller should try the op again, or
    * its time ran out, which the caller keeps track of. Throws if the op's handle has been closed.
    */
  def park(timeoutNanos: Long): Boolean

  /** Ends the wait, from any thread: a caller parked in `park` returns, and the next `park` returns at once. */
  def cancel(): Unit
}

/** A wait on the calling thread for a handle to be ready for `ops`, on the handle's own selector for that direction
  * (see `Selectable.enterWait`), which the JDK's poller parks a virtual thread in. Cancelled with `Selector.wakeup`, so
  * the handle stays open.
  */
private[nio] final class SelectorWait(entry: Selectable, write: Boolean, ops: Int) extends WaitHere {
  @volatile private var cancelled = false
  @volatile private var waitingOn: Selector | Null = null

  /** Never finishes the op itself: the caller tries it once the selector reports the handle ready, or its time is up.
    */
  def park(timeoutNanos: Long): Boolean = {
    val selector = entry.enterWait(write)
    try {
      val key = entry.channel.keyFor(selector)
      if key != null && key.interestOps() != ops then key.interestOps(ops): Unit
      waitingOn = selector
      if !cancelled then
        {
          if timeoutNanos < 0 then selector.select(WaitsHere.Ignore)
          else if timeoutNanos == 0 then selector.selectNow(WaitsHere.Ignore)
          else
            selector.select(WaitsHere.Ignore, (timeoutNanos + 999_999L) / 1_000_000L) // rounded up: due when it returns
        }: Unit
    } catch {
      case _: ClosedSelectorException | _: CancelledKeyException => ()
    } finally {
      waitingOn = null
      entry.exitWait()
    }
    false
  }

  def cancel(): Unit = {
    cancelled = true
    val selector = waitingOn
    if selector != null then selector.wakeup(): Unit
  }
}

/** A timer waited for on the calling thread, parked until its time has passed: no reactor thread to fire it and resume
  * the caller, which the OS may keep from running while the cores are busy. Cancelled with an unpark.
  */
private[nio] final class TimerWait(timer: NioTimer) extends WaitHere with gears.async.io.internal.TimerWaiter {
  @volatile private var cancelled = false
  @volatile private var parked: Thread | Null = null

  /** Parks until the timer's key, its deadline or time limit, a cancel, or its deadline moving earlier (`moved`); true
    * if its deadline has come. The timer carries its own time limit, so `timeoutNanos` is not used.
    */
  def park(timeoutNanos: Long): Boolean = {
    parked = Thread.currentThread()
    timer.waiter = this
    try {
      var remaining = timer.key - System.nanoTime()
      while remaining > 0 && !cancelled do {
        timer.filed = timer.key
        LockSupport.parkNanos(this, remaining)
        remaining = timer.key - System.nanoTime()
      }
    } finally {
      timer.waiter = null
      parked = null
    }
    timer.due(System.nanoTime()) == gears.async.io.internal.TimerState.Complete
  }

  def moved(t: gears.async.io.internal.TimerState): Unit = {
    val thread = parked
    if thread != null then LockSupport.unpark(thread)
  }

  def cancel(): Unit = {
    cancelled = true
    val thread = parked
    if thread != null then LockSupport.unpark(thread)
  }
}

/** A blocking op run on the calling thread, under the blocker pool's rules (see `NioBlocking.here`): `run` runs the
  * body, writing its result into the op's slot, and returns once it has, with its failure thrown; if `cancel` came
  * first the body is skipped, if during it is interrupted and its result not written, and either way `run` then throws
  * `CancellationException`. A `Cancellable`, so that the caller's group can end it.
  */
private[nio] final class BlockingHere(task: NioBlocking) extends Cancellable {
  def run(): Unit = task.runHere()
  def cancel(): Unit = if task.cancelUnlessCommitted() then task.interrupt(): Unit // else it wrote its result
}

/** A wait on the calling thread for an op's turn on its handle (see `Turns`), which the op holding it passes on as it
  * lets go, waking the caller (`wake`).
  */
private[nio] final class TurnWait(turns: gears.async.io.internal.Turns, op: AnyRef) extends WaitHere {
  @volatile private var cancelled = false
  private val caller = Thread.currentThread()

  /** Parks until `op` holds the turn, a cancel, or `timeoutNanos` have passed; true if it holds it. */
  def park(timeoutNanos: Long): Boolean = {
    val deadline = if timeoutNanos < 0 then -1L else System.nanoTime() + timeoutNanos
    var left = timeoutNanos
    while !turns.holds(op) && !cancelled && left != 0 do {
      if deadline < 0 then LockSupport.park(this) else LockSupport.parkNanos(this, left)
      if deadline >= 0 then left = math.max(deadline - System.nanoTime(), 0L)
    }
    turns.holds(op)
  }

  /** The turn passed to `op`: the caller goes on. */
  def wake(): Unit = LockSupport.unpark(caller)

  def cancel(): Unit = {
    cancelled = true
    LockSupport.unpark(caller)
  }
}
