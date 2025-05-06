package gears.async.js

import gears.async.*

import scala.compiletime.uninitialized
import scala.scalajs.js
import scala.scalajs.js.wasm.JSPI.allowOrphanJSAwait

/** An opaque, compile-time token to signal that we are under a [[js.async]] scope. */
opaque type AsyncToken = Unit

object AsyncToken:
  /** Assumes that we are under an `async` scope. */
  def unsafeAssumed: AsyncToken = ()

/** Capability-safe wrapper around [[JSPI.async]]. */
private[async] inline def async[T](body: AsyncToken ?=> T): js.Promise[T] = JSPI.async(body(using ()))

/** An implementation of [[SuspendSupport]] using JSPI async/await under WebAssembly.
  * @note
  *   this assumes that the root context is **already** under `JSPI.async`.
  */
trait WasmJSPISuspend(using AsyncToken) extends SuspendSupport:
  /** The label stores a Promise that should be resolved every time the context is suspended or is completed. Since
    * Promises are one-time resolvables, every resumption will "reset" the label, giving it a new Promise (see
    * [[WasmLabel.reset]]).
    *
    * Due to the promise possibly changing over time, within [[boundary]], we have to dynamically resolve the reference
    * _after_ running the `body`.
    */
  protected class WasmLabel[T]():
    var (promise, resolve) = mkPromise[T]

    def reset() =
      var (p, q) = mkPromise[T]
      promise = p
      resolve = q

  /** Creates a new [[js.Promise]] and returns both the Promise and its `resolve` function. */
  inline def mkPromise[T]: (js.Promise[T], T => Any) =
    var resolve: (T => Any) | Null = null
    val promise = js.Promise[T]((res, rej) => resolve = res)
    (promise, resolve)

  protected class WasmSuspension[-T, +R](label: Label[R], resolve: T => Any) extends gears.async.Suspension[T, R]:
    def resume(arg: T): R =
      label.reset()
      resolve(arg)
      JSPI.await(label.promise)

  // Implementation of the [[SuspendSupport]] interface.

  opaque type Label[T] = WasmLabel[T]

  opaque type Suspension[-T, +R] <: gears.async.Suspension[T, R] = WasmSuspension[T, R]

  override def boundary[T](body: Label[T] ?=> T): T =
    val label = WasmLabel[T]()
    JSPI.async:
      val r = body(using label)
      label.resolve(r) // see [[WasmLabel]]
    JSPI.await(label.promise) // this is fine to resolve immediately, since we only wait for the first return.

  /** Suspends the context by creating a [[js.Promise]] to wait for inside the [[Suspension]] class, that would be
    * resolved once resumed.
    * @note
    *   Should return immediately if resume is called from within body
    */
  override def suspend[T, R](body: Suspension[T, R] => R)(using label: Label[R]): T =
    val (suspPromise, suspResolve) = mkPromise[T]
    val suspend = WasmSuspension[T, R](label, suspResolve)
    label.resolve(body(suspend))
    JSPI.await(suspPromise)
end WasmJSPISuspend

/** Overrides [[AsyncOperations]] with JavaScript-specific operations. */
object JsAsyncOperations extends AsyncOperations:
  override def `yield`()(using Async) =
    sleep(1)

/** An implementaion of [[Scheduler]] that assumes a single-threaded, event-loop driven JavaScript context.
  *
  * In this context, `execute` will always immediately run (while under a [[js.async]] scope, so that suspension is
  * possible), while `schedule`d computations only run when another computation has yielded.
  */
object JsAsyncScheduler extends Scheduler:
  def execute(body: Runnable) = JSPI.async(body.run())
  def schedule(delay: scala.concurrent.duration.FiniteDuration, body: Runnable) =
    new Cancellable:
      val handle = js.timers.setTimeout(delay)(body.run())
      def cancel() =
        js.timers.clearTimeout(handle)

/** An implementation of [[AsyncSupport]] where we assume a JSPI-enabled WebAssembly environment under a
  * single-threaded, event-loop driven JavaScript scheduler (as assumed by [[JsAsyncScheduler]]).
  */
final class WasmAsyncSupport(using AsyncToken) extends AsyncSupport with WasmJSPISuspend:
  type Scheduler = JsAsyncScheduler.type

/** A special root-level implementation of the [[Async]] context, that uses JSPI async/await on top-level to wait for
  * futures.
  */
private[async] class JsAsync(val group: CompletionGroup)(using support: WasmAsyncSupport, sched: JsAsyncScheduler.type)
    extends Async(using support, sched):
  override def await[T](src: Async.Source[T]) =
    src
      .poll()
      .getOrElse:
        JSPI.await: // TODO: can we make this more efficient?
          js.Promise: (resolve, _) =>
            src.onComplete:
              Listener: (item, _) =>
                resolve(item)
  def withGroup(group: CompletionGroup) = JsAsync(group)

/** An implementation of [[Async.FromSync]] that returns a [[scala.concurrent.Future]] for a top-level
  * [[Async.blocking]] computation.
  */
object JsAsyncFromSync extends Async.FromSync:
  type Output[+T] = scala.concurrent.Future[T]
  def apply[T](body: Async ?=> T): Output[T] =
    async:
      given WasmAsyncSupport = WasmAsyncSupport()
      given JsAsyncScheduler.type = JsAsyncScheduler
      Async.group(body)(using JsAsync(CompletionGroup.Unlinked))
    .toFuture

/** Alternative [[Async.FromAsync]] implementation. **Assumes** that we are under an `async` scope.
  */
object UnsafeJsAsyncFromSync extends Async.FromSync:
  type Output[+T] = T
  def apply[T](body: Async ?=> T): Output[T] =
    given WasmAsyncSupport = WasmAsyncSupport(using AsyncToken.unsafeAssumed)
    given JsAsyncScheduler.type = JsAsyncScheduler
    Async.group(body)(using JsAsync(CompletionGroup.Unlinked))
