package gears.async.js

import gears.async.*

import scala.compiletime.uninitialized
import scala.scalajs.js
import scala.scalajs.js.wasm.JSPI.allowOrphanJSAwait

opaque type AsyncToken = Unit

object AsyncToken:
  /** Assumes that we are under an `async` scope. */
  def unsafeAssumed: AsyncToken = ()

/** Capability-safe wrapper around [[JSPI.async]]. */
private[async] inline def async[T](body: AsyncToken ?=> T): js.Promise[T] = JSPI.async(body(using ()))

/** Note that this assumes that the root context is **already** under `JSPI.async`. */
trait WasmJSPISuspend(using AsyncToken) extends SuspendSupport:
  protected class WasmLabel[T]():
    var (promise, resolve) = mkPromise[T]

    def reset() =
      var (p, q) = mkPromise[T]
      promise = p
      resolve = q

  inline def mkPromise[T]: (js.Promise[T], T => Any) =
    var resolve: (T => Any) | Null = null
    val promise = js.Promise[T]((res, rej) => resolve = res)
    (promise, resolve)

  opaque type Label[T] = WasmLabel[T]

  override def boundary[T](body: Label[T] ?=> T): T =
    val label = WasmLabel[T]()
    JSPI.async:
      val r = body(using label)
      label.resolve(r)
    JSPI.await(label.promise)

  protected class WasmSuspension[-T, +R](label: Label[R], resolve: T => Any) extends gears.async.Suspension[T, R]:
    def resume(arg: T): R =
      label.reset()
      resolve(arg)
      JSPI.await(label.promise)

  opaque type Suspension[-T, +R] <: gears.async.Suspension[T, R] = WasmSuspension[T, R]

  /** Should return immediately if resume is called from within body */
  override def suspend[T, R](body: Suspension[T, R] => R)(using label: Label[R]): T =
    val (suspPromise, suspResolve) = mkPromise[T]
    val suspend = WasmSuspension[T, R](label, suspResolve)
    label.resolve(body(suspend))
    JSPI.await(suspPromise)

object JsAsyncOperations extends AsyncOperations:
  override def `yield`()(using Async) =
    sleep(1)

object JsAsyncScheduler extends Scheduler:
  def execute(body: Runnable) = JSPI.async(body.run())
  def schedule(delay: scala.concurrent.duration.FiniteDuration, body: Runnable) =
    new Cancellable:
      val handle = js.timers.setTimeout(delay)(body.run())
      def cancel() =
        js.timers.clearTimeout(handle)

final class WasmAsyncSupport(using AsyncToken) extends AsyncSupport with WasmJSPISuspend:
  type Scheduler = JsAsyncScheduler.type

private[async] class JsAsync(val group: CompletionGroup)(using support: WasmAsyncSupport, sched: JsAsyncScheduler.type)
    extends Async(using support, sched):
  override def await[T](src: Async.Source[T]) =
    src
      .poll()
      .getOrElse:
        JSPI.await:
          js.Promise: (resolve, _) =>
            src.onComplete:
              Listener: (item, _) =>
                resolve(item)
  def withGroup(group: CompletionGroup) = JsAsync(group)

object JsAsyncFromSync extends Async.FromSync:
  type Output[+T] = scala.concurrent.Future[T]
  def apply[T](body: Async ?=> T): Output[T] =
    async:
      given WasmAsyncSupport = WasmAsyncSupport()
      given JsAsyncScheduler.type = JsAsyncScheduler
      Async.group(body)(using JsAsync(CompletionGroup.Unlinked))
    .toFuture

/** Alternative [[Async.FromAsync]] implementation. Assumes** that we are under an `async` scope.
  */
object UnsafeJsAsyncFromSync extends Async.FromSync:
  type Output[+T] = T
  def apply[T](body: Async ?=> T): Output[T] =
    given WasmAsyncSupport = WasmAsyncSupport(using AsyncToken.unsafeAssumed)
    given JsAsyncScheduler.type = JsAsyncScheduler
    Async.group(body)(using JsAsync(CompletionGroup.Unlinked))
