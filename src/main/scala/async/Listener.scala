package gears.async

import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable

object Listener:
  trait ListenerLock:
    /** Release the lock without completing. May be due to a failure or the
     *  unability to actually provide the element proposed to filteredLock.
     */
    def release()(using LockContext): Unit

    /** Complete and release this lock successfully.
     * This tells the listener to handle the object obtained earlier.
     */
    def complete()(using LockContext): Unit

  trait LockContext:
    def lock[T](listener: Listener[T], data: T): Option[ListenerLock]
    def release(lock: ListenerLock): Unit
    def complete(lock: ListenerLock): Unit

  given singleLockContext: LockContext with
    def lock[T](listener: Listener[T], data: T): Option[ListenerLock] =
      listener.filteredLock(data)
    def complete(lock: ListenerLock): Unit = lock.complete()
    def release(lock: ListenerLock): Unit = lock.release()

  /** Create a listener that will always accept the element and pass it to the
   *  given consumer.
  */
  def acceptingListener[T](consumer: T => Unit) =
    new Listener[T]:
      override def filteredLock(data: T)(using LockContext) =
        Some(new ListenerLock:
          override def release()(using LockContext): Unit = ()
          override def complete()(using LockContext): Unit = consumer(data)
        )

  /** A listener for values that are processed by the given source `src` and
   *  that are demanded by the continuation listener `continue`.
   *  This class is necessary to identify listeners registered to upstream sources (for removal).
   */
  abstract case class ForwardingListener[T](src: Async.Source[?], continue: Listener[?]) extends Listener[T]

  private val listenerNumber = AtomicLong()
  /** A listener that receives a number atomically. Used for deadlock prevention.
   *  Note that numbered ForwardingListeners always have greater numbers than their
   *  wrapped `continue`-Listener.
   */
  trait NumberedListener:
    val number = listenerNumber.getAndIncrement()

/** An atomic listener that may manage an internal lock to guarantee atomic completion
 */
trait Listener[-T]:
  /** Try to lock the listener blockingly. It is already passed the data it
   *  may receive later and decides whether it wants to handle this element
   *  and is able to (by locking and checking whether it is not completed yet).
   *  If the element can be handled, return the acquired lock, None otherwise.
   *  Callers must ensure that the lock is released quickly.
   */
  def filteredLock(data: T)(using Listener.LockContext): Option[Listener.ListenerLock]

  /** Try to lock and complete directly. Returns true if the operation
   *  succeeds, false if the element is not handled.
   */
  def completeNow(data: T): Boolean =
      filteredLock(data).map(_.complete()).isDefined
