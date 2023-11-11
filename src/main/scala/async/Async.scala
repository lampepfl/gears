package gears.async
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicLong
import gears.async.Listener.NumberedSublock

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

  /** A source that transforms an original source in some way */
  abstract class DerivedSource[T, U](val original: Source[T]) extends Source[U]:

    /** Handle a lock request `x` received from the original source by possibly
     *  requesting the upstream listener for this source. The default behavior
     *  is to try to lock the upstream listener.
     */
    protected def tryLock(k: Listener[U]) = k.tryLock()

    /** Perform any additional release operation before the upstream listener
     *  has been released.
     */
    protected def beforeRelease(): Unit = ()

    /** Handle a complete request for a previous lock from the original source by
     *  passing on any operation (complete/release) to the upstream listener for
     *  this source.
     */
    protected def complete(k: Listener.ListenerLock[U], data: T): Unit

    private def transform(k: Listener[U]): Listener[T] =
      new Listener.ForwardingListener[T](this, k):
        def tryLock() = DerivedSource.this.tryLock(k)

        def release(until: Listener.ReleaseBoundary) =
          beforeRelease()
          k

        def complete(data: T) = DerivedSource.this.complete(k, data)

    def poll(k: Listener[U]): Boolean =
      original.poll(transform(k))
    def onComplete(k: Listener[U]): Unit =
      original.onComplete(transform(k))
    def dropListener(k: Listener[U]): Unit =
      original.dropListener(transform(k))

  end DerivedSource

  extension [T](src: Source[T])
    /** Pass on data transformed by `f` */
    def map[U](f: T => U): Source[U] =
      new DerivedSource[T, U](src):
        protected def complete(k: Listener.ListenerLock[U], data: T) =
          k.complete(f(data))

  /** Pass first result from any of `sources` to the continuation */
  def race[T](sources: Source[T]*): Source[T] =
    new Source[T] {

      def poll(k: Listener[T]): Boolean =
        val it = sources.iterator
        var found = false

        val listener = new Listener[T]:
          def tryLock() =
            val result = k.tryLock()
            // If tryLock is called, the source has - to the best of its
            // knowledge - an item available. But the upstream listener k
            // refuses to take any -> we assume there would have been one.
            if result == Listener.Gone then found = true
            result

          def release(until: Listener.ReleaseBoundary) = k

          def complete(data: T) =
            k.complete(data)
            found = true
        end listener

        while it.hasNext && !found do
          it.next.poll(listener)
        found

      def onComplete(k: Listener[T]): Unit =
        val listener = new Listener.ForwardingListener[T](this, k) with Listener.LockingListener { self =>
          var foundBefore = false

          val listenerPartial = new Listener.NumberedSublock {
            def number: Long = self.number
            def tryLock() =
              self.lock()
              val result =
                if foundBefore then
                  self.unlock()
                  Listener.Gone
                else
                  val result = k.tryLock()
                  if result == Listener.Gone then self.unlock()
                  result
              end result
              // as soon as we once return None, we are completed -> drop everywhere
              if result == Listener.Gone then sources.foreach(_.dropListener(self))
              result
          } // end listenerPartial: NumberedSublock

          def tryLock() =
            if foundBefore then Listener.Gone
            else listenerPartial

          def release(until: Listener.ReleaseBoundary) =
            if until != listenerPartial then
              self.unlock()
              k
            else null

          def complete(data: T) =
            k.completeNow(data)
            foundBefore = true
            self.unlock()
            sources.foreach(_.dropListener(self))
        } // end listener

        sources.foreach(_.onComplete(listener))

      def dropListener(k: Listener[T]): Unit =
        val listener = new Listener.ForwardingListener[T](this, k):
          def tryLock() = ???
          def complete(data: T) = ???
          def release(until: Listener.ReleaseBoundary) = ???
        // not to be called, we need the listener only for its
        // hashcode and equality test.
        sources.foreach(_.dropListener(listener))

    }
  end race

  /** If left (respectively, right) source succeeds with `x`, pass `Left(x)`,
   *  (respectively, Right(x)) on to the continuation.
   */
  def either[T1, T2](src1: Source[T1], src2: Source[T2]): Source[Either[T1, T2]] =
    race(src1.map(Left(_)), src2.map(Right(_)))

end Async
