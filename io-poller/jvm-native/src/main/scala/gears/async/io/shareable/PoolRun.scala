package gears.async.io.shareable

import gears.async.Async
import gears.async.AsyncSupport
import gears.async.io.Interface
import gears.async.io.Io
import gears.async.io.shareable.WorkpoolScheduler.AsyncOp
import gears.async.io.shareable.WorkpoolScheduler.ReentrantOp

/** How a pool backend runs a program, where there are threads to run it on and one to wait for it: not on Scala.js,
  * which is why it is apart from the pool itself.
  */
object PoolRun {

  /** Runs `program` on `pool`, as its backend's `run` does, and shuts the pool down afterwards. The program runs as a
    * future of the pool's, under the pool's group: a fatal error in a worker cancels it, and is thrown from here once
    * it has unwound. The caller's thread waits, and runs nothing of the program.
    */
  def apply[I <: Interface { type Op <: AsyncOp & ReentrantOp }, T](pool: WorkpoolScheduler[I])(
      program: Io[I] ?=> Async.Spawn ?=> T
  ): T = {
    given poolSupport: (AsyncSupport { type Scheduler = pool.Scheduler }) = pool
    given poolScheduler: pool.Scheduler = pool
    try
      val outcome = Async.blocking({
        Async.withNewCompletionGroup(pool.rootGroup)(gears.async.Future(program(using pool)).awaitResult)
      })
      val fatal = pool.fatalFailure
      if fatal != null then throw fatal
      outcome.get
    finally pool.shutdown()
  }
}
