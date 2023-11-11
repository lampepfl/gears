package gears.async

import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import scala.util.Try
import scala.util.boundary, boundary.break
import java.util.concurrent.locks.ReentrantLock
import scala.annotation.tailrec

object Listener:
  trait Lockable:
    /** Try to lock the (sub)listener blockingly.
     *  A listener is considered permanently fulfilled as soon as it returned Gone once.
     *  Callers must ensure that the lock is released quickly.
     */
    def tryLock(): LockResult

  sealed trait LockResult
  /** Signals that a listener cannot be locked (already fulfilled) */
  case object Gone extends LockResult
  /** Signals that the listener is now successfully locked */
  case object Locked extends LockResult
  /** An encapsulation of a lock step that will lock an internal lock */
  trait NumberedSublock extends LockResult with Lockable:
    def number: Long

  type ReleaseBoundary = None.type | NumberedSublock

  trait ListenerLock[-T]:
    /** Release the lock without completing. May be due to a failure or the
     *  unability to actually provide an element.
     *  If a NumberedSublock is passed, then the callee shall assume that this
     *  sublock is not acquired and shall not release it (and anything it wraps).
     */
    def release(until: ReleaseBoundary): Unit

    /** Complete and release this lock successfully.
     */
    def complete(data: T): Unit

  class InvalidLockOrderException(message: String) extends Exception(message)

  /** Lock two Listeners preventing deadlocks. If locking of both succeeds, both locks
   *  are returned as a tuple wrapped in a right. If one (the first) listener fails
   *  (unable to lock), then this listener instance is returned in a Left.
   */
  def lockBoth[T, V](l1: Listener[T], l2: Listener[V])(using support: SuspendSupport): Either[Listener[?], (ListenerLock[T], ListenerLock[V])] =
    def unlock(listener: Listener[?], lockState: NumberedSublock | Locked.type) =
      listener.release(lockState match
        case sublock: NumberedSublock => sublock
        case Locked => None
      )

    var lock1 = l1.tryLock() match
      case Gone => return Left(l1)
      case value: (NumberedSublock | Locked.type) => value
    var lock2 = l2.tryLock() match
      case Gone =>
        unlock(l1, lock1)
        return Left(l2)
      case value: (NumberedSublock | Locked.type) => value

    var isFirst: Boolean = false
    var isSecond: Boolean = false
    boundary[Either[Listener[?], (ListenerLock[T], ListenerLock[V])]]:
      def advance[X](listener: Listener[X], lock: NumberedSublock | Locked.type, otherListener: Listener[?], other: NumberedSublock | Locked.type) =
        lock.asInstanceOf[NumberedSublock].tryLock() match
          case Gone =>
            unlock(otherListener, other)
            unlock(listener, lock)
            break(Left(listener))
          case value: (NumberedSublock | Locked.type) => value

      while {isFirst = lock1.isInstanceOf[NumberedSublock] ; isFirst} | {isSecond = lock2.isInstanceOf[NumberedSublock] ; isSecond} do
        if isFirst && isSecond then
          val number1 = lock1.asInstanceOf[NumberedSublock].number
          val number2 = lock2.asInstanceOf[NumberedSublock].number
          if number1 > number2 then lock1 = advance(l1, lock1, l2, lock2)
          else if number1 < number2 then lock2 = advance(l2, lock2, l1, lock1)
          else throw InvalidLockOrderException(s"same lock $number1 used twice")
        else if isFirst then lock1 = advance(l1, lock1, l2, lock2)
        else lock2 = advance(l2, lock2, l1, lock1)

      Right((l1, l2))

  /** Create a listener that will always accept the element and pass it to the
   *  given consumer.
  */
  def acceptingListener[T](consumer: T => Unit) =
    new Listener[T]:
      override def release(until: ReleaseBoundary): Unit = ()
      override def complete(data: T): Unit = consumer(data)
      override def tryLock() = Locked

  /** A listener for values that are processed by the given source `src` and
   *  that are demanded by the continuation listener `continue`.
   *  This class is necessary to identify listeners registered to upstream sources (for removal).
   */
  abstract case class ForwardingListener[T](src: Async.Source[?], continue: Listener[?]) extends Listener[T]

  private val listenerNumber = AtomicLong()
  /** A listener that wraps an internal lock and that receives a number atomically
   *  for deadlock prevention. Note that numbered ForwardingListeners always have
   *  greater numbers than their wrapped `continue`-Listener.
   */
  trait LockingListener:
    self: Listener[?] =>
    val number = listenerNumber.getAndIncrement()
    private val lock0 = ReentrantLock()

    /** Locks this listener's internal lock.
     */
    protected def lock() = lock0.lock()

    protected def unlock() = lock0.unlock()

/** An atomic listener that may manage an internal lock to guarantee atomic completion
 */
trait Listener[-T] extends Listener.Lockable with Listener.ListenerLock[T]:
  /** Try to lock the listener completely and blockingly.
   *  If the listener can handle an element, return the acquired lock, None otherwise.
   *  See tryLock() for details on the semantics of the option and the acquired lock.
   */
  def lockCompletely(): Option[Listener.ListenerLock[T]] =
    @tailrec
    def lock(l: Listener.Lockable): Option[Listener.ListenerLock[T]] =
      l.tryLock() match
        case Listener.Gone =>
          if l.isInstanceOf[Listener.NumberedSublock] then
            this.release(l.asInstanceOf[Listener.NumberedSublock])
          None
        case Listener.Locked => Some(this)
        case sublock: Listener.NumberedSublock => lock(sublock)
    lock(this)

  /** Try to lock and complete directly. Returns true if the operation
   *  succeeds, false if the element is not handled.
   */
  def completeNow(data: T): Boolean =
    lockCompletely().map(_.complete(data)).isDefined
