package gears.async
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicLong
import gears.async.Listener.ListenerLock
import gears.async.Listener.LockContext

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
   *  of `poll` and `addListener`.
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
    protected def tryLock(k: Listener[U])(using Listener.LockContext): Option[Listener.ListenerLock[U]] = k.tryLock()

    /** Handle a release request for a previous lock from the original source by
     *  passing on any operation (complete/release) to the upstream listener for
     *  this source. The default behavior is to forward the release operation.
     */
    protected def release(k: Listener.ListenerLock[U]): Unit =
      k.release()

    /** Handle a complete request for a previous lock from the original source by
     *  passing on any operation (complete/release) to the upstream listener for
     *  this source.
     */
    protected def complete(k: Listener.ListenerLock[U], data: T): Unit

    private def transform(k: Listener[U]): Listener[T] =
      new Listener.ForwardingListener[T](this, k){
        def tryLock()(using LockContext): Option[ListenerLock[T]] =
          DerivedSource.this.tryLock(k).map { lock =>
            new ListenerLock[T]:
              def release(): Unit = DerivedSource.this.release(lock)

              def complete(data: T): Unit =
                DerivedSource.this.complete(lock, data)
          }
      }

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
        protected def complete(k: ListenerLock[U], data: T): Unit =
          k.complete(f(data))

  /** Pass first result from any of `sources` to the continuation */
  def race[T](sources: Source[T]*): Source[T] =
    new Source[T] {

      def poll(k: Listener[T]): Boolean =
        val it = sources.iterator
        var found = false

        val listener = new Listener[T]:
          def tryLock()(using ctx: Listener.LockContext): Option[Listener.ListenerLock[T]] =
            k.tryLock() match
              case None =>
                found = true
                None
              case Some(lock) => Some(new Listener.ListenerLock{
                def release(): Unit = lock.release()
                def complete(data: T): Unit =
                  found = true
                  lock.complete(data)
              })
        end listener

        while it.hasNext && !found do
          it.next.poll(listener)
        found

      def onComplete(k: Listener[T]): Unit =
        val listener = new Listener.ForwardingListener[T](this, k) with Listener.LockingListener { self =>
          var foundBefore = false

          def tryLock()(using Listener.LockContext): Option[Listener.ListenerLock[T]] =
            if foundBefore then None
            else
              k.tryLock().flatMap: lock =>
                try this.lock()
                finally lock.release()

                if foundBefore then
                  self.unlock()
                  lock.release()
                  None
                else Some(new Listener.ListenerLock[T] {
                  def release(): Unit =
                    self.unlock()
                    lock.release()
                  def complete(data: T): Unit =
                    foundBefore = true
                    self.unlock()
                    lock.complete(data)
                })
        }
        sources.foreach(_.onComplete(listener))

      def dropListener(k: Listener[T]): Unit =
        val listener = new Listener.ForwardingListener[T](this, k):
          def tryLock()(using Listener.LockContext): Option[Listener.ListenerLock[T]] = ???
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
