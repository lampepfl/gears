package gears.async.io
package internal

import scala.util.Try

import _root_.gears.async.Async
import _root_.gears.async.AsyncOperations
import _root_.gears.async.Cancellable

/** An `Io` on a pool of workers driving a reactor backend's reactors: an op is submitted to the reactor of the worker
  * running the caller, which suspends until the reactor finishes with it. Cancelling the caller asks the reactor to
  * cancel the op, and waits until it has. Ops in flight together are futures performing them
  * (`Future(io.perform(op))`), each cancelled with its future. The pool itself is the runtime's, which implements this:
  * it says only what a program may do on the pool; `yield` is its alone.
  */
trait OnPool[+B <: ReactorBackend] extends Io[B] {

  /** Gives up the caller's turn the pool's way: its resume queued behind the tasks already queued on its worker, so the
    * futures sharing the worker run first, as a long computation should between slices.
    */
  override def `yield`()(using Async): Unit
}

/** An `Io` on the calling thread, which may block: a caller backend's ops performed by the caller, so it may be made by
  * the runner, or anyone, and used from any thread.
  */
final class OnCaller[+B <: CallerBackend](val backend: B) extends Io[B] {
  private[io] def performWithin(op: Op, timeoutMillis: Int)(using Async): Unit = backend.performHere(op, timeoutMillis)

  /** Gives up the caller's turn behind the threads already woken, the wake-ups of ops among them, where the platform
    * has a way to (`Runner.yieldHere`), else as gears does (docs/io/jvm.md, "Starvation and lateness").
    */
  override def `yield`()(using Async): Unit = if !Runner.yieldHere() then super.`yield`()
}
