package gears.async.io
package reference

import gears.async.Async
import gears.async.GearsCancellationException
import gears.async.cancellationScope

import java.nio.channels.SelectableChannel
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** One performance of an op: its wait, when it began, and its deadline, `Long.MaxValue` for none. */
final class Ctx(val waiter: Wait, val start: Long, val deadline: Long, val run: RefRun) {

  /** Makes `handle`, just opened by the op being performed, its run's: closed as the run ends if nothing closed it, and
    * refused by any other run.
    */
  def adopt[H <: RefHandle](handle: H): H = run.adopt(handle)

  def expired: Boolean = deadline != Long.MaxValue && System.nanoTime() - deadline >= 0

  /** Throws if the op has been cancelled, or its time limit has passed. */
  def checkStop(op: AnyRef)(using ac: Async): Unit = {
    if waiter.cancelled || ac.group.isCancelled then throw new GearsCancellationException()
    if expired then throw new TimeoutException(s"$op timed out")
  }

  /** Parks until signalled, cancelled, or the deadline. */
  def park(): Unit = waiter.park(deadline)

  /** Waits for `channel` to be ready for `ops`, by the poller, failing if `handle` is closed meanwhile, or the op is
    * cancelled or out of time; else returns, for the op to try again.
    */
  def ready(op: AnyRef, handle: RefHandle | Null, channel: SelectableChannel, ops: Int)(using Async): Unit = {
    waiter.reset()
    Poller.register(channel, ops, waiter)
    park()
    if handle != null then handle.checkOpen()
    checkStop(op)
  }
}

/** An op of the reference backend, performed by the caller (`perform`), which makes the checks every op makes, in this
  * order: not cancelled already; a one-shot op not performed before (else `IllegalStateException`), nor any op being
  * performed now; its slots empty and what `check` checks (else `IllegalArgumentException`); its handle open (else the
  * closed handle's failure); then the op itself (`body`), with its handle taken up while it runs, so that a close fails
  * it and waits for it.
  */
abstract class RefOp {
  private val used = new AtomicBoolean()
  private val busy = new AtomicBoolean()

  /** The run performing the op, while `busy`. */
  @volatile private var performer: RefRun | Null = null

  /** The slots the op writes, which must be empty as it is performed. */
  protected def slots: List[Slot[?]] = Nil

  /** The handle the op is on, if any. */
  protected def handle: RefHandle | Null = null

  /** The handle the op uses, which is to be its run's: the one it is on, unless it says another, as a close does. */
  protected def uses: RefHandle | Null = handle

  /** Fails if a slot the op writes holds a value, as it is performed. */
  private def checkSlots(): Unit =
    if slots.exists(!_.isEmpty) then
      throw new IllegalArgumentException(
        "The op's slot still holds a value, not yet cleared, which the op would overwrite"
      )

  /** Checks made as the op is performed, before anything is done: `IllegalArgumentException`s. */
  protected def check(): Unit = ()

  protected def body(ctx: Ctx)(using Async): Unit

  final def perform(timeoutMillis: Long, run: RefRun)(using ac: Async): Unit = {
    if ac.group.isCancelled then throw new GearsCancellationException()
    // An op that another run is performing is that run's meanwhile, and is left as it is.
    val other = performer
    if other != null && (other ne run) then throw new IllegalStateException(s"$this is being performed by another run")
    if !this.isInstanceOf[Repeatable] && !used.compareAndSet(false, true) then
      throw new IllegalStateException(s"$this is not Repeatable, and has been performed already")
    if !busy.compareAndSet(false, true) then
      if performer ne run then throw new IllegalStateException(s"$this is being performed by another run")
      else throw new IllegalArgumentException(s"$this is already being performed")
    performer = run
    val wait = new Wait
    try {
      val theirs = uses
      if theirs != null && (theirs.owner ne run) then
        throw new IllegalArgumentException(s"A handle of another run can't be used in this one: $theirs")
      checkSlots()
      check()
      val start = System.nanoTime()
      // No limit if negative, or beyond what nanoseconds hold, some 292 years. Else the sum may wrap, as
      // `System.nanoTime` itself may: deadlines are only ever compared by their difference, which does not.
      val unlimited = timeoutMillis < 0 || timeoutMillis > Long.MaxValue / 1_000_000L
      val limit = start + timeoutMillis * 1_000_000L
      // `Long.MaxValue` means none: a limit that lands on it is a nanosecond later, never sooner.
      val deadline = if unlimited then Long.MaxValue else if limit == Long.MaxValue then limit + 1 else limit
      val ctx = Ctx(wait, start, deadline, run)
      val h = handle
      cancellationScope(wait) {
        if h == null then body(ctx)
        else {
          h.enter(wait)
          try body(ctx)
          finally h.leave(wait)
        }
      }
    } finally {
      wait.retired = true
      performer = null
      busy.set(false)
    }
  }

  override def toString: String = getClass.getSimpleName.stripSuffix("Op")
}

/** A run of a program over the reference backend: the handles its ops opened, which are its own, closed as it ends if
  * nothing closed them, and refused by any other run.
  */
final class RefRun {
  private val opened = scala.collection.mutable.Set.empty[RefHandle]

  private[reference] def adopt[H <: RefHandle](handle: H): H = {
    handle.owner = this
    synchronized(opened += handle): Unit
    handle
  }

  private[reference] def forget(handle: RefHandle): Unit = synchronized(opened -= handle): Unit

  /** Closes what the run opened and left open, once its program and its futures have ended. */
  private[reference] def end(): Unit = {
    val left = synchronized(opened.toList)
    left.foreach(_.closeLeftOpen())
  }
}
