package gears.async.io
package shareable

/** What a run's handles belong to: its pool has one, and every handle is made with the one of the pool whose op opened
  * it. It holds the handles that are open, which it closes when the run ends, so that nothing a program forgot to close
  * outlives its run; and it is what an op is checked against as it is performed, since a handle is its run's. The types
  * say so only to a program written against an interface, whose handles are path-dependent on its `Io`: with the
  * backend known, every run's ops are the one object, and a handle passes from run to run with no cast. So the check is
  * made as the op is performed.
  */
final class HandleOwner {
  private val open: java.util.Set[AbstractBaseHandle] =
    java.util.concurrent.ConcurrentHashMap.newKeySet[AbstractBaseHandle]()

  private[shareable] def opened(handle: AbstractBaseHandle): Unit = open.add(handle): Unit

  private[shareable] def closed(handle: AbstractBaseHandle): Unit = open.remove(handle): Unit

  /** Refuses `handle` if it is another run's: for an op about to be performed by this one's pool. */
  inline def check(handle: BaseHandle): Unit =
    if handle.owner ne this then throw HandleOwner.foreign(handle)

  /** Closes what is still open: for a pool as it shuts down, its program having ended, so that nothing of its own is
    * under way on them. A handle carried out of the run is a closed handle to whoever uses it next.
    */
  def closeAll(): Unit =
    open.toArray(new Array[AbstractBaseHandle](0)).foreach { handle =>
      try handle.requestClose(): Unit
      catch case _: java.io.IOException | _: IllegalStateException => () // closed meanwhile
    }
}

object HandleOwner {
  private def foreign(handle: BaseHandle): IllegalArgumentException =
    new IllegalArgumentException(s"A handle of another run can't be used in this one: $handle")
}
