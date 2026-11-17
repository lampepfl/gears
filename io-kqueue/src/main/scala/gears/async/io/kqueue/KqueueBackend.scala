package gears.async.io
package kqueue

import gears.async.Async
import gears.async.AsyncSupport
import gears.async.Future

/** The kqueue backend, for macOS and FreeBSD.
  *
  * A program runs on a pool of workers (`KqueueScheduler`), each with its own inbox, heap of timers and kqueue. An op
  * is performed on the worker of the thread performing it, whose heap holds its time limit. What it then does is its
  * kind's:
  *
  *   - a read, a write, an accept, a connect or a datagram's send or receive is tried at once, and if its handle is not
  *     ready waits for the kqueue of the worker that listens for that handle, which does the op's work and resumes the
  *     performer. A stream allows one read and one write at a time; a listener's accepts and a datagram socket's sends
  *     and receives take turns in a queue (see `HandleReadiness`);
  *   - blocking code, a file's calls and name lookups, which block, are done on a thread of `BlockingPool`'s, and the
  *     op is completed back on the performer's worker (see `WorkOffThread`);
  *   - opening a listener, a datagram socket, a pipe or a FIFO, and shutting a connection down, are done as they are
  *     performed.
  */
object KqueueBackend extends Backend {
  type Interface = KqueueOps.type

  def pool(bufferSize: Int, buffers: Int): BufferPool = new NativePool(bufferSize, buffers)

  /** Runs `program` on a pool of one worker for each processor, shutting it down afterwards. The program runs under the
    * pool's group: a fatal error in a worker cancels it, and is thrown from here once it has unwound.
    */
  private[io] def run[T](program: Io[Interface] ?=> Async.Spawn ?=> T): T =
    runOn(Runtime.getRuntime.availableProcessors, shareable.Starvation.printWarning)(program)

  /** As `run`, on a pool of `workers` workers whose reports of starved workers go to `onStarvation`. */
  private[io] def runOn[T](workers: Int, onStarvation: shareable.Starvation.Handler)(
      program: Io[Interface] ?=> Async.Spawn ?=> T
  ): T = {
    posix.FileLimit.raised: Unit // a shell's limit on open descriptors is far below what a server's sockets need
    val pool = KqueueScheduler(workers, onStarvation)
    given poolSupport: (AsyncSupport { type Scheduler = pool.Scheduler }) = pool
    given poolScheduler: pool.Scheduler = pool
    // `Async.blocking` runs its body on the calling thread.
    try
      val outcome = Async.blocking({
        Async.withNewCompletionGroup(pool.rootGroup)(Future(program(using pool)).awaitResult)
      })
      val fatal = pool.fatalFailure
      if fatal != null then throw fatal
      outcome.get
    finally pool.shutdown()
  }
}
