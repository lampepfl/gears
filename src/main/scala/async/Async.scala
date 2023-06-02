package concurrent
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.concurrent.ExecutionContext

/** A context that allows to suspend waiting for asynchronous data sources
 */
trait Async:

  /** Wait for completion of async source `src` and return the result */
  def await[T](src: Async.Source[T]): T

  /** The execution context for this Async */
  def scheduler: ExecutionContext

  /** The cancellation group for this Async */
  def group: CancellationGroup

  /** An Async of the same kind as this one, with a new cancellation group */
  def withGroup(group: CancellationGroup): Async

object Async:

  /** An implementation of Async that blocks the running thread when waiting */
  private class Blocking(val group: CancellationGroup)(using val scheduler: ExecutionContext) extends Async:
    def await[T](src: Source[T]): T =
      var result: Option[T] = None
      val poll = src.poll: x =>
        synchronized:
          result = Some(x)
          notify()
        true
      if !poll then synchronized:
        while result.isEmpty do wait()
      result.get

    def withGroup(group: CancellationGroup) = Blocking(group)
  end Blocking

  /** Execute asynchronous computation `body` on currently running thread.
   *  The thread will suspend when the computation waits.
   */
  def blocking[T](body: Async ?=> T)(using ExecutionContext): T =
    body(using Blocking(CancellationGroup.Unlinked))

  /** The currently executing Async context */
  inline def current(using async: Async): Async = async

  /** Await source result in currently executing Async context */
  inline def await[T](src: Source[T])(using async: Async): T = async.await(src)

  def group[T](body: Async ?=> T)(using async: Async): T =
    val newGroup = CancellationGroup().link()
    try body(using async.withGroup(newGroup))
    finally newGroup.cancel()

  /** A function `T => Boolean` whose lineage is recorded by its implementing
   *  classes. The Listener function accepts values of type `T` and returns
   *  `true` iff the value was consumed by an async block.
   */
  trait Listener[-T] extends (T => Boolean)

  /** A listener for values that are processed by the given source `src` and
   *  that are demanded by the continuation listener `continue`.
   */
  abstract case class ForwardingListener[T](src: Source[?], continue: Listener[?]) extends Listener[T]

  /** An asynchronous data source. Sources can be persistent or ephemeral.
   *  A persistent source will always pass same data to calls of `poll and `onComplete`.
   *  An ephememral source can pass new data in every call.
   *  An example of a persistent source is `Future`.
   *  An example of an ephemeral source is `Channel`.
   */
  trait Source[+T]:
    /** If data is available at present, pass it to function `k`
     *  and return the result of this call. Otherwise, call when the source is complete asynchronously.
     *  `k` returns true iff the data was consumed in an async block.
     *  Returns whether the poll was done synchronously.
     */
    def poll(k: Listener[T]): Boolean

    /** Signal that listener `k` is dead (i.e. will always return `false` from now on).
     *  This permits original, (i.e. non-derived) sources like futures or channels
     *  to drop the  listener from their waiting sets.
     */
    def dropListener(k: Listener[T]): Unit

  end Source

  /** A source that transforms an original source in some way */
  abstract class DerivedSource[T, U](val original: Source[T]) extends Source[U]:
    /** Handle a value `x` passed to the original source by possibly
     *  invokiong the continuation for this source.
     */
    protected def listen(x: T, k: Listener[U]): Boolean

    private def transform(k: Listener[U]): Listener[T] =
      new ForwardingListener[T](this, k):
        def apply(x: T): Boolean = listen(x, k)

    def poll(k: Listener[U]): Boolean =
      original.poll(transform(k))

    def dropListener(k: Listener[U]): Unit =
      original.dropListener(transform(k))

  end DerivedSource

  extension [T](src: Source[T])

    /** Pass on data transformed by `f` */
    def map[U](f: T => U): Source[U]  =
      new DerivedSource[T, U](src):
        def listen(x: T, k: Listener[U]) = k(f(x))

    /** Pass on only data matching the predicate `p` */
    def filter(p: T => Boolean): Source[T] =
      new DerivedSource[T, T](src):
        def listen(x: T, k: Listener[T]) = p(x) && k(x)

  /** Pass first result from any of `sources` to the continuation */
  def race[T](sources: Source[T]*): Source[T] =
    new Source[T]:
      def poll(k: Listener[T]): Boolean =
        val listener = new ForwardingListener[T](this, k):
          var foundBefore = false
          def continueIfFirst(x: T): Boolean = synchronized:
            if foundBefore then false else { foundBefore = k(x); foundBefore }
          def apply(x: T): Boolean =
            val found = continueIfFirst(x)
            if found then sources.foreach(_.dropListener(this))
            found
        sources.iterator.exists(_.poll(listener))

      def dropListener(k: Listener[T]): Unit =
        val listener = new ForwardingListener[T](this, k):
          def apply(x: T): Boolean = ???
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

