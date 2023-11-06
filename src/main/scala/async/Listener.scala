package gears.async

import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import scala.util.Try
import scala.util.boundary, boundary.break
import java.util.concurrent.locks.ReentrantLock
import scala.annotation.tailrec

object Listener:
  trait ListenerLock[-T]:
    self =>
    /** Release the lock without completing. May be due to a failure or the
     *  unability to actually provide an element.
     */
    def release(): Unit

    /** Complete and release this lock successfully.
     */
    def complete(data: T): Unit

  trait PartialListenerLock[-T]:
    def nextListener: LockingListener
    def release(): Unit
    def lock(): Option[Either[PartialListenerLock[T], ListenerLock[T]]]

  extension[T] (lock: Either[PartialListenerLock[T], ListenerLock[T]])
    def tapLock(afterRelease: => Unit, afterComplete: T => Unit): Either[PartialListenerLock[T], ListenerLock[T]] =
      mapLock[T](afterRelease, (baseLock, data) => {
        baseLock.complete(data)
        afterComplete(data)
      })

    def mapLock[U](afterRelease: => Unit, completeLock: (ListenerLock[T], U) => Unit): Either[PartialListenerLock[U], ListenerLock[U]] =
      lock match
        case Left(partial) => Left(new PartialListenerLock[U] {
            def nextListener: LockingListener = partial.nextListener
            def lock(): Option[Either[PartialListenerLock[U], ListenerLock[U]]] =
              partial.lock().map(_.mapLock(afterRelease, completeLock))
            def release(): Unit =
              partial.release()
              afterRelease
        })
        case Right(full) => Right(new ListenerLock[U] {
          def release(): Unit =
            full.release()
            afterRelease
          def complete(data: U): Unit = completeLock(full, data)
        })

  class InvalidLockOrderException(message: String) extends Exception(message)

  /** Lock two Listeners preventing deadlocks. If locking of both succeeds, both locks
   *  are returned as a tuple wrapped in a right. If one (the first) listener fails
   *  (unable to lock), then this listener instance is returned in a Left.
   */
  def lockBoth[T, V](l1: Listener[T], l2: Listener[V])(using support: SuspendSupport): Either[Listener[?], (ListenerLock[T], ListenerLock[V])] =
    var lock1 = l1.tryLock() match
      case None => return Left(l1)
      case Some(value) => value
    var lock2 = l2.tryLock() match
      case None =>
        lock1.fold(_.release(), _.release())
        return Left(l2)
      case Some(value) => value

    var isFirst: Boolean = false
    var isSecond: Boolean = false
    boundary[Either[Listener[?], (ListenerLock[T], ListenerLock[V])]]:
      inline def advance[X](inline listener: Listener[X], inline lock: Either[PartialListenerLock[X], ListenerLock[X]], inline other: Either[PartialListenerLock[?], ListenerLock[?]]) =
        lock.left.get.lock() match
          case None =>
            other.fold(_.release(), _.release())
            break(Left(listener))
          case Some(value) => value

      while {isFirst = lock1.isLeft ; isFirst} | {isSecond = lock2.isLeft ; isSecond} do
        if isFirst && isSecond then
          val number1 = lock1.left.get.nextListener.number
          val number2 = lock2.left.get.nextListener.number
          if number1 > number2 then lock1 = advance(l1, lock1, lock2)
          else if number1 < number2 then lock2 = advance(l2, lock2, lock1)
          else throw InvalidLockOrderException(s"same lock $number1 used twice")
        else if isFirst then lock1 = advance(l1, lock1, lock2)
        else lock2 = advance(l2, lock2, lock1)

      Right((lock1.right.get, lock2.right.get))

  /** Create a listener that will always accept the element and pass it to the
   *  given consumer.
  */
  def acceptingListener[T](consumer: T => Unit) =
    val lock = new ListenerLock[T]:
      override def release(): Unit = ()
      override def complete(data: T): Unit = consumer(data)
    new Listener[T]:
      override def tryLock() = Some(Right(lock))

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

    /** Locks this listener's internal lock *if the LockContext permits it*.
     *  May throw because of LockContext.
     */
    protected def lock() =
      lock0.lock()

    protected def unlock() =
      lock0.unlock()

/** An atomic listener that may manage an internal lock to guarantee atomic completion
 */
trait Listener[-T]:
  /** Try to lock the listener blockingly.
   *  If the listener can handle an element without locking, return a ListenerLock.
   *  If it cannot handle an element return None.
   *  If it needs to lock a native lock, return a PartialListenerLock before locking.
   *  A listener is considered permanently fulfilled as soon as it returned None once.
   *  Callers must ensure that the lock is released quickly.
   */
  def tryLock(): Option[Either[Listener.PartialListenerLock[T], Listener.ListenerLock[T]]]

  /** Try to lock the listener completely and blockingly.
   *  If the listener can handle an element, return the acquired lock, None otherwise.
   *  See tryLock() for details on the semantics of the option and the acquired lock.
   */
  def lockCompletely(): Option[Listener.ListenerLock[T]] =
    @tailrec
    def lock(l: Option[Either[Listener.PartialListenerLock[T], Listener.ListenerLock[T]]]): Option[Listener.ListenerLock[T]] =
      l match
        case None => None
        case Some(Left(partial)) => lock(partial.lock())
        case Some(Right(lock)) => Some(lock)
    lock(tryLock())

  /** Try to lock and complete directly. Returns true if the operation
   *  succeeds, false if the element is not handled.
   */
  def completeNow(data: T): Boolean =
    lockCompletely().map(_.complete(data)).isDefined
