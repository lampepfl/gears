package gears.async.io
package node

import gears.async.Future
import gears.async.io.testkit.ContractSuite

import scala.concurrent.duration.*
import scala.scalajs.js

/** The checks of `ThreadedTimerContract`, which moves a deadline from threads of the host's, as they can be made on
  * Node.js: that contract does not run here, since Scala.js has no threads to start. What moves a deadline from outside
  * the program's futures on Node is a callback of the event loop's, so these move it from timers of JavaScript's own,
  * set outside any future, and the deadline timer must take the move as it would another thread's.
  */
class NodeDeadlineSuite extends NodeSuite {
  val backend: Backend = NodeBackend

  test("a deadline timer moved earlier from a callback of the event loop's releases the future waiting on it") {
    running { io ?=>
      val timer = io.ops.deadlineTimer(System.nanoTime() + 60_000_000_000L)
      var moved = false
      js.timers.setTimeout(50) {
        timer.moveTo(System.nanoTime())
        moved = true
      }
      val started = System.nanoTime()
      io.perform(timer) // waits until the move releases it
      assert(moved, "it completed before its deadline was moved")
      assert(System.nanoTime() - started < 10_000_000_000L, "it waited for its first deadline")
    }
  }

  test("coalesced earlier moves observe the latest deadline and can release the timer again later") {
    running { io ?=>
      val start = System.nanoTime()
      val timer = io.ops.deadlineTimer(start + 60_000_000_000L)
      val waiting = Future(io.perform(timer))

      /** A thousand moves in one callback, none of which the event loop sees but the last, then `finalDeadline`. */
      def moveBatch(finalDeadline: () => Long): Unit = {
        var done = false
        js.timers.setTimeout(1) {
          for i <- 1 to 1000 do timer.moveTo(start + 60_000_000_000L - i)
          timer.moveTo(finalDeadline())
          done = true
        }
        while !done do io.perform(io.ops.timer(1))
      }
      moveBatch(() => start + 30_000_000_000L)
      settle(io)
      assert(waiting.poll().isEmpty, "it completed at a deadline it had left")
      moveBatch(() => System.nanoTime() - 1)
      waiting.await
    }
  }

  test("moves from several callbacks preserve the final move after all of them have run") {
    running { io ?=>
      val start = System.nanoTime()
      val timer = io.ops.deadlineTimer(start + 60.seconds.toNanos)
      val waiting = Future(io.perform(timer, 5.seconds))
      var ran = 0
      for mover <- 0 until 4 do
        js.timers.setTimeout(mover.toDouble) {
          for i <- 0 until 1000 do timer.moveTo(start + 60.seconds.toNanos + (mover * 1000 + i) % 2000)
          ran += 1
        }
      while ran < 4 do io.perform(io.ops.timer(1))
      assert(waiting.poll().isEmpty, "a timer completed while every requested deadline was still in the future")
      val finalDeadline = System.nanoTime() + 10.millis.toNanos
      timer.moveTo(finalDeadline)
      waiting.await
      assert(System.nanoTime() - finalDeadline >= 0, "the timer completed at a superseded deadline")
    }
  }

  test("timers that complete at once, performed without end, leave the event loop its turns") {
    running { io ?=>
      var turned = false
      js.timers.setTimeout(0) { turned = true }
      val timer = io.ops.timer(0)
      var performed = 0
      while !turned && performed < 1_000_000 do {
        io.perform(timer)
        performed += 1
      }
      assert(turned, s"$performed timers of no time completed, and no callback of the event loop's ran")
    }
  }
}
