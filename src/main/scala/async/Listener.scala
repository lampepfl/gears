package gears.async

import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import scala.util.Try
import scala.util.boundary, boundary.break
import java.util.concurrent.Semaphore

object Listener:
  trait ListenerLock[-T]:
    /** Release the lock without completing. May be due to a failure or the
     *  unability to actually provide an element.
     */
    def release(): Unit

    /** Complete and release this lock successfully.
     */
    def complete(data: T): Unit

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
    def beforeLock(listener: LockingListener): Unit

  class InvalidLockOrderException(message: String) extends Exception(message)
  private class LockCancelException extends Exception

  private class MultiLockContext[-T](val support: SuspendSupport)(using support.Label[LockState[T]]) extends LockContext:
    var greatestLock: Long = -1

    def beforeLock(listener: LockingListener): Unit =
      if greatestLock >= listener.number then
        throw new InvalidLockOrderException("this listener tree already holds a greater lock")
      support.suspend[Option[Exception], LockState[T]](
        LockState.Lock[T](listener, _)
      ).foreach(throw _)

  private trait LockState[-T]:
    def priority: Int

  private object LockState:
    class Lock[-T](
      val listener: LockingListener,
      val suspension: Suspension[Option[Exception], LockState[T]]
    ) extends LockState:
      def priority: Int = 20

    object Lock:
      def unapply[T](lock: Lock[T]) =
        (lock.listener, lock.suspension)

    class Finish[-T](
      val lock: ListenerLock[T]
    ) extends LockState:
      def priority: Int = 10

    object Finish:
      def unapply[T](finish: Finish[T]) =
        Some(finish.lock)

    class Cancel[-T](val exception: Option[Throwable]) extends LockState[T]:
      def priority: Int = 30

    object Cancel:
      def unapply(cancel: Cancel[?]) =
        Some(cancel.exception)

    def priorityOrdering =
      Ordering.by[LockState[?], Int](_.priority).orElse(new Ordering[LockState[?]]:
        def compare(x: LockState[?], y: LockState[?]): Int = (x, y) match
          case (Finish(_), Finish(_)) => 0

          case (Lock(l1, _), Lock(l2, _)) =>
            val value = (l2.number - l1.number).intValue // L(n1) > L(n2) iff n1 < n2
            if value == 0 then
              throw new InvalidLockOrderException("same locking listener used multiple times")
            value

          case (Cancel(ex1), Cancel(ex2)) =>
            def bVal(b: Boolean) = if b then 1 else 0
            // C(ex1) > C(ex2) if only ex1 present
            bVal(ex1.isDefined) - bVal(ex2.isDefined)
      )

  /** Lock two Listeners preventing deadlocks. If locking of both succeeds, both locks
   *  are returned as a tuple wrapped in a right. If one (the first) listener fails
   *  (unable to lock), then this listener instance is returned in a Left.
   */
  def lockBoth[T, V](l1: Listener[T], l2: Listener[V])(using support: SuspendSupport): Either[Listener[?], (ListenerLock[T], ListenerLock[V])] =
    def startLock[X](listener: Listener[X]) =
      support.boundary[LockState[X]]:
        val ctx = MultiLockContext[X](support)
        Try[LockState[X]]:
          listener.tryLock()(using ctx)
            .fold(LockState.Cancel(None))(x => LockState.Finish(x))
        .fold(ex => LockState.Cancel(Some(ex)), identity)

    var state1: LockState[?] = startLock(l1)
    if (state1.isInstanceOf[LockState.Cancel[?]]) then
      state1.asInstanceOf[LockState.Cancel[?]].exception.foreach(throw _)
      return Left(l1)
    var state2: LockState[?] = startLock(l2)
    var permuted = false

    while true do
      if LockState.priorityOrdering.compare(state1, state2) < 0 then
        val temp = state1
        state1 = state2
        state2 = temp
        permuted = !permuted
      (state1, state2) match
        case (LockState.Cancel(ex1), LockState.Lock(_, suspension2)) =>
          val result2 = suspension2.resume(Some(LockCancelException()))
          ex1.foreach(throw _)
          result2 match
            case LockState.Cancel(ex2) => ex2.foreach(throw _)
            case _ => ()
          return Left(if permuted then l2 else l1)
        case (LockState.Cancel(ex1), LockState.Finish(lock2)) =>
          lock2.release()
          ex1.foreach(throw _)
          return Left(if permuted then l2 else l1)
        case (LockState.Lock(_, suspension), _) =>
          state1 = suspension.resume(None)
        case (LockState.Finish(lock1), LockState.Finish(lock2)) =>
          return (
            if permuted then Right(lock2, lock1)
            else Right(lock1, lock2)
          ).asInstanceOf[Right[Listener[?], (ListenerLock[T], ListenerLock[V])]]
    ??? // never reached

  given singleLockContext: LockContext with
    def beforeLock(listener: LockingListener): Unit = ()

  /** Create a listener that will always accept the element and pass it to the
   *  given consumer.
  */
  def acceptingListener[T](consumer: T => Unit) =
    val lock = new ListenerLock[T]:
      override def release(): Unit = ()
      override def complete(data: T): Unit = consumer(data)
    new Listener[T]:
      override def tryLock()(using LockContext) = Some(lock)

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
    val number = listenerNumber.getAndIncrement()
    private val lock0 = Semaphore(1)

    /** Locks this listener's internal lock *if the LockContext permits it*.
     *  May throw because of LockContext.
     */
    protected def lock()(using ctx: LockContext) =
      ctx.beforeLock(this)
      lock0.acquire()

    protected def unlock() =
      lock0.release()

/** An atomic listener that may manage an internal lock to guarantee atomic completion
 */
trait Listener[-T]:
  /** Try to lock the listener blockingly.
   *  If the listener can handle an element, return the acquired lock, None otherwise.
   *  A listener is considered permanently fulfilled as soon as it returned None once.
   *  Callers must ensure that the lock is released quickly.
   */
  def tryLock()(using Listener.LockContext): Option[Listener.ListenerLock[T]]

  /** Try to lock and complete directly. Returns true if the operation
   *  succeeds, false if the element is not handled.
   */
  def completeNow(data: T): Boolean =
      tryLock().map(_.complete(data)).isDefined
