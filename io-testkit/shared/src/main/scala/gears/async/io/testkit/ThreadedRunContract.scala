package gears.async.io
package testkit

import gears.async.Async
import gears.async.Future

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*

/** What `RunEndContract` leaves to backends with threads: what a run's end does to a blocking op, what passes between
  * runs that are under way at once, each on a thread of its own, and what a task of the program's scheduler that throws
  * does to the program. For runtimes that can create host threads, as `ThreadedTimerContract` is.
  */
trait ThreadedRunContract extends IpStreamFixture {
  val backend: Backend { type Interface <: Blocking & IpStreams }

  private def seconds(since: Long): Double = (System.nanoTime() - since) / 1e9

  /** Runs `body` as another program over the backend, on a thread of its own, while the caller's goes on: the caller
    * waits for it in a blocking op, off the threads that run its futures.
    */
  private def inAnotherRun[T](io: Io[backend.Interface])(body: Io[backend.Interface] ?=> Async.Spawn ?=> T)(using
      Async
  ): T = {
    @volatile var outcome: scala.util.Try[T] | Null = null
    val other = new Thread(() => outcome = scala.util.Try(running(body)))
    other.start()
    io.perform(io.ops.blocking(() => other.join()))
    outcome.nn.get
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

  test("blocking ops performed with long time limits, met in time, leave nothing for the run's end to wait for") {
    val started = System.nanoTime()
    running { io ?=>
      for _ <- 1 to 3 do io.perform(io.ops.blocking(() => ()), 60.seconds)
    }
    assert(seconds(started) < 5, s"the run took ${seconds(started)} s to end")
  }

  test("an op being performed in one run is refused by another meanwhile, and is the first run's still") {
    running { io ?=>
      val timer = io.ops.timer(500)
      val waiting = Future(io.perform(timer))
      settle(io) // so that it waits
      val refusal = inAnotherRun(io)(theirs ?=> attempt(theirs, timer.asInstanceOf[theirs.Op]))
      assert(refusal.isInstanceOf[IllegalStateException], String.valueOf(refusal))
      waiting.await // undisturbed
    }
  }

  test("a handle is refused by a run other than the one that opened it, while that one still runs") {
    running { io ?=>
      val listener =
        opening[io.ops.Listener](io)(slot => io.ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), slot))
      val address = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress]
      val (client, server) = pair(io, listener)(opening[io.ops.Connection](io)(slot => io.ops.connect(address, slot)))
      try {
        // Each kind of op that uses a handle, built here and performed by another run, on a thread of its own.
        val foreign = List[(String, io.Op)](
          "read" -> io.ops.read(client, allocate(16)),
          "write" -> io.ops.write(server, bytes("y")),
          "accept" -> io.ops.accept(listener, Slot[io.ops.Connection]()),
          "close" -> io.ops.close(client)
        )
        val refusals =
          inAnotherRun(io)(theirs ?=> foreign.map((what, op) => what -> attempt(theirs, op.asInstanceOf[theirs.Op])))
        for (what, refused) <- refusals do assert(refused.isInstanceOf[IllegalArgumentException], s"$what: $refused")
        // And they are still this run's, as they were.
        writeAll(io, server, bytes("mine"))
        val buf = allocate(16)
        readExactly(io, io.ops.read(client, buf), buf, 4)
        assertEquals(text(buf), "mine")
      } finally {
        close(io, client)
        close(io, server)
        close(io, listener)
      }
    }
  }

  test("a task of the scheduler's that throws a plain exception does not cancel the program") {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler((_, _) => ()) // reported there, which is not what is tested here
    try {
      val result = running { io ?=>
        summon[Async].scheduler.execute(() => throw new RuntimeException("expected: a throwing task"))
        io.perform(io.ops.timer(20.millis))
        1
      }
      assertEquals(result, 1)
    } finally Thread.setDefaultUncaughtExceptionHandler(previous)
  }
}
