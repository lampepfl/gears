package gears.io.demos

import gears.async.Async
import gears.async.Future
import gears.async.io.Backend
import gears.async.io.Io

/** Mirror of `example.KQueueExampleTimer`: a five-second one-shot timer, with a one-second ticker running alongside. */
object GearsTimer {
  def run()(using io: Io[Backend], async: Async.Spawn): Unit = {
    println("Creating and registering timer event...")
    val fired = Future(io.perform(io.backend.ops.timer(5000)))
    println("Event registered successfully. Waiting for it to trigger...")
    println("calling kevent to wait for events...")
    val ticker = Future {
      var seconds = 0
      val timer = io.backend.ops.timer(1000)
      while true do {
        io.perform(timer)
        seconds += 1
        println(s"waited for $seconds seconds")
      }
    }
    fired.await
    println("timer fired")
    ticker.cancel() // Also cancels its pending one-second timer in the reactor.
  }
}
