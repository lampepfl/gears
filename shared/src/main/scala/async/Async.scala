package gears.async

import gears.async.Listener.NumberedLock
import gears.async.Listener.withLock

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import scala.collection.mutable
import scala.util.boundary

/** The async context: provides the capability to asynchronously [[Async.await await]] for [[Async.Source Source]]s, and
  * defines a scope for structured concurrency through a [[CompletionGroup]].
  *
  * As both a context and a capability, the idiomatic way of using [[Async]] is to be implicitly passed around
  * functions, as an `using` parameter:
  * {{{
  * def function()(using Async): T = ???
  * }}}
  *
  * It is not recommended to store [[Async]] in a class field, since it complicates scoping rules.
  *
  * @param support
  *   An implementation of the underlying asynchronous operations (suspend and resume). See [[AsyncSupport]].
  * @param scheduler
  *   An implementation of a scheduler, for scheduling computation as they are spawned or resumed. See [[Scheduler]].
  *
  * @see
  *   [[Async$.blocking Async.blocking]] for a way to construct an [[Async]] instance.
  * @see
  *   [[Async$.group Async.group]] and [[Future$.apply Future.apply]] for [[Async]]-subscoping operations.
  */
trait Async private[async] (using val support: AsyncSupport, val scheduler: support.Scheduler):
  /** Waits for completion of source `src` and returns the result. Suspends the computation.
    *
    * @see
    *   [[Async.Source.awaitResult]] and [[Async$.await]] for extension methods calling [[Async!.await]] from the source
    *   itself.
    */
  def await[T](src: Async.Source[T]): T

  /** Returns the cancellation group for this [[Async]] context. */
  def group: CompletionGroup

  /** Returns an [[Async]] context of the same kind as this one, with a new cancellation group. */
  def withGroup(group: CompletionGroup): Async

object Async extends AsyncImpl:
  /** The [[Async]] implementation based on blocking locks.
    *
    * @note
    *   Does not currently work on Scala.js, due to locks and condvars not being available.
    */
  private[async] class LockingAsync(val group: CompletionGroup)(using
      support: AsyncSupport,
      scheduler: support.Scheduler
  ) extends Async(using support, scheduler):
    private val lock = ReentrantLock()
    private val condVar = lock.newCondition()

    /** Wait for completion of async source `src` and return the result */
    override def await[T](src: Async.Source[T]): T =
      src
        .poll()
        .getOrElse:
          var result: Option[T] = None
          src.onComplete:
            Listener.acceptingListener: (t, _) =>
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
    override def withGroup(group: CompletionGroup): Async = Async.LockingAsync(group)

  /** A way to introduce asynchronicity into a synchronous environment. */
  trait FromSync private[async] ():
    private[async] type Output[+T]
    private[async] def apply[T](body: Async ?=> T): Output[T]

  object FromSync:
    /** A [[FromSync]] implementation that blocks the current runtime. */
    type Blocking = FromSync { type Output[+T] = T }

    /** Implements [[FromSync]] by directly blocking the current thread. */
    class BlockingWithLocks(using support: AsyncSupport, scheduler: support.Scheduler) extends FromSync:
      type Output[T] = T
      private[async] def apply[T](body: Async.Spawn ?=> T): Output[T] =
        Async.group(body)(using Async.LockingAsync(CompletionGroup.Unlinked))

  /** Execute asynchronous computation `body` using the given [[FromSync]] implementation.
    */
  inline def fromSync[T](using fs: FromSync)(body: Async.Spawn ?=> T): fs.Output[T] =
    fs(body)

  /** Execute asynchronous computation `body` from the context. Requires a [[FromSync.Blocking]] implementation. */
  inline def blocking[T](using
      fromSync: FromSync.Blocking
  )(
      body: Async.Spawn ?=> T
  ): T =
    fromSync(body)

  /** Returns the currently executing Async context. Equivalent to `summon[Async]`. */
  inline def current(using async: Async): Async = async

  /** [[Async.Spawn]] is a special subtype of [[Async]], also capable of spawning runnable [[Future]]s.
    *
    * Most functions should not take [[Spawn]] as a parameter, unless the function explicitly wants to spawn "dangling"
    * runnable [[Future]]s. Instead, functions should take [[Async]] and spawn scoped futures within [[Async.group]].
    */
  opaque type Spawn <: Async = Async

  /** Runs `body` inside a spawnable context where it is allowed to spawn concurrently runnable [[Future]]s. When the
    * body returns, all spawned futures are cancelled and waited for.
    */
  def group[T](body: Async.Spawn ?=> T)(using Async): T =
    withNewCompletionGroup(CompletionGroup().link())(body)

  private def cancelAndWaitGroup(group: CompletionGroup)(using async: Async) =
    val completionAsync =
      if CompletionGroup.Unlinked == async.group
      then async
      else async.withGroup(CompletionGroup.Unlinked)
    group.cancel()
    group.waitCompletion()(using completionAsync)

  /** Runs a body within another completion group. When the body returns, the group is cancelled and its completion
    * awaited with the `Unlinked` group.
    */
  private[async] def withNewCompletionGroup[T](group: CompletionGroup)(body: Async.Spawn ?=> T)(using
      async: Async
  ): T =
    try body(using async.withGroup(group))
    finally cancelAndWaitGroup(group)(using async)

  /** A Resource that grants access to the [[Spawn]] capability. On cleanup, every spawned [[Future]] is cancelled and
    * awaited, similar to [[Async.group]].
    *
    * Note that the [[Spawn]] from the resource must not be used for awaiting after allocation.
    */
  val spawning = new Resource[Spawn]:
    override def use[V](body: Spawn => V)(using Async): V = group(spawn ?=> body(spawn))
    override def allocated(using allocAsync: Async): (Spawn, (Async) ?=> Unit) =
      val group = CompletionGroup() // not linked to allocAsync's group because it would not unlink itself
      (allocAsync.withGroup(group), closeAsync ?=> cancelAndWaitGroup(group)(using closeAsync))

  /** An asynchronous data source. Sources can be persistent or ephemeral. A persistent source will always pass same
    * data to calls of [[Source!.poll]] and [[Source!.onComplete]]. An ephemeral source can pass new data in every call.
    *
    * @see
    *   An example of a persistent source is [[gears.async.Future]].
    * @see
    *   An example of an ephemeral source is [[gears.async.Channel]].
    */
  trait Source[+T]:
    /** Checks whether data is available at present and pass it to `k` if so. Calls to `poll` are always synchronous and
      * non-blocking.
      *
      * The process is as follows:
      *   - If no data is immediately available, return `false` immediately.
      *   - If there is data available, attempt to lock `k`.
      *     - If `k` is no longer available, `true` is returned to signal this source's general availability.
      *     - If locking `k` succeeds:
      *       - If data is still available, complete `k` and return true.
      *       - Otherwise, unlock `k` and return false.
      *
      * Note that in all cases, a return value of `false` indicates that `k` should be put into `onComplete` to receive
      * data in a later point in time.
      *
      * @return
      *   Whether poll was able to pass data to `k`. Note that this is regardless of `k` being available to receive the
      *   data. In most cases, one should pass `k` into [[Source!.onComplete]] if `poll` returns `false`.
      */
    def poll(k: Listener[T]): Boolean

    /** Once data is available, pass it to the listener `k`. `onComplete` is always non-blocking.
      *
      * Note that `k`'s methods will be executed on the same thread as the [[Source]], usually in sequence. It is hence
      * important that the listener itself does not perform expensive operations.
      */
    def onComplete(k: Listener[T]): Unit

    /** Signal that listener `k` is dead (i.e. will always fail to acquire locks from now on), and should be removed
      * from `onComplete` queues.
      *
      * This permits original, (i.e. non-derived) sources like futures or channels to drop the listener from their
      * waiting sets.
      */
    def dropListener(k: Listener[T]): Unit

    /** Similar to [[Async.Source!.poll(k:Listener[T])* poll]], but instead of passing in a listener, directly return
      * the value `T` if it is available.
      */
    def poll(): Option[T] =
      var resultOpt: Option[T] = None
      poll(Listener.acceptingListener { (x, _) => resultOpt = Some(x) })
      resultOpt

    /** Waits for an item to arrive from the source. Suspends until an item returns.
      *
      * This is an utility method for direct waiting with `Async`, instead of going through listeners.
      */
    final def awaitResult(using ac: Async) = ac.await(this)
  end Source

  extension [T](src: Source[scala.util.Try[T]])
    /** Waits for an item to arrive from the source, then automatically unwraps it. Suspends until an item returns.
      * @see
      *   [[Source!.awaitResult awaitResult]] for non-unwrapping await.
      */
    inline def await(using Async) = src.awaitResult.get
  extension [E, T](src: Source[Either[E, T]])
    /** Waits for an item to arrive from the source, then automatically unwraps it. Suspends until an item returns.
      * @see
      *   [[Source!.awaitResult awaitResult]] for non-unwrapping await.
      */
    inline def await(using Async) = src.awaitResult.right.get

  /** An original source has a standard definition of [[Source.onComplete onComplete]] in terms of [[Source.poll poll]]
    * and [[OriginalSource.addListener addListener]].
    *
    * Implementations should be the resource owner to handle listener queue and completion using an object monitor on
    * the instance.
    */
  abstract class OriginalSource[+T] extends Source[T]:
    /** Add `k` to the listener set of this source. */
    protected def addListener(k: Listener[T]): Unit

    def onComplete(k: Listener[T]): Unit = synchronized:
      if !poll(k) then addListener(k)

  end OriginalSource

  object Source:
    /** Create a [[Source]] containing the given values, resolved once for each.
      *
      * @return
      *   an ephemeral source of values arriving to listeners in a queue. Once all values are received, attaching a
      *   listener with [[Source!.onComplete onComplete]] will be a no-op (i.e. the listener will never be called).
      */
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
      * received.
      *
      * Note that `f` is **always** run on the computation that produces the values from the original source, so this is
      * very likely to run **sequentially** and be a performance bottleneck.
      *
      * @param f
      *   the transformation function to be run on every value. `f` is run *before* the item is passed to the
      *   [[Listener]].
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

  /** Creates a source that "races" a list of sources.
    *
    * Listeners attached to this source is resolved with the first item arriving from one of the sources. If multiple
    * sources are available at the same time, one of the items will be returned with no priority. Items that are not
    * returned are '''not''' consumed from the upstream sources.
    *
    * @see
    *   [[raceWithOrigin]] for a race source that also returns the upstream origin of the item.
    * @see
    *   [[Async$.select Async.select]] for a convenient syntax to race sources and awaiting them with [[Async]].
    */
  def race[T](sources: Source[T]*): Source[T] = raceImpl[T, T]((v, _) => v)(sources*)

  /** Like [[race]], but the returned value includes a reference to the upstream source that the item came from.
    * @see
    *   [[Async$.select Async.select]] for a convenient syntax to race sources and awaiting them with [[Async]].
    */
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
                    numberedLock.lock()
                    if found then
                      numberedLock.unlock()
                      // no cleanup needed here, since we have done this by an earlier `complete` or `lockNext`
                      false
                    else true
                def release() =
                  numberedLock.unlock()

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
    *
    * @see
    *   [[handle Source.handle]] (and its operator alias [[~~> ~~>]])
    * @see
    *   [[Async$.select Async.select]] where [[SelectCase]] is used.
    */
  opaque type SelectCase[T] = (Source[?], Nothing => T)
  //                           ^ unsafe types, but we only construct SelectCase from `handle` which is safe

  extension [T](src: Source[T])
    /** Attach a handler to `src`, creating a [[SelectCase]].
      * @see
      *   [[Async$.select Async.select]] where [[SelectCase]] is used.
      */
    inline def handle[U](f: T => U): SelectCase[U] = (src, f)

    /** Alias for [[handle]]
      * @see
      *   [[Async$.select Async.select]] where [[SelectCase]] is used.
      */
    inline def ~~>[U](f: T => U): SelectCase[U] = src.handle(f)

  /** Race a list of sources with the corresponding handler functions, once an item has come back. Like [[race]],
    * [[select]] guarantees exactly one of the sources are polled. Unlike [[transformValuesWith]], the handler in
    * [[select]] is run in the same async context as the calling context of [[select]].
    *
    * @see
    *   [[handle Source.handle]] (and its operator alias [[~~> ~~>]]) for methods to create [[SelectCase]]s.
    * @example
    *   {{{
    * // Race a channel read with a timeout
    * val ch = SyncChannel[Int]()
    * // ...
    * val timeout = Future(sleep(1500.millis))
    *
    * Async.select(
    *   ch.readSrc.handle: item =>
    *     Some(item * 2),
    *   timeout ~~> _ => None
    * )
    *   }}}
    */
  def select[T](cases: SelectCase[T]*)(using Async) =
    val (input, which) = raceWithOrigin(cases.map(_._1)*).awaitResult
    val (_, handler) = cases.find(_._1 == which).get
    handler.asInstanceOf[input.type => T](input)

  /** Race two sources, wrapping them respectively in [[Left]] and [[Right]] cases.
    * @return
    *   a new [[Source]] that resolves with [[Left]] if `src1` returns an item, [[Right]] if `src2` returns an item,
    *   whichever comes first.
    * @see
    *   [[race]] and [[select]] for racing more than two sources.
    */
  def either[T1, T2](src1: Source[T1], src2: Source[T2]): Source[Either[T1, T2]] =
    race(src1.transformValuesWith(Left(_)), src2.transformValuesWith(Right(_)))
end Async
