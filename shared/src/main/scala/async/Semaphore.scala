package gears.async

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** A semaphore that manages a number of grants. One can wait to obtain a grant (with [[acquire]]) and return it to the
  * semaphore (with [[release]]).
  *
  * @param value
  *   the grant counter
  */
class Semaphore private (private var value: AtomicInteger) extends Async.Source[Unit]:
  private val waiting = ConcurrentLinkedQueue[Listener[Unit]]()

  /** Create a semaphore with the given initial number of grants
    *
    * @param initialValue
    *   the initial counter of this semaphore
    */
  def this(initialValue: Int) = this(AtomicInteger(initialValue))

  override def onComplete(k: Listener[Unit]): Unit =
    if k.acquireLock() then // if k is gone, we are done
      if value.getAndDecrement() > 0 then
        // we got a ticket
        k.complete((), this)
      else
        // no ticket -> add to queue and reset value (was now negative - unless concurrently increased)
        k.releaseLock()
        waiting.add(k)
        release()

  override def dropListener(k: Listener[Unit]): Unit = waiting.remove(k)

  override def poll(k: Listener[Unit]): Boolean =
    if !k.acquireLock() then return true
    val success = value.getAndUpdate(i => if i > 0 then i - 1 else i) > 0
    if success then k.complete((), this) else k.releaseLock()
    success

  override def poll(): Option[Unit] =
    if value.getAndUpdate(i => if i > 0 then i - 1 else i) > 0 then Some(()) else None

  /** Decrease the number of grants available from this semaphore, possibly waiting if none is available.
    *
    * @param a
    *   the async capability used for waiting
    */
  inline def acquire()(using a: Async): Unit =
    a.await(this) // do not short-circuit because cancellation should be considered first

  /** Increase the number of grants available to this semaphore, possibly waking up a waiting [[acquire]].
    */
  def release(): Unit =
    // if value is < 0, a ticket is missing anyway -> do nothing now
    if value.getAndUpdate(i => if i < 0 then i + 1 else i) >= 0 then
      // we kept the ticket for now

      var listener = waiting.poll()
      while listener != null && !listener.completeNow((), this) do listener = waiting.poll()
      // if listener not null, then we quit because listener was completed -> ticket is reused -> we are done

      // if listener is null, return the ticket by incrementing, then recheck waiting queue (if incremented to >0)
      if listener == null && value.getAndIncrement() >= 0 then
        listener = waiting.poll()
        if listener != null then // if null now, we are done
          onComplete(listener)
