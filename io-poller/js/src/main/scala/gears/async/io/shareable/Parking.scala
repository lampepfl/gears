package gears.async.io.shareable

import gears.async.Async
import gears.async.Cancellable
import gears.async.Listener

import java.util.concurrent.CancellationException
import scala.scalajs.js
import scala.scalajs.js.wasm.JSPI.allowOrphanJSAwait

/** How a performer is suspended until a source of the backend's own has its item, an op its outcome: where the
  * platforms differ in what suspending is. On Scala.js it is a promise awaited where the performer stands
  * (`js.await`), which suspends the stack it runs on and resumes that same stack. gears' own `Async.await` in a future
  * starts a stack for every resumption (`JsAsyncScheduler.execute`), which measured a third again on a round trip
  * over a UDP socket.
  */
private[io] object Parking {
  private object Cancelled

  private var parkedCount = 0

  /** One wait: the listener that resolves the promise awaited with the source's item; and what a cancel of the performer's group reaches, which resolves it as cancelled. A
    * promise takes its first resolution: of an item and a cancel, whichever came first.
    *
    * It is in the group's set for as long as the wait lasts, so its hash is a number of its own: an object's identity
    * hash is kept by JavaScript in a table of weak keys, which every new object would add to.
    */
  private final class Parked[T](source: Async.Source[T])
      extends Listener[T],
        Cancellable {
    val promise: js.Promise[Any] = Promises.make()
    private val number = { parkedCount += 1; parkedCount }

    override def hashCode(): Int = number

    val lock: Listener.ListenerLock | Null = null

    def complete(item: T, from: Async.Source[T]): Unit = Promises.resolve(promise, item)

    def cancel(): Unit = {
      source.dropListener(this)
      Promises.resolve(promise, Cancelled)
    }
  }

  /** Suspends until `source` has its item, or the performer's group is cancelled, which throws. */
  def parkOn[T](source: Async.Source[T])(using async: Async): T = {
    val group = async.group
    if group.isCancelled then throw new CancellationException()
    val parked = new Parked[T](source)
    val onCancel: Cancellable = parked
    onCancel.link(group)
    val got =
      try {
        source.onComplete(parked)
        js.await(parked.promise)
      } finally onCancel.unlink()
    if got.asInstanceOf[AnyRef] eq Cancelled then throw new CancellationException() else got.asInstanceOf[T]
  }

  /** Suspends until `source` has its item, whatever becomes of the performer's group. */
  def parkUncancelled[T](source: Async.Source[T])(using Async): T = {
    val parked = new Parked[T](source)
    source.onComplete(parked)
    js.await(parked.promise).asInstanceOf[T]
  }
}
