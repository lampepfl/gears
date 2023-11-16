package gears.async

import scala.annotation.tailrec
import gears.async.Async.Source
import java.util.concurrent.locks.ReentrantLock

object Listener:
  /** The result of locking a single listener lock. */
  sealed trait LockResult

  /** We have completed locking the listener. It can now be `completed`. */
  object Locked extends LockResult

  /** The listener is no longer available.
    * It should be removed from the source, and any acquired locks should be `released`.
    */
  object Gone extends LockResult

  /** Locking is successful; however, there are more locks to be acquired. */
  trait SemiLock extends LockResult:
    /** The number of the next lock. */
    val nextNumber: Long
    /** Attempt to lock the next lock. */
    def lockNext(): LockResult

  /** Points to a position on the lock chain, whose lock up until this point has been acquired,
    * but no further.
    */
  type LockMarker = SemiLock | Locked.type

  /** A lock required by a listener to be acquired before accepting values.
    * Should there be multiple listeners that needs to be locked at the same time,
    * they should be locked by larger-number-first.

    * Some implementations are provided for ease of implementations:
    * - For custom listener implementations involving locks: `NumberedLock` provides uniquely
    * numbered locks.
    * - For source transformation implementations: `TopLockWrapper` provides a TopLock instance
    *   that only forwards the requests to the underlying topLock. `wrapLock` is a convenient
    *   `.map` for `TopLock | Null`.
    */
  trait TopLock:
    /** The assigned number of the lock.
      * If the listener holds inner listeners underneath that utilizes locks,
      * it is @required that `selfNumber` must be larger than any `nextNumber` of
      * any returned `SemiLock`s.
      */
    val selfNumber: Long
    /** Attempt to lock the current `TopLock`. To try to lock all possible nesting locks,
      * see `Listener.lockCompletely`.
      * Locks are guaranteed to be held as short as possible.
      */
    def lockSelf(source: Async.Source[?]): LockResult

  /** A special TopLockWrapper that just passes the source through. */
  class TopLockWrapper(inner: TopLock, src: Async.Source[?]) extends TopLock:
    val selfNumber: Long = inner.selfNumber
    def lockSelf(_src: Async.Source[?]) =
      inner.lockSelf(src)

  inline def wrapLock[T](listener: Listener[?])(inline body: TopLock => T): T | Null =
    listener.topLock match
      case null => null
      case l: TopLock => body(l)

  /** A simple listener that always accepts the item and sends it to the consumer. */
  def acceptingListener[T](consumer: T => Unit) =
    new Listener[T]:
      val topLock = null
      def complete(data: T, source: Source[T]) = consumer(data)
      def release(to: LockMarker) = null

  /** A simple listener that always accepts the item and sends it to the consumer. */
  def apply[T](consumer: T => Unit): Listener[T] = acceptingListener(consumer)

  abstract case class ForwardingListener[T](src: Async.Source[?], inner: Listener[T]) extends Listener[T]

  /** A helper instance that provides an uniquely numbered mutex. */
  trait NumberedLock:
    import NumberedLock._

    val number = listenerNumber.getAndIncrement()
    private val lock0 = ReentrantLock()

    protected def lock() = lock0.lock()
    protected def unlock() = lock0.unlock()
  object NumberedLock:
    private val listenerNumber = java.util.concurrent.atomic.AtomicLong()

/** A listener, representing an one-time value receiver of an `Async.Source`.
  *
  * Most of the time listeners should involve only calling a receiver function,
  * and can be created by `Listener.apply` or `Listener.acceptingListener`.
  *
  * However, should the listener want to attempt synchronization, it has to
  * expose some locking-related interfaces. See `topLock` and `release`.
  */
trait Listener[-T]:
  import Listener._
  /** Represents the exposed API for synchronization on listeners at receiving time.
    * If the listener does not have any form of synchronization, `topLock` should be `null`.
    */
  val topLock: Listener.TopLock | Null

  /** Complete the listener with the given item, from the given source.
    * *If the listener exposes a `TopLock`*, it is required to acquire this lock completely
    * (either through `lockCompletely` or through manual locking on every layer)
    * before calling `complete`.
    * For performance reasons, this condition is usually not checked and will end up
    * causing unexpected behavior.
    *
    * The listener should automatically release the lock of itself and any underlying listeners,
    * however this usually is done automatically by calling the inner listener's `complete`
    * recursively.
    */
  def complete(data: T, source: Async.Source[T]): Unit
  /** Release the current lock without resolving the listener with any items, if the
    * current listener is past the current LockMarker.
    */
  protected def release(to: Listener.LockMarker): Listener[?] | Null

  @tailrec
  final def releaseAll(until: Listener.LockMarker): Unit =
    val rest = release(until)
    if rest != null then rest.releaseAll(until)

  @tailrec
  private def lock(l: Listener.SemiLock): Locked.type | Gone.type =
    l.lockNext() match
      case Locked => Locked
      case Gone =>
        this.releaseAll(l)
        Gone
      case inner: SemiLock => lock(inner)

  def lockCompletely(source: Async.Source[T]): Locked.type | Gone.type =
    this.topLock match
      case topLock: Listener.TopLock =>
        topLock.lockSelf(source) match
          case Locked => Locked
          case Gone => Gone
          case inner: SemiLock => lock(inner)
      case null => Locked

  def completeNow(data: T, source: Async.Source[T]): Boolean =
    lockCompletely(source) match
      case Locked =>
        this.complete(data, source)
        true
      case Gone => false
