package gears.async.io
package kqueue

import gears.async.Future
import gears.async.io.testkit.*

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*

/** What a run leaves when its program returns: the ops its futures still had under way are cancelled, at once, and the
  * handles it opened and did not close are closed; a blocking op is interrupted, and the run waits for its body to
  * return; and a time limit met in time leaves nothing to wait for. For runtimes that can create host threads, as
  * `ThreadedTimerContract` is.
  */
trait RunEndContract extends PipeFixture {
  val backend: Backend { type Interface <: Blocking & Pipes }

  private def seconds(since: Long): Double = (System.nanoTime() - since) / 1e9

  test(
    "a run that returns with a read and a long timer pending cancels them at once, and closes what it left open, which no other run may use"
  ) {
    val started = System.nanoTime()
    var left: AnyRef | Null = null
    running { io ?=>
      val ends = pipe(io)
      Future(io.perform(io.ops.read(ends._1, allocate(16))))
      Future(io.perform(io.ops.timer(60_000)))
      settle(io)
      left = ends // never closed, and carried out of the run
    }
    assert(seconds(started) < 5, s"the run took ${seconds(started)} s to end")
    val (leftIn, leftOut) = left.asInstanceOf[(shareable.BaseHandle, shareable.BaseHandle)]
    assertEquals(leftIn.getState, shareable.HandleState.ClosedState)
    assertEquals(leftOut.getState, shareable.HandleState.ClosedState)
    // A handle is its run's: brought into another, here by a cast, as the suite knows only the interface, it is refused.
    running { io ?=>
      val (in, out) = left.asInstanceOf[(io.ops.Readable, io.ops.Writable)]
      val reading = attempt(io, io.ops.read(in, allocate(16)))
      assert(reading.isInstanceOf[IllegalArgumentException], String.valueOf(reading))
      val writing = attempt(io, io.ops.write(out, bytes("y")))
      assert(writing.isInstanceOf[IllegalArgumentException], String.valueOf(writing))
      val closing = attempt(io, io.ops.close(in))
      assert(closing.isInstanceOf[IllegalArgumentException], String.valueOf(closing))
    }
  }

  test("a run that returns while a blocking op runs interrupts it, and ends once its body has returned") {
    val entered = new CountDownLatch(1)
    val returned = new AtomicBoolean
    val started = System.nanoTime()
    running { io ?=>
      Future(
        io.perform(
          io.ops.blocking { () =>
            entered.countDown()
            try Thread.sleep(10_000)
            catch case _: InterruptedException => ()
            returned.set(true)
          },
          60.seconds
        )
      )
      while entered.getCount() > 0 do io.perform(io.ops.timer(5))
    }
    assert(returned.get(), "the run ended before the blocking op's body returned")
    assert(seconds(started) < 5, s"the run took ${seconds(started)} s to end: the body was not interrupted")
  }

  test("ops performed with long time limits, met in time, leave nothing for the run's end to wait for") {
    val started = System.nanoTime()
    running { io ?=>
      for _ <- 1 to 3 do io.perform(io.ops.blocking(() => ()), 60.seconds)
      val (in, out) = pipe(io)
      try {
        writeAll(io, out, bytes("z"))
        val buf = allocate(16)
        io.perform(io.ops.read(in, buf), 60.seconds)
        assertEquals(text(buf), "z")
      } finally {
        close(io, in)
        close(io, out)
      }
    }
    assert(seconds(started) < 5, s"the run took ${seconds(started)} s to end")
  }
}
