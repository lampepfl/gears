package gears.async
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicLong
import gears.async.Listener.{wrapLock, ListenerLockWrapper}
import gears.async.Listener.NumberedLock

/** A context that allows to suspend waiting for asynchronous data sources
 */
trait Async(using val support: AsyncSupport, val scheduler: support.Scheduler):
  /** Wait for completion of async source `src` and return the result */
  def await[T](src: Async.Source[T]): T

  /** The cancellation group for this Async */
  def group: CompletionGroup

  /** An Async of the same kind as this one, with a new cancellation group */
  def withGroup(group: CompletionGroup): Async

object Async:

  private class Blocking(val group: CompletionGroup)(using support: AsyncSupport, scheduler: support.Scheduler) extends Async(using support, scheduler):
    private val lock = ReentrantLock()
    private val condVar = lock.newCondition()

    /** Wait for completion of async source `src` and return the result */
    override def await[T](src: Async.Source[T]): T =
      src.poll().getOrElse:
        var result: Option[T] = None
        src onComplete Listener.acceptingListener: t =>
          lock.lock()
          try
            result = Some(t)
            condVar.signalAll()
          finally lock.unlock()

        lock.lock()
        try
          while result.isEmpty do condVar.await()
          result.get
        finally lock.unlock()

    /** An Async of the same kind as this one, with a new cancellation group */
    override def withGroup(group: CompletionGroup): Async = Blocking(group)

  /** Execute asynchronous computation `body` on currently running thread.
   *  The thread will suspend when the computation waits.
   */
  def blocking[T](body: Async ?=> T)(using support: AsyncSupport, scheduler: support.Scheduler): T =
    group(body)(using Blocking(CompletionGroup.Unlinked))

  /** The currently executing Async context */
  inline def current(using async: Async): Async = async

  /** Await source result in currently executing Async context */
  inline def await[T](src: Source[T])(using async: Async): T = async.await(src)

  def group[T](body: Async ?=> T)(using async: Async): T =
    withNewCompletionGroup(CompletionGroup(async.group.handleCompletion).link())(body)

  def withCompletionHandler[T](handler: Cancellable => Async ?=> Unit)(body: Async ?=> T)(using async: Async): T =
    val combined = (c: Cancellable) => (async: Async) ?=>
      handler(c)
      async.group.handleCompletion(c)
    withNewCompletionGroup(CompletionGroup(combined).link())(body)

  /** Runs a body within another completion group. When the body returns, the
   *  group is cancelled and its completion awaited with the `Unlinked` group.
   */
  private[async] def withNewCompletionGroup[T](group: CompletionGroup)(body: Async ?=> T)(using async: Async): T =
    val completionAsync =
      if CompletionGroup.Unlinked == async.group
      then async
      else async.withGroup(CompletionGroup.Unlinked)

    try body(using async.withGroup(group))
    finally
      group.cancel()
      group.waitCompletion()(using completionAsync)

  /** An asynchronous data source. Sources can be persistent or ephemeral.
   *  A persistent source will always pass same data to calls of `poll and `onComplete`.
   *  An ephemeral source can pass new data in every call.
   *  An example of a persistent source is `Future`.
   *  An example of an ephemeral source is `Channel`.
   */
  trait Source[+T]:

    /** Check whether data is available at present and pass it to k if so.
     *  If no element is available, does not lock k and returns false immediately.
     *  If there is (or may be) data available, the listener is locked and if
     *  it fails, true is returned to signal this source's general availability.
     *  If locking k succeeds, only return true iff k's complete is called.
     *  Calls to `poll` are always synchronous.
     */
    def poll(k: Listener[T]): Boolean

    /** Once data is available, pass it to function `k`.
     *  `k` returns true iff the data was consumed in an async block.
     *  Calls to `onComplete` are usually asynchronous, meaning that
     *  the passed continuation `k` is a suspension.
     */
    def onComplete(k: Listener[T]): Unit

    /** Signal that listener `k` is dead (i.e. will always return `false` from now on).
     *  This permits original, (i.e. non-derived) sources like futures or channels
     *  to drop the listener from their waiting sets.
     */
    def dropListener(k: Listener[T]): Unit

    /** Utility method for direct polling. */
    def poll(): Option[T] =
      var resultOpt: Option[T] = None
      poll(Listener.acceptingListener { x => resultOpt = Some(x) })
      resultOpt

  end Source

  /** An original source has a standard definition of `onComplete` in terms
   *  of `poll` and `addListener`. Implementations should be the resource owner to
   *  handle listener queue and completion using an object monitor on the instance.
   */
  abstract class OriginalSource[+T] extends Source[T]:

    /** Add `k` to the listener set of this source */
    protected def addListener(k: Listener[T]): Unit

    def onComplete(k: Listener[T]): Unit = synchronized:
      if !poll(k) then addListener(k)

  end OriginalSource

  extension [T](src: Source[T])
    /** Pass on data transformed by `f` */
    def map[U](f: T => U) =
      new Source[U]:
        selfSrc =>
        def transform(k: Listener[U]) =
          new Listener[T]:
            val lock = wrapLock(k) { inner => new ListenerLockWrapper(inner, selfSrc ) }
            def complete(data: T, source: Async.Source[T]) =
              k.complete(f(data), selfSrc)
            def release(to: Listener.LockMarker) = k

        def poll(k: Listener[U]): Boolean =
          src.poll(transform(k))
        def onComplete(k: Listener[U]): Unit =
          src.onComplete(transform(k))
        def dropListener(k: Listener[U]): Unit =
          src.dropListener(transform(k))

  /** Pass first result from any of `sources` to the continuation */
  def race[T](sources: Source[T]*): Source[T] =
    new Source[T] { selfSrc =>

      def poll(k: Listener[T]): Boolean =
        val it = sources.iterator
        var found = false

        val listener = new Listener[T]:
          val lock = wrapLock(k) { inner => new ListenerLockWrapper(inner, selfSrc) }

          def complete(data: T, source: Async.Source[T]): Unit =
            found = true
            k.complete(data, selfSrc)
          def release(to: Listener.LockMarker) =
            /* If release is called with a non-null boundary,
               tryLock has been called but failed, so the source has -
               to the best of its knowledge - an item available.
               But the upstream listener k refuses to take any ->
                 we assume there would have been one.
            */
            if to != null then found = true
            k

        while it.hasNext && !found do
          it.next.poll(listener)
        found

      def onComplete(k: Listener[T]): Unit =
        val listener = new Listener.ForwardingListener[T](this, k) with NumberedLock { self =>
          var found = false
          val lock =
            var lockingSrc: Async.Source[?] = null // guaranteed to not be null when lock is held
            val heldLock = k.lock match
              case null => Listener.Locked
              case inner: Listener.ListenerLock =>
                new Listener.PartialLock:
                  val nextNumber = inner.selfNumber
                  def lockNext() =
                    val r = inner.lockSelf(selfSrc)
                    if r == Listener.Gone && !found then
                      found = true
                      cleanup(lockingSrc)
                    r

            new Listener.ListenerLock:
              val selfNumber = self.number
              def lockSelf(src: Async.Source[?]) =
                if found then return Listener.Gone
                self.acquireLock()
                lockingSrc = src
                if found then
                  self.releaseLock()
                  Listener.Gone
                else heldLock

          /** Remove all instances of this listener on other sources */
          def cleanup(src: Async.Source[?]) =
            sources.filter(_ != src).foreach(_.dropListener(self))

          def complete(item: T, src: Async.Source[T]) =
            found = true
            self.releaseLock()
            k.complete(item, selfSrc)
            cleanup(src)

          def release(until: Listener.LockMarker) =
            self.releaseLock()
            k
        } // end listener

        sources.foreach(_.onComplete(listener))

      def dropListener(k: Listener[T]): Unit =
        val listener = Listener.ForwardingListener.empty(this, k)
        sources.foreach(_.dropListener(listener))

    }
  end race

  /** If left (respectively, right) source succeeds with `x`, pass `Left(x)`,
   *  (respectively, Right(x)) on to the continuation.
   */
  def either[T1, T2](src1: Source[T1], src2: Source[T2]): Source[Either[T1, T2]] =
    race(src1.map(Left(_)), src2.map(Right(_)))

end Async
