package example

import gears.async.io.internal.Completion
import gears.async.io.kqueue.KqueueReactor
import gears.async.io.kqueue.KqueueTimer

/** A five-second one-shot timer, with a one-second ticker running alongside. Both are `KqueueTimer` ops. */
object KQueueExampleTimer {
  def run(): Unit = {
    KqueueReactor.scoped(maxEvents = 1) { r =>
      println("Creating and registering timer event...")
      val tick = new KqueueTimer(milliseconds = 1000)
      r.submit(
        new KqueueTimer(milliseconds = 5000),
        timer => {
          println(s"Event triggered: the ${timer.milliseconds} ms timer")
          println("timer fired")
          r.cancel(tick)
          r.stop() // Exit after handling the event
        }
      )
      println("Event registered successfully. Waiting for it to trigger...")
      println("calling kevent to wait for events...")
      var seconds = 0
      lazy val ticker: Completion[KqueueTimer] = _ => {
        seconds += 1
        println(s"waited for $seconds seconds")
        r.submit(tick, ticker)
      }
      r.submit(tick, ticker)
      KQueueExampleIO.runFailingFast(r)
    }
  }
}
