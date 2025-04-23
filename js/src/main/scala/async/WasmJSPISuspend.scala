package gears.async.js

import gears.async.*

import scala.compiletime.uninitialized
import scala.scalajs.js
import scala.scalajs.js.wasm.JSPI.allowOrphanJSAwait

opaque type AsyncToken = Unit

/** Capability-safe wrapper around [[js.async]]. */
private[async] inline def async[T](body: AsyncToken ?=> T): js.Promise[T] = js.async(body(using ()))

/** Note that this assumes that the root context is **already** under `js.async`. */
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

  def boundary[T](body: Label[T] ?=> T): T =
    val label = WasmLabel[T]()
    js.async:
      label.resolve(body(using label))
    js.await(label.promise)

  protected class WasmSuspension[-T, +R](label: Label[R], resolve: T => Any) extends gears.async.Suspension[T, R]:
    def resume(arg: T): R =
      label.reset()
      resolve(arg)
      js.await(label.promise)

  opaque type Suspension[-T, +R] <: gears.async.Suspension[T, R] = WasmSuspension[T, R]

  /** Should return immediately if resume is called from within body */
  def suspend[T, R](body: Suspension[T, R] => R)(using label: Label[R]): T =
    val (suspPromise, suspResolve) = mkPromise[T]
    val suspend = WasmSuspension[T, R](label, suspResolve)
    label.resolve(body(suspend))
    js.await(suspPromise)

object JsAsyncOperations extends AsyncOperations:
  def sleep(millis: Long)(using Async) =
    import scala.concurrent.duration.*
    Future
      .withResolver: resolver =>
        val handle = js.timers.setTimeout(millis.millis)(resolver.resolve(()))
        resolver.onCancel(() => js.timers.clearTimeout(handle))
      .await

object JsAsyncScheduler extends Scheduler:
  def execute(body: Runnable) = js.async(body)
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
        js.await:
          js.Promise: (resolve, _) =>
            src.onComplete:
              Listener: (item, _) =>
                resolve(item)
  def withGroup(group: CompletionGroup) = JsAsync(group)

object JsAsyncFromSync extends Async.FromSync:
  type Output[+T] = js.Promise[T]
  def apply[T](body: Async ?=> T): Output[T] =
    async:
      given WasmAsyncSupport = WasmAsyncSupport()
      given JsAsyncScheduler.type = JsAsyncScheduler
      body(using JsAsync(CompletionGroup.Unlinked))
