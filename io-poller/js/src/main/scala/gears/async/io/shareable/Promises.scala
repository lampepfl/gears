package gears.async.io.shareable

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** A module of JavaScript's own, imported as the program is loaded: what it does is one call out of WebAssembly, and
  * none back in, where a promise made from here calls in for what resolves it.
  *
  * Its source is the module's address itself, a `data:` URL, so that there is no file to keep beside whatever links
  * this backend. An address ends at a `?` or a `#`, and a `%` escapes: the source has none of them.
  */
private[io] object PromisesModule {
  final val Address =
    "data:text/javascript," +
      "export function make() { let resolve; const promise = new Promise((r) => { resolve = r; });" +
      " promise.gearsResolve = resolve; return promise; }" +
      " export function resolve(promise, item) { promise.gearsResolve(item); }"
}

@js.native
@JSImport(PromisesModule.Address, JSImport.Namespace)
private[io] object Promises extends js.Object {

  /** A promise that keeps what resolves it. */
  def make(): js.Promise[Any] = js.native

  /** Resolves a promise of `make`'s with `item`; a promise takes its first resolution. */
  def resolve(promise: js.Promise[Any], item: Any): Unit = js.native
}
