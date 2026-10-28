package gears.async.io
package internal

import gears.async.Async
import gears.async.AsyncOperations
import gears.async.Future
import gears.async.Future.awaitAll

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Checks that tasks queued for a worker in particular (`ReactorWorker.sync`: cancelling an op on its reactor, arming
  * or cancelling a timer there) run on that worker's thread, whatever other workers take (see `ReactorPoolSuite`). They
  * call a reactor that only its own thread may use.
  *
  * On a pool of two workers, each round has another thread queue a task for the worker running the program, while the
  * program keeps that worker busy; the program then yields, so the queued task and the program's resume wait for the
  * same round of that worker, and spawns futures behind them while the other worker is idle, so that it takes some.
  * Every queued task must still run on the worker it was queued for.
  */
object InboxCheck {

  /** How the check went: queued tasks run, those run on another worker's thread, and tasks other workers took. */
  final case class Outcome(ran: Long, elsewhere: Long, taken: Long)

  def run(rounds: Int)(using ReactorBackend): Outcome = {
    val ran = new AtomicLong
    val elsewhere = new AtomicLong
    var workers = Array.empty[ReactorWorker]
    ReactorRuns.run(2) { io ?=>
      val pool = summon[Async].scheduler match {
        case pool: ReactorPool => pool
        case other             => throw new IllegalStateException(s"not on a reactor pool: $other")
      }
      workers = pool.workers
      var round = 0
      while round < rounds do {
        val worker = pool.currentWorker
        val queued = new AtomicBoolean
        val poster = new Thread(() => {
          worker.sync {
            ran.incrementAndGet()
            if !worker.isCurrent then elsewhere.incrementAndGet()
          }
          queued.set(true)
        })
        poster.start()
        while !queued.get() do Thread.onSpinWait() // keeps the worker busy, so the task waits for its next round
        poster.join()
        AsyncOperations.`yield`() // resumed in that round, with the queued task beside it
        val spawned = (1 to 3).map(_ => Future(()))
        val until = System.nanoTime() + 1_000_000L
        while System.nanoTime() < until do Thread.onSpinWait() // so the idle worker takes some meanwhile
        spawned.awaitAll
        round += 1
      }
    }
    Outcome(ran.get(), elsewhere.get(), workers.map(_.taken).sum) // read once the workers have stopped
  }
}
