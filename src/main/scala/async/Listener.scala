package gears.async

import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import scala.util.Try
import scala.util.boundary, boundary.break

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
    /* Ask for locking confirmation before acquiring the listener's internal lock.
     * This may wait to lock other locks first. It may also fail (throw)
     *  - because there is an error
     *    (e.g., same listener twice or smaller number already locked)
     *  - for an external reason
     *    (e.g., another top-level LockRequest returned None)
     *
     * In case of a failure, the held locks (and sublocks) should be released
     * and the exception should bubble up.
     *
     * No further calls may be done after this method has thrown.
     */
    def beforeLock(listener: NumberedListener): Unit

    /** Inform the context that the listener's internal lock is released. */
    def afterUnlock(listener: NumberedListener): Unit

  trait LockRequest:
    def lock()(using LockContext): Option[ListenerLock]
  object LockRequest:
    /** Create a lock request for a listener with the given data.
     *  It removes the types from the tuple and allows for untyped locking later.
     */
    def apply[T](listener: Listener[T], data: T) =
      new LockRequest:
        def lock()(using LockContext): Option[ListenerLock] =
          listener.filteredLock(data)

  class InvalidLockOrderException(message: String) extends Exception(message)
  private class LockCancelException extends Exception

  private class MultiLockContext[S <: SuspendSupport](val support: S)(using support.Label[LockState[S]]) extends LockContext:
    var greatestLock: Long = -1

    def beforeLock(listener: NumberedListener): Unit =
      if greatestLock >= listener.number then
        throw new InvalidLockOrderException("this listener tree already holds a greater lock")
      support.suspend[Option[Exception], LockState[S]](
        LockState.Lock[S](listener, _)
      ).foreach(throw _)

    def afterUnlock(listener: NumberedListener): Unit = ()

  private trait LockState[S <: SuspendSupport]:
    def priority: Int

  private object LockState:
    class Lock[S <: SuspendSupport](
      val listener: NumberedListener,
      val suspension: Suspension[Option[Exception], LockState[S]]
    ) extends LockState[S]:
      def priority: Int = 20

    object Lock:
      def unapply[S <: SuspendSupport](lock: Lock[S]) =
        (lock.listener, lock.suspension)

    class Finish[S <: SuspendSupport](
      val idx: Int,
      val lock: ListenerLock
    ) extends LockState[S]:
      def priority: Int = 10

    object Finish:
      def unapply[S <: SuspendSupport](finish: Finish[S]) =
        (finish.idx, finish.lock)

    class Cancel[S <: SuspendSupport](val exception: Option[Throwable]) extends LockState[S]:
      def priority: Int = 30

    object Cancel:
      def unapply[S <: SuspendSupport](cancel: Cancel[S]) =
        Some(cancel.exception)

    def priorityOrdering[S <: SuspendSupport] =
      Ordering.by[LockState[S], Int](_.priority).orElse(new Ordering[LockState[S]]:
        def compare(x: LockState[S], y: LockState[S]): Int = (x, y) match
          case (Finish(idx1, _), Finish(idx2, _)) =>
            idx2 - idx1 // F(idx1) > F(idx2) iff idx1 < idx2

          case (Lock(l1, _), Lock(l2, _)) =>
            val value = (l2.number - l1.number).intValue // L(n1) > L(n2) iff n1 < n2
            if value == 0 then
              throw new InvalidLockOrderException("same locking listener used multiple times")
            value

          case (Cancel[S](ex1), Cancel[S](ex2)) =>
            def bVal(b: Boolean) = if b then 1 else 0
            // C(ex1) > C(ex2) if only ex1 present
            bVal(ex1.isDefined) - bVal(ex2.isDefined)
      )

  /** Lock multiple Listeners (passed as LockRequests) preventing deadlocks */
  def lockMulti(locks: LockRequest*)(using support: SuspendSupport): Option[Seq[ListenerLock]] =
    if locks.isEmpty then return Some(Seq.empty)

    val queue = mutable.PriorityQueue.from(
      locks.zipWithIndex.map: (lock, idx) =>
        support.boundary[LockState[support.type]]: label ?=>
          val ctx = MultiLockContext(support)(using label)
          Try[LockState[support.type]]:
            lock.lock()(using ctx)
              .fold(LockState.Cancel(None))(x => LockState.Finish(idx, x))
          .fold(ex => LockState.Cancel(Some(ex)), identity)
    )(using LockState.priorityOrdering)

    while !queue.head.isInstanceOf[LockState.Finish[_]] do
      queue.dequeue() match
        case LockState.Lock(_, sus) =>
          queue.enqueue(sus.resume(None))
        case LockState.Cancel(exOpt) =>
          def noCancel = (ex: Throwable) => !ex.isInstanceOf[LockCancelException]
          var finalExOpt = exOpt.filter(noCancel)
          queue.foreach:
            case LockState.Lock(_, sus) => sus.resume(Some(LockCancelException()))
            case LockState.Finish(_, lock) => lock.release()(using singleLockContext)
            case LockState.Cancel(nextEx) =>
              finalExOpt = finalExOpt.orElse(nextEx.filter(noCancel))
          finalExOpt.foreach(throw _)
          return None

    Some(queue.dequeueAll.map(_.asInstanceOf[LockState.Finish[?]].lock))

  given singleLockContext: LockContext with
    def beforeLock(listener: NumberedListener): Unit = ()
    def afterUnlock(listener: NumberedListener): Unit = ()

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
