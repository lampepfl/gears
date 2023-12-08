package gears.async
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicLong
import gears.async.Listener.{withLock, ListenerLockWrapper}
import gears.async.Listener.NumberedLock
import scala.util.boundary

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

  private class Blocking(val group: CompletionGroup)(using support: AsyncSupport, scheduler: support.Scheduler)
      extends Async(using support, scheduler):
    private val lock = ReentrantLock()
    private val condVar = lock.newCondition()

    /** Wait for completion of async source `src` and return the result */
    override def await[T](src: Async.Source[T]): T =
      src
        .poll()
        .getOrElse:
          var result: Option[T] = None
          src onComplete Listener.acceptingListener: (t, _) =>
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

  /** Execute asynchronous computation `body` on currently running thread. The thread will suspend when the computation
    * waits.
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
    val combined = (c: Cancellable) =>
      (async: Async) ?=>
        handler(c)
        async.group.handleCompletion(c)
    withNewCompletionGroup(CompletionGroup(combined).link())(body)

  /** Runs a body within another completion group. When the body returns, the group is cancelled and its completion
    * awaited with the `Unlinked` group.
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

  /** An asynchronous data source. Sources can be persistent or ephemeral. A persistent source will always pass same
    * data to calls of `poll and `onComplete`. An ephemeral source can pass new data in every call. An example of a
    * persistent source is `Future`. An example of an ephemeral source is `Channel`.
    */
  trait Source[+T]:

    /** Check whether data is available at present and pass it to k if so. If no element is available, does not lock k
      * and returns false immediately. If there is (or may be) data available, the listener is locked and if it fails,
      * true is returned to signal this source's general availability. If locking k succeeds, only return true iff k's
      * complete is called. Calls to `poll` are always synchronous.
      */
    def poll(k: Listener[T]): Boolean

    /** Once data is available, pass it to function `k`. `k` returns true iff the data was consumed in an async block.
      * Calls to `onComplete` are usually asynchronous, meaning that the passed continuation `k` is a suspension.
      */
    def onComplete(k: Listener[T]): Unit

    /** Signal that listener `k` is dead (i.e. will always return `false` from now on). This permits original, (i.e.
      * non-derived) sources like futures or channels to drop the listener from their waiting sets.
      */
    def dropListener(k: Listener[T]): Unit

    /** Utility method for direct polling. */
    def poll(): Option[T] =
      var resultOpt: Option[T] = None
      poll(Listener.acceptingListener { (x, _) => resultOpt = Some(x) })
      resultOpt

    /** Utility method for direct waiting with `Async`. */
    def await(using Async) = Async.await(this)

  end Source

  /** An original source has a standard definition of `onComplete` in terms of `poll` and `addListener`. Implementations
    * should be the resource owner to handle listener queue and completion using an object monitor on the instance.
    */
  abstract class OriginalSource[+T] extends Source[T]:

    /** Add `k` to the listener set of this source */
    protected def addListener(k: Listener[T]): Unit

    def onComplete(k: Listener[T]): Unit = synchronized:
      if !poll(k) then addListener(k)

  end OriginalSource

  object Source:
    /** Create a [[Source]] containing the given values, resolved once for each. */
    def values[T](values: T*) =
      import scala.collection.JavaConverters._
      val q = java.util.concurrent.ConcurrentLinkedQueue[T](values.asJavaCollection)
      new Source[T]:
        override def poll(k: Listener[T]): Boolean =
          if q.isEmpty() then return false
          else
            k.lockCompletely(this) match
              case Listener.Gone => true
              case v: Listener.LockMarker =>
                val item = q.poll()
                if item == null then
                  k.releaseLock(v)
                  false
                else
                  k.complete(item, this)
                  true

        override def onComplete(k: Listener[T]): Unit = poll(k)
        override def dropListener(k: Listener[T]): Unit = ()
    end values

  extension [T](src: Source[T])
    /** Pass on data transformed by `f` */
    def map[U](f: T => U) =
      new Source[U]:
        selfSrc =>
        def transform(k: Listener[U]) =
          new Listener[T]:
            val lock = withLock(k) { inner => new ListenerLockWrapper(inner, selfSrc) }
            def complete(data: T, source: Async.Source[T]) =
              k.complete(f(data), selfSrc)

        def poll(k: Listener[U]): Boolean =
          src.poll(transform(k))
        def onComplete(k: Listener[U]): Unit =
          src.onComplete(transform(k))
        def dropListener(k: Listener[U]): Unit =
          src.dropListener(transform(k))

  def race[T](sources: Source[T]*): Source[T] = raceImpl[T, T]((v, _) => v)(sources*)
  def raceWithOrigin[T](sources: Source[T]*): Source[(T, Source[T])] =
    raceImpl[(T, Source[T]), T]((v, src) => (v, src))(sources*)

  /** Pass first result from any of `sources` to the continuation */
  private def raceImpl[T, U](map: (U, Source[U]) => T)(sources: Source[U]*): Source[T] =
    new Source[T] { selfSrc =>
      def poll(k: Listener[T]): Boolean =
        val it = sources.iterator
        var found = false

        val listener = new Listener.ForwardingListener[U](this, k):
          val lock = withLock(k) { inner => new ListenerLockWrapper(inner, selfSrc) }
          def complete(data: U, source: Async.Source[U]) =
            k.complete(map(data, source), selfSrc)
        end listener

        while it.hasNext && !found do
          found = it.next.poll(listener)
        found

      def onComplete(k: Listener[T]): Unit =
        val listener = new Listener.ForwardingListener[U](this, k)
          with NumberedLock
          with Listener.ListenerLock
          with Listener.PartialLock { self =>
          val lock = self

          var found = false
          def heldLock = if k.lock == null then Listener.Locked else this

          /* == PartialLock implementation == */
          // Note that this is bogus if k.lock is null, but we'll never use it if it is.
          val nextNumber = if k.lock == null then -1 else k.lock.selfNumber
          def lockNext() =
            val res = k.lock.lockSelf(selfSrc)
            if res == Listener.Gone then
              found = true // This is always false before this, since PartialLock is only returned when found is false
              sources.foreach(_.dropListener(this)) // same as dropListener(k), but avoids an allocation
            res

          /* == ListenerLock implementation == */
          val selfNumber = self.number
          def lockSelf(src: Async.Source[?]) =
            if found then Listener.Gone
            else
              self.acquireLock()
              if found then
                self.releaseLock()
                // no cleanup needed here, since we have done this by an earlier `complete` or `lockNext`
                Listener.Gone
              else heldLock
          def release(until: Listener.LockMarker) =
            self.releaseLock()
            if until == heldLock then null else k.lock

          def complete(item: U, src: Async.Source[U]) =
            found = true
            self.releaseLock()
            sources.foreach(s => if s != src then s.dropListener(self))
            k.complete(map(item, src), selfSrc)
        } // end listener

        sources.foreach(_.onComplete(listener))

      def dropListener(k: Listener[T]): Unit =
        val listener = Listener.ForwardingListener.empty[U](this, k)
        sources.foreach(_.dropListener(listener))

    }
  end raceImpl

  /** Cases for handling async sources in a [[select]]. [[SelectCase]] can be
   *  constructed by extension methods `handle` of [[Source]].
    */
  opaque type SelectCase[T] = (Source[?], Nothing => T)
  //                           ^ unsafe types, but we only construct SelectCase from `handle` which is safe

  extension [T](src: Source[T])
    /** Attach a handler to [[src]], creating a [[SelectCase]]. */
    inline def handle[U](f: T => U): SelectCase[U] = (src, f)

    /** Alias for [[handle]] */
    inline def ~~>[U](f: T => U): SelectCase[U] = src.handle(f)

  /** Race a list of sources with the corresponding handler functions, once an item has come back. Like [[race]],
    * [[select]] guarantees exactly one of the sources are polled. Unlike `map`ping a [[Source]], the handler in
    * [[select]] is run in the same async context as the calling context of [[select]].
    */
  def select[T](cases: SelectCase[T]*)(using Async) =
    val (input, which) = raceWithOrigin(cases.map(_._1)*).await
    val (_, handler) = cases.find(_._1 == which).get
    handler.asInstanceOf[input.type => T](input)

  /** If left (respectively, right) source succeeds with `x`, pass `Left(x)`, (respectively, Right(x)) on to the
    * continuation.
    */
  def either[T1, T2](src1: Source[T1], src2: Source[T2]): Source[Either[T1, T2]] =
    race(src1.map(Left(_)), src2.map(Right(_)))
end Async
