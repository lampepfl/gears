package gears.async
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicLong
import gears.async.Listener.withLock
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

  def group[T](body: Async ?=> T)(using async: Async): T =
    withNewCompletionGroup(CompletionGroup().link())(body)

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
    final def awaitResult(using ac: Async) = ac.await(this)
  end Source

  extension [T](src: Source[scala.util.Try[T]])
    /** Waits for an item to arrive from the source, then automatically unwraps it. */
    inline def await(using Async) = src.awaitResult.get
  extension [E, T](src: Source[Either[E, T]])
    /** Waits for an item to arrive from the source, then automatically unwraps it. */
    inline def await(using Async) = src.awaitResult.right.get

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
      val q = java.util.concurrent.ConcurrentLinkedQueue[T]()
      q.addAll(values.asJavaCollection)
      new Source[T]:
        override def poll(k: Listener[T]): Boolean =
          if q.isEmpty() then false
          else if !k.acquireLock() then true
          else
            val item = q.poll()
            if item == null then
              k.releaseLock()
              false
            else
              k.complete(item, this)
              true

        override def onComplete(k: Listener[T]): Unit = poll(k)
        override def dropListener(k: Listener[T]): Unit = ()
    end values

  extension [T](src: Source[T])
    /** Create a new source that requires the original source to run the given transformation function on every value
      * received. Note that [[f]] is **always** run on the computation that produces the values from the original
      * source, so this is very likely to run **sequentially** and be a performance bottleneck.
      */
    def transformValuesWith[U](f: T => U) =
      new Source[U]:
        selfSrc =>
        def transform(k: Listener[U]) =
          new Listener.ForwardingListener[T](selfSrc, k):
            val lock = k.lock
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
          val lock = k.lock
          def complete(data: U, source: Async.Source[U]) =
            k.complete(map(data, source), selfSrc)
        end listener

        while it.hasNext && !found do found = it.next.poll(listener)
        found

      def onComplete(k: Listener[T]): Unit =
        val listener = new Listener.ForwardingListener[U](this, k) { self =>
          inline def lockIsOurs = k.lock == null
          val lock =
            if k.lock != null then
              // if the upstream listener holds a lock already, we can utilize it.
              new Listener.ListenerLock:
                val selfNumber = k.lock.selfNumber
                override def acquire() =
                  if found then false // already completed
                  else if !k.lock.acquire() then
                    if !found && !synchronized { // getAndSet alternative, avoid racing only with self here.
                        val old = found
                        found = true
                        old
                      }
                    then sources.foreach(_.dropListener(self)) // same as dropListener(k), but avoids an allocation
                    false
                  else if found then
                    k.lock.release()
                    false
                  else true
                override def release() = k.lock.release()
            else
              new Listener.ListenerLock with NumberedLock:
                val selfNumber: Long = number
                def acquire() =
                  if found then false
                  else
                    acquireLock()
                    if found then
                      releaseLock()
                      // no cleanup needed here, since we have done this by an earlier `complete` or `lockNext`
                      false
                    else true
                def release() =
                  releaseLock()

          var found = false

          def complete(item: U, src: Async.Source[U]) =
            found = true
            if lockIsOurs then lock.release()
            sources.foreach(s => if s != src then s.dropListener(self))
            k.complete(map(item, src), selfSrc)
        } // end listener

        sources.foreach(_.onComplete(listener))

      def dropListener(k: Listener[T]): Unit =
        val listener = Listener.ForwardingListener.empty[U](this, k)
        sources.foreach(_.dropListener(listener))

    }
  end raceImpl

  /** Cases for handling async sources in a [[select]]. [[SelectCase]] can be constructed by extension methods `handle`
    * of [[Source]].
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
    val (input, which) = raceWithOrigin(cases.map(_._1)*).awaitResult
    val (_, handler) = cases.find(_._1 == which).get
    handler.asInstanceOf[input.type => T](input)

  /** If left (respectively, right) source succeeds with `x`, pass `Left(x)`, (respectively, Right(x)) on to the
    * continuation.
    */
  def either[T1, T2](src1: Source[T1], src2: Source[T2]): Source[Either[T1, T2]] =
    race(src1.transformValuesWith(Left(_)), src2.transformValuesWith(Right(_)))
end Async
