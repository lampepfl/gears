package gears.async

import scala.annotation.tailrec
import gears.async.Async.Source
import java.util.concurrent.locks.ReentrantLock

/** A listener, representing an one-time value receiver of an [[Async.Source]].
  *
  * Most of the time listeners should involve only calling a receiver function, and can be created by [[Listener.apply]]
  * or [[Listener.acceptingListener]].
  *
  * However, should the listener want to attempt synchronization, it has to expose some locking-related interfaces. See
  * `lock`.
  */
trait Listener[-T]:
  import Listener._

  /** Complete the listener with the given item, from the given source. **If the listener exposes a
    * [[Listener.ListenerLock]]**, it is required to acquire this lock completely (either through [[lockCompletely]] or
    * through manual locking of every layer) before calling [[complete]]. This can also be done conveniently with
    * [[completeNow]]. For performance reasons, this condition is usually not checked and will end up causing unexpected
    * behavior if not satisfied.
    *
    * The listener must automatically release the lock of itself and any underlying listeners, however this usually is
    * done automatically by calling the inner listener's [[complete]] recursively.
    */
  def complete(data: T, source: Async.Source[T]): Unit

  /** Represents the exposed API for synchronization on listeners at receiving time. If the listener does not have any
    * form of synchronization, [[lock]] should be `null`.
    */
  val lock: Listener.ListenerLock | Null

  /** Attempts to acquire all locks and then calling [[complete]] with the given item and source. If locking fails,
    * [[releaseAll]] is automatically called.
    */
  def completeNow(data: T, source: Async.Source[T]): Boolean =
    lockCompletely(source) match
      case Locked =>
        this.complete(data, source)
        true
      case Gone => false

  /** Release the listener lock up to the given [[Listener.LockMarker]], if it exists. */
  inline final def releaseLock(to: Listener.LockMarker): Unit = if lock != null then lock.releaseAll(to)

  /** Attempts to completely lock the listener, if such a lock exists. Succeeds with [[Listener.Locked]] immediately if
    * there is no [[Listener.ListenerLock]]. If locking fails, [[releaseAll]] is automatically called.
    */
  inline final def lockCompletely(source: Async.Source[T]): Locked.type | Gone.type =
    if lock != null then lock.lockAll(source) else Locked

object Listener:
  /** A simple [[Listener]] that always accepts the item and sends it to the consumer. */
  inline def acceptingListener[T](inline consumer: (T, Source[T]) => Unit) =
    new Listener[T]:
      val lock = null
      def complete(data: T, source: Source[T]) = consumer(data, source)

  /** Returns a simple [[Listener]] that always accepts the item and sends it to the consumer. */
  inline def apply[T](consumer: (T, Source[T]) => Unit): Listener[T] = acceptingListener(consumer)

  /** A special class of listener that forwards the inner listener through the given source. For purposes of
    * [[Async.Source.dropListener]] these listeners are compared for equality by the hash of the source and the inner
    * listener.
    */
  abstract case class ForwardingListener[T](src: Async.Source[?], inner: Listener[T]) extends Listener[T]

  object ForwardingListener:
    /** Create an empty [[ForwardingListener]] for equality comparison. */
    def empty[T](src: Async.Source[?], inner: Listener[T]) = new ForwardingListener(src, inner):
      val lock = null
      override def complete(data: T, source: Async.Source[T]) = ???

  /** The result of locking a single listener lock. */
  sealed trait LockResult

  /** We have completed locking the listener. It can now be `complete`d. */
  case object Locked extends LockResult

  /** The listener is no longer available. It should be removed from the source, and any acquired locks by the source
    * must be manually `release`d by the source itself.
    */
  case object Gone extends LockResult

  /** Locking is successful; however, there are more locks to be acquired. */
  trait PartialLock extends LockResult:
    /** The number of the next lock. */
    val nextNumber: Long

    /** Attempt to lock the next lock. */
    def lockNext(): LockResult

  /** Points to a position on the lock chain, whose lock up until this point has been acquired, but no further.
    */
  type LockMarker = PartialLock | Locked.type

  /** A lock required by a listener to be acquired before accepting values. Should there be multiple listeners that
    * needs to be locked at the same time, they should be locked by larger-number-first.
    *
    * Some implementations are provided for ease of implementations:
    *   - For custom listener implementations involving locks: [[NumberedLock]] provides uniquely numbered locks.
    *   - For source transformation implementations: [[ListenerLockWrapper]] provides a ListenerLock instance that only
    *     forwards the requests to the underlying lock. [[withLock]] is a convenient `.map` for `[[ListenerLock]] |
    *     Null`.
    */
  trait ListenerLock:
    /** The assigned number of the lock. If the listener holds inner listeners underneath that utilizes locks, it is
      * **required** that [[selfNumber]] must be greater or equal any [[PartialLock.nextNumber]] of any returned
      * [[PartialLock]]s.
      */
    val selfNumber: Long

    /** Attempt to lock the current [[ListenerLock]]. To try to lock all possible nesting locks, see
      * [[Listener.lockCompletely]]. Locks are guaranteed to be held as short as possible.
      */
    def lockSelf(source: Async.Source[?]): LockResult

    /** Release the current lock without resolving the listener with any items, if the current listener lock is before
      * or the same as the current [[Listener.LockMarker]]. Returns the next lock to be released, `null` if there are no
      * more.
      */
    protected def release(to: Listener.LockMarker): ListenerLock | Null

    /** Attempt to release all locks up to and including the given [[Listener.LockMarker]]. */
    @tailrec
    final def releaseAll(to: Listener.LockMarker): Unit =
      val rest = release(to)
      if rest != null then rest.releaseAll(to)

    /** Attempt to lock all layers of this listener lock. */
    private[Listener] final def lockAll(source: Async.Source[?]): Locked.type | Gone.type =
      lockSelf(source) match
        case Locked             => Locked
        case Gone               => Gone
        case inner: PartialLock => lockRecursively(inner)

    @tailrec
    private final def lockRecursively(l: Listener.PartialLock): Locked.type | Gone.type =
      l.lockNext() match
        case Locked => Locked
        case Gone =>
          this.releaseAll(l)
          Gone
        case inner: PartialLock => lockRecursively(inner)
  end ListenerLock

  /** A special wrapper for [[ListenerLock]] that just passes the source through. */
  class ListenerLockWrapper(inner: ListenerLock, src: Async.Source[?]) extends ListenerLock:
    val selfNumber: Long = inner.selfNumber
    def lockSelf(_src: Async.Source[?]) =
      inner.lockSelf(src)
    def release(to: LockMarker): ListenerLock | Null = inner

  /** Maps the lock of a listener, if it exists. */
  inline def withLock[T](listener: Listener[?])(inline body: ListenerLock => T): T | Null =
    listener.lock match
      case null            => null
      case l: ListenerLock => body(l)

  /** A helper instance that provides an uniquely numbered mutex. */
  trait NumberedLock:
    import NumberedLock._

    val number = listenerNumber.getAndIncrement()
    private val lock0 = ReentrantLock()

    protected def acquireLock() = lock0.lock()
    protected def releaseLock() = lock0.unlock()
  object NumberedLock:
    private val listenerNumber = java.util.concurrent.atomic.AtomicLong()
