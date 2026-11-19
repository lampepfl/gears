package gears.async.io
package node

import gears.async.Async
import gears.async.js.UnsafeJsAsyncFromSync

import scala.scalajs.js

/** The Node.js backend: the interface on Node's own I/O, for a Scala.js program linked to WebAssembly and run by
  * Node.js, which suspends with JSPI.
  *
  * A program runs on Node's one thread, its event loop, as gears' futures do on Scala.js (`NodeLoop`), each run with handles of its own (`NodeRun`). An op is
  * performed by the future performing it: its work is tried at once, which most often does it, and if Node has not what
  * completes it the op waits, as its handle's waiter, on the heap of timers, or for a call of Node's to call back,
  * while the future is suspended. Nothing blocks, so that every future, timer and socket goes on while one waits; and
  * since there is one thread, what a program does between two suspensions no other future sees half done.
  *
  * A run suspends its caller, so `Io.run` over this backend must be called where JSPI can suspend: within Scala.js's
  * `js.async`, as a gears program is on Scala.js. `start` does both, for a program started from JavaScript's side, a
  * main method or a callback.
  */
object NodeBackend extends Backend {
  type Interface = NodeOps.type

  def pool(bufferSize: Int, buffers: Int): BufferPool = new NodePool(bufferSize, buffers)

  /** Runs `program` on the event loop, where its caller is, which is suspended until it returns. */
  private[io] def run[T](program: Io[Interface] ?=> Async.Spawn ?=> T): T = {
    val run = new NodeRun
    // By the time this returns the program's futures have been cancelled and have ended, so nothing of its own is
    // under way on what it left open.
    try Async.fromSync(using UnsafeJsAsyncFromSync)(program(using run))
    finally run.end()
  }

  /** Starts `program` as `Io.run` runs it, from where nothing can be suspended, and returns at once: the promise is
    * resolved with what the program returns, or rejected with its failure.
    */
  def start[T](program: Io[Interface] ?=> Async.Spawn ?=> T): js.Promise[T] = js.async(run(program))
}
