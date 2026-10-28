package gears.async.io
package kqueue

import gears.async.io.internal.Completion
import gears.async.io.internal.Interest

import java.io.IOException
import scala.collection.mutable.ArrayBuffer

/** Errors in kqueue itself fail the op they are for, and the loop goes on: here, registering the filter of a command
  * whose descriptor is not open, which kqueue refuses with `EBADF`. Before, the failure escaped `submit` and left the
  * command in its slot, so nothing could wait on that descriptor again, or, when the command waited again from the
  * loop, it escaped `runOnce` and ended the worker.
  */
class LoopErrorSuite extends munit.FunSuite {

  /** A number far above any descriptor the process has open. */
  private val notOpen = 10_000

  private final class Outcomes extends Completion[KqueueOp] {
    val completed = ArrayBuffer.empty[KqueueOp]
    val failed = ArrayBuffer.empty[Throwable]
    def onComplete(op: KqueueOp): Unit = completed += op
    override def onFailure(op: KqueueOp, failure: Throwable): Unit = failed += failure
  }

  test("a filter kqueue refuses to register fails its command alone, which may wait on the descriptor again") {
    KqueueReactor.scoped() { r =>
      val outcomes = new Outcomes
      for interest <- Seq(Interest.Read, Interest.Write, Interest.Read) do
        r.submit(new Ready(notOpen, interest), outcomes, -1) // no "already pending": the failed one left its slot
      val timer = new KqueueTimer(0)
      r.submit(timer, outcomes, -1)
      var turns = 0
      while (outcomes.failed.size < 3 || outcomes.completed.isEmpty) && turns < 100 do {
        r.runOnce(block = false)
        turns += 1
      }
      assertEquals(outcomes.failed.size, 3, s"failures: ${outcomes.failed}")
      outcomes.failed.foreach(f => assert(f.isInstanceOf[IOException], f))
      assertEquals(outcomes.completed.toList, List(timer), "the loop went on to fire the timer")
    }
  }

  test("submitted to finish at once, the refused registration throws, and the command can be submitted again") {
    KqueueReactor.scoped() { r =>
      val ready = new Ready(notOpen, Interest.Read)
      for _ <- 1 to 2 do intercept[IOException](r.trySubmit(ready, new Outcomes, -1))
    }
  }
}
