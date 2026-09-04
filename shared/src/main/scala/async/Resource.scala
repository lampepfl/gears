package gears.async

import language.experimental.captureChecking
import caps.*

trait Allocated[+T](val item: T):
  self: Allocated[T]^ =>
  def cleanup(using Async^): Unit

  def map[U](f: T => Async^ ?=> U)(using Async^): Allocated[U]^{this} =
    try
      val v = f(item)
      new Allocated(v):
        def cleanup(using Async^) = self.cleanup
    catch
      e =>
        self.cleanup
        throw e

/** A Resource wraps allocation to some asynchronously allocatable and releasable resource and grants access to it. It
  * allows both structured access (similar to [[scala.util.Using]]) and unstructured allocation.
  */
trait Resource[+T]:
  self: Resource[T]^ =>

  /** Run a structured action on the resource. It is allocated and released automatically.
    *
    * @param body
    *   the action to run on the resource
    * @return
    *   the result of [[body]]
    */
  def use[V](body: T => V)(using Async^): V =
    val r = allocated
    try body(r.item)
    finally r.cleanup

  /** Allocate the resource and leak it. **Use with caution**. The programmer is responsible for closing the resource
    * with the returned handle.
    *
    * @return
    *   the allocated access to the resource data as well as a handle to close it
    */
  def allocated(using Async^): Allocated[T]^{this}

  /** Create a derived resource that inherits the close operation.
    *
    * @param fn
    *   the function used to transform the resource data. It is only run on allocation/use.
    * @return
    *   the transformed resource used to access the mapped resource data
    */
  def map[U](fn: T => Async^ ?=> U): Resource[U]^{this, fn} =
    class MapResource[T](outer: Resource[T]^, fn: T => Async^ ?=> U) extends Resource[U]:
      override def use[V](body: U => V)(using Async^): V = outer.use(t => body(fn(t)))
      override def allocated(using Async^): Allocated[U]^{this} = outer.allocated.map(fn)
      override def map[Q](fn2: U => (Async^) ?=> Q): Resource[Q]^{this, fn2} = outer.map(t => fn2(fn(t)))
    MapResource(self, fn)

  /** Create a derived resource that creates a inner resource from the resource data. The inner resource will be
    * acquired simultaneously, thus it can both transform the resource data and add a new cleanup action.
    *
    * @param fn
    *   a function that creates an inner resource
    * @return
    *   the transformed resource that provides the two-levels-in-one access
    */
  def flatMap[U, R^](fn: T => Async^ ?=> Resource[U]^{R}): Resource[U]^{this, fn, R} =
    class FlatMapResource[T](outer: Resource[T]^, fn: T => Async^ ?=> Resource[U]^{R}) extends Resource[U]:
      override def use[V](body: U => V)(using Async^): V = outer.use(t => fn(t).use(body))
      override def allocated(using Async^): Allocated[U]^{this} =
        val res: Allocated[T]^{this} = outer.allocated
        try
          val r: Resource[U]^{R} = fn(res.item)
          val mapped: Allocated[U]^{this} =
            // Bug: see scala/scala3#26917
            caps.unsafe.unsafeAssumePure(r.allocated)
          new Allocated(mapped.item):
            def cleanup(using Async^) =
              try mapped.cleanup
              finally res.cleanup
        catch
          e =>
            res.cleanup
            throw e
    FlatMapResource(self, fn)
end Resource

object Resource:
  /** Create a Resource from the allocation and release operation. The returned resource will allocate a new instance,
    * i.e., call [[alloc]], for every call to [[use]] and [[allocated]].
    *
    * @param alloc
    *   the allocation (generating) operation
    * @param close
    *   the release (close) operation
    * @return
    *   a new Resource exposing the allocatable object in a safe way
    */
  def apply[T](alloc: Async^ ?=> T, close: T => Async^ ?=> Unit): Resource[T]^{alloc, close} =
    class NewResource[T](alloc: Async^ ?=> T, close: T => Async^ ?=> Unit) extends Resource[T]:
      def allocated(using Async^): Allocated[T]^{this} =
        val v = alloc
        new Allocated(v):
          def cleanup(using Async^) = close(item)
    NewResource[T](alloc, close)

  /** Create a concurrent computation resource from an allocator function. It can use the given capability to spawn
    * [[Future]]s and return a handle to communicate with them. Allocation is only complete after that allocator
    * returns. The resource is only allocated on use.
    *
    * If the [[Async.Spawn]] capability is used for [[Async.await]]ing, it may only be done synchronously by the
    * spawnBody.
    *
    * No presumption is made on reusability of the Resource. Thus, if the [[spawnBody]] is re-runnable, so is the
    * Resource created from it.
    *
    * @param spawnBody
    *   the allocator to setup and start asynchronous computation
    * @return
    *   a new resource wrapping access to the spawnBody's results
    */
  // inline def spawning[T](inline spawnBody: Async.Spawn^ ?=> T) = Async.spawning.map(spawn => spawnBody(using spawn))

  /** Create a resource that does not need asynchronous allocation nor cleanup.
    *
    * @param data
    *   the generator that provides the resource element
    * @return
    *   a resource wrapping the data provider
    */
  inline def just[T](inline data: => T) = apply(data, _ => ())

  /** Create a resource combining access to two separate resources.
    *
    * @param res1
    *   the first resource
    * @param res2
    *   the second resource
    * @param join
    *   an operator to combine the elements from both resources to that of the combined resource
    * @return
    *   a new resource wrapping access to the combined element
    */
  def both[T, U, V](res1: Resource[T]^, res2: Resource[U]^)(join: (T, U) => V): Resource[V]^{res1, res2, join} =
    class BothResource[V](res1: Resource[T]^, res2: Resource[U]^, join: (T, U) => V) extends Resource[V]:
      override def allocated(using async: Async^): Allocated[V]^{this} =
        import util.Try
        val r1 = res1.allocated
        val r2 =
          try res2.allocated
          catch
            e =>
              r1.cleanup
              throw e

        try
          val joined = join(r1.item, r2.item)
          new Allocated(joined):
            def cleanup(using Async^) =
              val t1 = Try(r1.cleanup)
              val t2 = Try(r2.cleanup)
              t1.get
              t2.get
        catch
          e =>
            val t1 = Try(r1.cleanup)
            val t2 = Try(r2.cleanup)
            t1.get
            t2.get
            throw e
    BothResource(res1, res2, join)
  end both

  /** Create a resource combining access to a list of resources
    *
    * @param ress
    *   the list of single resources
    * @return
    *   the resource of the list of elements provided by the single resources
    */
  def all[T, R^](ress: List[Resource[T]^{R}]): Resource[List[T]]^{R} = ress match
    case Nil          => just(Nil)
    case head :: Nil  => head.map(List(_))
    case head :: next => both(head, all(next))(_ :: _)
end Resource
