package gears.async.io.shareable

import gears.async.Async
import gears.async.CompletionGroup

/** How a performer is suspended until a source of the backend's own has its item, an op its outcome: where the
  * platforms differ in what suspending is. On the JVM and Scala Native it is gears' own (`Async.await`).
  */
private[io] object Parking {

  /** Suspends until `source` has its item, or the performer's group is cancelled, which throws. */
  inline def parkOn[T](source: Async.Source[T])(using async: Async): T = async.await(source)

  /** Suspends until `source` has its item, whatever becomes of the performer's group. */
  inline def parkUncancelled[T](source: Async.Source[T])(using async: Async): T =
    async.withGroup(CompletionGroup.Unlinked).await(source)
}
