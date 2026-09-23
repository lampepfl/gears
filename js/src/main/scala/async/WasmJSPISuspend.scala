package gears.async.js

import gears.async.*

import scala.compiletime.uninitialized
import scala.scalajs.js
import scala.scalajs.js.wasm.JSPI.allowOrphanJSAwait

import language.experimental.captureChecking
import caps.*

/** An opaque, compile-time token to signal that we are under a [[js.async]] scope. */
class AsyncToken private () extends capabilities.Suspension

object AsyncToken:
  /** Assumes that we are under an `async` scope. */
  def unsafeAssumed: AsyncToken^{} = unsafe.unsafeAssumePure(new AsyncToken())

/** Capability-safe wrapper around [[js.async]]. */
private[async] inline def async[T](inline body: AsyncToken ?=> T): js.Promise[T] = js.async(body(using AsyncToken.unsafeAssumed))

/** An implementation of [[SuspendSupport]] using JSPI async/await under WebAssembly.
  * @note
  *   this assumes that the root context is **already** under `js.async`.
  */
trait WasmJSPISuspend(using token: AsyncToken) extends AsyncSupport:
  this: WasmJSPISuspend^{any.only[capabilities.Suspension]} =>
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
  inline def mkPromise[T]: (js.Promise[T], T -> Any) =
    var resolve: (T -> Any) | Null = null
    val promise = js.Promise[T]((res, rej) => resolve = res)
    (promise, resolve)

  protected class WasmSuspension[-T, +R] private (label: WasmLabel[R], resolve: T => Any) extends gears.async.Suspension[T, R]:
    def resume(arg: T): R =
      label.reset()
      resolve(arg)
      js.await(label.promise)

  protected object WasmSuspension:
    def apply[T, R, B^](label: Label[R, B]^, resolve: T -> Any): WasmSuspension[T, R]^{B} =
      // Safety: labels are hidden from the suspension and cannot be used
      val l = unsafe.unsafeAssumePure(label)
      new WasmSuspension(l, resolve)


  // Implementation of the [[SuspendSupport]] interface.

  type Label[T, B^] = WasmLabel[T]

  type Suspension[-T, +R] = WasmSuspension[T, R]

  override def boundary[T, B^ <: {any.except[Control]}](body: (Label[T, B]^{any.only[Control]}) ?->{B} T): T =
    val label = WasmLabel[T]()
    js.async:
      val r = body(using label)
      label.resolve(r) // see [[WasmLabel]]
    js.await(label.promise) // this is fine to resolve immediately, since we only wait for the first return.

  /** Suspends the context by creating a [[js.Promise]] to wait for inside the [[Suspension]] class, that would be
    * resolved once resumed.
    * @note
    *   Should return immediately if resume is called from within body
    */
  override def suspend[T, R, B^](body: (Suspension[T, R]^{B}) ->{B, any.except[Control]} R)(using label: Label[R, B]^): T =
    val (suspPromise, suspResolve) = mkPromise[T]
    val suspend = WasmSuspension[T, R, B](label, suspResolve)
    label.resolve(body(suspend))
    js.await(suspPromise)

  override private[async] def scheduleBoundary(body: (Label[Unit, {}]^) ?-> Unit)(using s: Scheduler): Unit =
    val label = WasmLabel[Unit]()
    s.execute: () =>
      body(using label)
      label.resolve(())
end WasmJSPISuspend

/** Overrides [[AsyncOperations]] with JavaScript-specific operations. */
object JsAsyncOperations extends AsyncOperations:
  override def `yield`()(using Async^) =
    sleep(1)

/** An implementaion of [[Scheduler]] that assumes a single-threaded, event-loop driven JavaScript context.
  *
  * In this context, `execute` will always immediately run (while under a [[js.async]] scope, so that suspension is
  * possible), while `schedule`d computations only run when another computation has yielded.
  */
object JsAsyncScheduler extends Scheduler:
  def execute(body: Runnable) = js.async(body.run())
  def schedule(delay: scala.concurrent.duration.FiniteDuration, body: Runnable) =
    new Cancellable:
      val handle = js.timers.setTimeout(delay)(body.run())
      def cancel() =
        js.timers.clearTimeout(handle)

/** An implementation of [[AsyncSupport]] where we assume a JSPI-enabled WebAssembly environment under a
  * single-threaded, event-loop driven JavaScript scheduler (as assumed by [[JsAsyncScheduler]]).
  */
final class WasmAsyncSupport(using token: AsyncToken) extends WasmJSPISuspend:
  type Scheduler = JsAsyncScheduler.type

/** A special root-level implementation of the [[Async]] context, that uses JSPI async/await on top-level to wait for
  * futures.
  */
private[async] class JsAsync(val group: CompletionGroup)(using support: WasmAsyncSupport^{any.only[capabilities.Suspension]}, sched: JsAsyncScheduler.type)
    extends Async(using support, sched):
  override def await[T](src: Async.Source[T]^) =
    src
      .poll()
      .getOrElse:
        js.await: // TODO: can we make this more efficient?
          js.Promise: (resolve, _) =>
            src.onComplete:
              Listener: (item, _) =>
                resolve(item)
  def withGroup(group: CompletionGroup) = unsafe.unsafeAssumePure(JsAsync(group))

/** An implementation of [[Async.FromSync]] that returns a [[scala.concurrent.Future]] for a top-level
  * [[Async.blocking]] computation.
  */
object JsAsyncFromSync extends Async.FromSync:
  type Output[+T] = scala.concurrent.Future[T]
  def apply[T](body: Async^ ?=> T): Output[T] =
    async:
      val support = WasmAsyncSupport()
      Async.group(body)(using JsAsync(CompletionGroup.Unlinked)(using support, JsAsyncScheduler))
    .toFuture

/** Alternative [[Async.FromAsync]] implementation. **Assumes** that we are under an `async` scope.
  */
object UnsafeJsAsyncFromSync extends Async.FromSync:
  type Output[+T] = T
  def apply[T](body: Async^ ?=> T): Output[T] =
    given WasmAsyncSupport = WasmAsyncSupport(using AsyncToken.unsafeAssumed)
    given JsAsyncScheduler.type = JsAsyncScheduler
    Async.group(body)(using JsAsync(CompletionGroup.Unlinked))
