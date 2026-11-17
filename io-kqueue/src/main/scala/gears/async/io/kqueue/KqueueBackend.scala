package gears.async.io
package kqueue

import gears.async.Async
import gears.async.AsyncSupport
import gears.async.Future

/** The kqueue backend
  *
  * A program runs on a pool of workers (`KqueueScheduler`), each with its own inbox and heap of timers. An op is
  * performed on the worker of the thread performing it, whose heap holds its deadline, and which resumes the performer
  * once it completes. A read or a write that finds its pipe not ready waits for the kqueue of the worker that listens
  * for its handle. So far there are timers and pipes.
  */
object KqueueBackend extends Backend {
  type Interface = KqueueOps.type

  def pool(bufferSize: Int, buffers: Int): BufferPool = new NativePool(bufferSize, buffers)

  /** Runs `program` on a pool of one worker for each processor, shutting it down afterwards. The program runs under the
    * pool's group: a fatal error in a worker cancels it, and is thrown from here once it has unwound.
    */
  private[io] def run[T](program: Io[Interface] ?=> Async.Spawn ?=> T): T = {
    val pool = KqueueScheduler(Runtime.getRuntime.availableProcessors)
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
