package gears.async

/** A Resource wraps allocation to some asynchronously allocatable and releasable resource and grants access to it. It
  * allows both structured access (similar to [[scala.util.Using]]) and unstructured allocation.
  */
trait Resource[+T]:
  self =>

  /** Run a structured action on the resource. It is allocated and released automatically.
    *
    * @param body
    *   the action to run on the resource
    * @return
    *   the result of [[body]]
    */
  def use[V](body: T => V)(using Async): V =
    val res = allocated
    try body(res._1)
    finally res._2

  /** Allocate the resource and leak it. **Use with caution**. The programmer is responsible for closing the resource
    * with the returned handle.
    *
    * @return
    *   the allocated access to the resource data as well as a handle to close it
    */
  def allocated(using Async): (T, Async ?=> Unit)

  /** Create a derived resource that inherits the close operation.
    *
    * @param fn
    *   the function used to transform the resource data. It is only run on allocation/use.
    * @return
    *   the transformed resource used to access the mapped resource data
    */
  def map[U](fn: T => Async ?=> U): Resource[U] = new Resource[U]:
    override def use[V](body: U => V)(using Async): V = self.use(t => body(fn(t)))
    override def allocated(using Async): (U, (Async) ?=> Unit) =
      val res = self.allocated
      try
        (fn(res._1), res._2)
      catch
        e =>
          res._2
          throw e
    override def map[Q](fn2: U => (Async) ?=> Q): Resource[Q] = self.map(t => fn2(fn(t)))

  /** Create a derived resource that creates a inner resource from the resource data. The inner resource will be
    * acquired simultaneously, thus it can both transform the resource data and add a new cleanup action.
    *
    * @param fn
    *   a function that creates an inner resource
    * @return
    *   the transformed resource that provides the two-levels-in-one access
    */
  def flatMap[U](fn: T => Async ?=> Resource[U]): Resource[U] = new Resource[U]:
    override def use[V](body: U => V)(using Async): V = self.use(t => fn(t).use(body))
    override def allocated(using Async): (U, (Async) ?=> Unit) =
      val res = self.allocated
      try
        val mapped = fn(res._1).allocated
        (
          mapped._1,
          { closeAsync ?=>
            try mapped._2(using closeAsync) // close inner first
            finally res._2(using closeAsync) // then close second, even if first failed
          }
        )
      catch
        e =>
          res._2
          throw e
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
  inline def apply[T](inline alloc: Async ?=> T, inline close: T => Async ?=> Unit): Resource[T] =
    new Resource[T]:
      def allocated(using Async): (T, (Async) ?=> Unit) =
        val res = alloc
        (res, close(res))

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
  inline def spawning[T](inline spawnBody: Async.Spawn ?=> T) = Async.spawning.map(spawn => spawnBody(using spawn))

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
  def both[T, U, V](res1: Resource[T], res2: Resource[U])(join: (T, U) => V): Resource[V] = new Resource[V]:
    override def allocated(using Async): (V, (Async) ?=> Unit) =
      val alloc1 = res1.allocated
      val alloc2 =
        try res2.allocated
        catch
          e =>
            alloc1._2
            throw e

      try
        val joined = join(alloc1._1, alloc2._1)
        (
          joined,
          { closeAsync ?=>
            try alloc1._2(using closeAsync)
            finally alloc2._2(using closeAsync)
          }
        )
      catch
        e =>
          try alloc1._2
          finally alloc2._2
          throw e
  end both

  /** Create a resource combining access to a list of resources
    *
    * @param ress
    *   the list of single resources
    * @return
    *   the resource of the list of elements provided by the single resources
    */
  def all[T](ress: List[Resource[T]]): Resource[List[T]] = ress match
    case Nil          => just(Nil)
    case head :: Nil  => head.map(List(_))
    case head :: next => both(head, all(next))(_ :: _)
end Resource
