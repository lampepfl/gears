package gears.async
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicLong

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
    body(using Blocking(CompletionGroup.Unlinked))

  /** The currently executing Async context */
  inline def current(using async: Async): Async = async

  /** Await source result in currently executing Async context */
  inline def await[T](src: Source[T])(using async: Async): T = async.await(src)

  def group[T](body: Async ?=> T)(using async: Async): T =
    withNewCompletionGroup(CompletionGroup())(body)

  def withCompletionHandler[T](handler: Cancellable => Async ?=> Unit)(body: Async ?=> T)(using async: Async): T =
    val combined = (c: Cancellable) => (async: Async) ?=>
      handler(c)
      async.group.handleCompletion(c)
    withNewCompletionGroup(CompletionGroup(combined))(body)

  private def withNewCompletionGroup[T](group: CompletionGroup)(body: Async ?=> T)(using async: Async): T =
    awaitingGroup(body)(using async.withGroup(group.link()))

  private[async] def awaitingGroup[T](body: Async ?=> T)(using async: Async): T =
    try body
    finally
      async.group.cancel()
      async.group.waitCompletion()

  /** An asynchronous data source. Sources can be persistent or ephemeral.
   *  A persistent source will always pass same data to calls of `poll and `onComplete`.
   *  An ephemeral source can pass new data in every call.
   *  An example of a persistent source is `Future`.
   *  An example of an ephemeral source is `Channel`.
   */
  trait Source[+T]:

    /** If data is available at present, pass it to function `k`
     *  and return the result of this call. Otherwise return false.
     *  `k` returns true iff the data was consumed in an async block.
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
     *  requesting the upstream listener for this source.
     */
    protected def filteredLock(x: T, k: Listener[U])(using Listener.LockContext): Option[Listener.ListenerLock]

    /** Handle a release request for a previous lock from the original source by
     *  passing on any operation (complete/release) to the upstream listener for
     *  this source. The default behavior is to forward the release operation.
     */
    protected def release(k: Listener.ListenerLock)(using ctx: Listener.LockContext): Unit =
      ctx.release(k)

    /** Handle a complete request for a previous lock from the original source by
     *  passing on any operation (complete/release) to the upstream listener for
     *  this source. The default behavior is to forward the complete operation.
     */
    protected def complete(k: Listener.ListenerLock)(using ctx: Listener.LockContext): Unit =
      ctx.complete(k)

    private def transform(k: Listener[U]): Listener[T] =
      new Listener.ForwardingListener[T](this, k):
        override def filteredLock(data: T)(using Listener.LockContext): Option[Listener.ListenerLock] =
          DerivedSource.this.filteredLock(data, k).map: innerLock =>
            new Listener.ListenerLock:
              override def release()(using Listener.LockContext): Unit = DerivedSource.this.release(innerLock)
              override def complete()(using Listener.LockContext): Unit = DerivedSource.this.complete(innerLock)

    def poll(k: Listener[U]): Boolean =
      original.poll(transform(k))
    def onComplete(k: Listener[U]): Unit =
      original.onComplete(transform(k))
    def dropListener(k: Listener[U]): Unit =
      original.dropListener(transform(k))

  end DerivedSource

  extension [T](src: Source[T])

    /** Pass on data transformed by `f` */
    def map[U](f: T => U): Source[U]  =
      new DerivedSource[T, U](src):
        def filteredLock(x: T, k: Listener[U])(using ctx: Listener.LockContext): Option[Listener.ListenerLock] =
          ctx.lock(k, f(x))

    /** Pass on only data matching the predicate `p` */
    def filter(p: T => Boolean): Source[T] =
      new DerivedSource[T, T](src):
        def filteredLock(x: T, k: Listener[T])(using ctx: Listener.LockContext): Option[Listener.ListenerLock] =
          if p(x) then ctx.lock(k, x) else None

  /** Pass first result from any of `sources` to the continuation */
  def race[T](sources: Source[T]*): Source[T] =
    new Source[T]:

      def poll(k: Listener[T]): Boolean =
        val it = sources.iterator
        var found = false
        while it.hasNext && !found do
          it.next.poll(new Listener[T]:
            def filteredLock(data: T)(using ctx: Listener.LockContext): Option[Listener.ListenerLock] =
              ctx.lock(k, data).map(lock => new Listener.ListenerLock:
                def release()(using ctx: Listener.LockContext): Unit = ctx.release(lock)
                def complete()(using ctx: Listener.LockContext): Unit =
                  found = true
                  ctx.complete(lock)
              )
          )
        found

      def onComplete(k: Listener[T]): Unit =
        val listener = new Listener.ForwardingListener[T](this, k) with Listener.NumberedListener {
          val baseLock = new ReentrantLock()
          var foundBefore = false

          def filteredLock(data: T)(using ctx: Listener.LockContext): Option[Listener.ListenerLock] =
            if foundBefore then
              None
            else
              ctx.lock(k, data).flatMap: lock =>
                baseLock.lock()
                if foundBefore then
                  baseLock.unlock()
                  ctx.release(lock)
                  None
                else Some:
                  new Listener.ListenerLock:
                    def release()(using ctx: Listener.LockContext): Unit =
                      baseLock.unlock()
                      ctx.release(lock)
                    def complete()(using ctx: Listener.LockContext): Unit =
                      foundBefore = true
                      baseLock.unlock()
                      ctx.complete(lock)
        }
        sources.foreach(_.onComplete(listener))

      def dropListener(k: Listener[T]): Unit =
        val listener = new Listener.ForwardingListener[T](this, k):
          def filteredLock(data: T)(using Listener.LockContext): Option[Listener.ListenerLock] = ???
        // not to be called, we need the listener only for its
        // hashcode and equality test.
        sources.foreach(_.dropListener(listener))

  end race

  /** If left (respectively, right) source succeeds with `x`, pass `Left(x)`,
   *  (respectively, Right(x)) on to the continuation.
   */
  def either[T1, T2](src1: Source[T1], src2: Source[T2]): Source[Either[T1, T2]] =
    race(src1.map(Left(_)), src2.map(Right(_)))

end Async