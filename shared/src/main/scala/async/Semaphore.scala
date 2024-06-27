package gears.async

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** A semaphore that manages a number of grants. One can wait to obtain a grant (with [[acquire]]) and return it to the
  * semaphore (with [[release]]).
  *
  * @param initialValue
  *   the initial counter of this semaphore
  */
class Semaphore(initialValue: Int) extends Async.Source[Semaphore.Guard]:
  self =>
  private val value = AtomicInteger(initialValue)
  private val waiting = ConcurrentLinkedQueue[Listener[Semaphore.Guard]]()

  override def onComplete(k: Listener[Semaphore.Guard]): Unit =
    if k.acquireLock() then // if k is gone, we are done
      if value.getAndDecrement() > 0 then
        // we got a ticket
        k.complete(guard, this)
      else
        // no ticket -> add to queue and reset value (was now negative - unless concurrently increased)
        k.releaseLock()
        waiting.add(k)
        guard.release()

  override def dropListener(k: Listener[Semaphore.Guard]): Unit = waiting.remove(k)

  override def poll(k: Listener[Semaphore.Guard]): Boolean =
    if !k.acquireLock() then return true
    val success = value.getAndUpdate(i => if i > 0 then i - 1 else i) > 0
    if success then k.complete(guard, self) else k.releaseLock()
    success

  override def poll(): Option[Semaphore.Guard] =
    if value.getAndUpdate(i => if i > 0 then i - 1 else i) > 0 then Some(guard) else None

  /** Decrease the number of grants available from this semaphore, possibly waiting if none is available.
    *
    * @param a
    *   the async capability used for waiting
    */
  inline def acquire()(using Async): Semaphore.Guard =
    this.awaitResult // do not short-circuit because cancellation should be considered first

  private object guard extends Semaphore.Guard:
    /** Increase the number of grants available to this semaphore, possibly waking up a waiting [[acquire]].
      */
    def release(): Unit =
      // if value is < 0, a ticket is missing anyway -> do nothing now
      if value.getAndUpdate(i => if i < 0 then i + 1 else i) >= 0 then
        // we kept the ticket for now

        var listener = waiting.poll()
        while listener != null && !listener.completeNow(guard, self) do listener = waiting.poll()
        // if listener not null, then we quit because listener was completed -> ticket is reused -> we are done

        // if listener is null, return the ticket by incrementing, then recheck waiting queue (if incremented to >0)
        if listener == null && value.getAndIncrement() >= 0 then
          listener = waiting.poll()
          if listener != null then // if null now, we are done
            onComplete(listener)

object Semaphore:
  /** A guard that marks a single usage of the [[Semaphore]]. Implements [[java.lang.AutoCloseable]] so it can be used
    * as a try-with-resource (e.g. with [[scala.util.Using]]).
    */
  trait Guard extends java.lang.AutoCloseable:
    /** Release the semaphore, must be called exactly once. */
    def release(): Unit

    final def close() = release()
