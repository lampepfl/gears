package gears.async

import scala.collection.mutable

/** A simple and possibly very inefficient countdown latch */
class CountdownLatch(private var count: Int) extends Async.OriginalSource[Unit]:
  val listeners = mutable.Set[Listener[Unit]]()

  override def poll(l: Listener[Unit]) =
    if count == 0 then l.completeNow((), this)
    else false

  override def addListener(l: Listener[Unit]) = synchronized:
    listeners += l

  override def dropListener(l: Listener[Unit]) = synchronized:
    listeners -= l

  def done() =
    val toWake =
      synchronized:
        assert(count > 0, "All countdown has been completed")
        count -= 1
        if count == 0 then
          val ls = listeners.toSeq
          listeners.clear()
          ls
        else Seq.empty
    toWake.foreach(_.completeNow((), this))
