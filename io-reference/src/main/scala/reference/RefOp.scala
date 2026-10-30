package gears.async.io
package reference

import gears.async.Async
import gears.async.cancellationScope

import java.nio.channels.SelectableChannel
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** One performance of an op: its wait, when it began, and its deadline, `Long.MaxValue` for none. */
final class Ctx(val waiter: Wait, val start: Long, val deadline: Long) {

  def expired: Boolean = deadline != Long.MaxValue && System.nanoTime() - deadline >= 0

  /** Throws if the op has been cancelled, or its time limit has passed. */
  def checkStop(op: AnyRef)(using ac: Async): Unit = {
    if waiter.cancelled || ac.group.isCancelled then throw new CancellationException()
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

  /** The slots the op writes, which must be empty as it is performed. */
  protected def slots: List[Slot[?]] = Nil

  /** The handle the op is on, if any. */
  protected def handle: RefHandle | Null = null

  /** Checks made as the op is performed, before anything is done: `IllegalArgumentException`s. */
  protected def check(): Unit = ()

  protected def body(ctx: Ctx)(using Async): Unit

  final def perform(timeoutMillis: Int)(using ac: Async): Unit = {
    if ac.group.isCancelled then throw new CancellationException()
    if !this.isInstanceOf[Repeatable] && !used.compareAndSet(false, true) then
      throw new IllegalStateException(s"$this is not Repeatable, and has been performed already")
    if !busy.compareAndSet(false, true) then throw new IllegalArgumentException(s"$this is already being performed")
    val wait = new Wait
    try {
      if slots.exists(!_.isEmpty) then
        throw new IllegalArgumentException(
          "The op's slot still holds a value, not yet cleared, which the op would overwrite"
        )
      check()
      val start = System.nanoTime()
      val deadline = if timeoutMillis < 0 then Long.MaxValue else start + timeoutMillis * 1_000_000L
      val ctx = Ctx(wait, start, deadline)
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
      busy.set(false)
    }
  }

  override def toString: String = getClass.getSimpleName.stripSuffix("Op")
}
