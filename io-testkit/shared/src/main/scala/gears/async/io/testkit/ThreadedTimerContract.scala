package gears.async.io
package testkit

import gears.async.AsyncOperations.sleep
import gears.async.Future

import scala.concurrent.duration.*

/** Additional deadline checks for runtimes that support creating host threads. */
trait ThreadedTimerContract extends ContractSuite {

  test("a deadline timer moved earlier from another thread releases the future waiting on it") {
    running { io ?=>
      val timer = io.backend.ops.deadlineTimer(System.nanoTime() + 60_000_000_000L)
      val mover = new Thread(() => {
        Thread.sleep(50)
        timer.moveTo(System.nanoTime())
      })
      val started = System.nanoTime()
      mover.start()
      io.perform(timer) // waits until the move releases it
      mover.join()
      assert(System.nanoTime() - started < 10_000_000_000L, "it waited for its first deadline")
    }
  }

  test("coalesced earlier moves observe the latest deadline and can release the timer again later") {
    running { io ?=>
      val start = System.nanoTime()
      val timer = io.backend.ops.deadlineTimer(start + 60_000_000_000L)
      val waiting = Future(io.perform(timer))
      def moveBatch(finalDeadline: Long): Unit = {
        val mover = new Thread(() => {
          for i <- 1 to 1000 do timer.moveTo(start + 60_000_000_000L - i)
          timer.moveTo(finalDeadline)
        })
        mover.start()
        mover.join(5000)
        assert(!mover.isAlive)
      }
      moveBatch(start + 30_000_000_000L)
      settle(io)
      assert(waiting.poll().isEmpty, "it completed at a deadline it had left")
      moveBatch(System.nanoTime() - 1)
      waiting.await
    }
  }

  test("concurrent deadline moves preserve the final move after all movers have joined") {
    running { io ?=>
      val start = System.nanoTime()
      val timer = io.backend.ops.deadlineTimer(start + 60.seconds.toNanos)
      val waiting = Future(io.perform(timer, 5.seconds))
      val movers = List.tabulate(4) { thread =>
        new Thread(() => {
          for i <- 0 until 1000 do timer.moveTo(start + 60.seconds.toNanos + (thread * 1000 + i) % 2000)
        })
      }
      movers.foreach(_.start())
      movers.foreach(_.join(5000))
      assert(movers.forall(!_.isAlive))
      assert(waiting.poll().isEmpty, "a timer completed while every requested deadline was still in the future")
      val finalDeadline = System.nanoTime() + 10.millis.toNanos
      timer.moveTo(finalDeadline)
      waiting.await
      assert(System.nanoTime() - finalDeadline >= 0, "the timer completed at a superseded deadline")
    }
  }
}
