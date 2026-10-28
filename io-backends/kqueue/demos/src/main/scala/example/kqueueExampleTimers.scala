package example

import gears.async.io.internal.Completion
import gears.async.io.kqueue.KqueueReactor
import gears.async.io.kqueue.KqueueTimer

/** Many timers at once, some cancelled: checks that the reactor fires them in deadline order, never fires a cancelled
  * one, and fires all the others.
  */
object KQueueExampleTimers {
  def run(count: Int): Unit = {
    KqueueReactor.scoped() { r =>
      val timers = Array.tabulate(count)(i => new KqueueTimer(milliseconds = (i * 7) % 50))
      val deadlines = new Array[Long](count)
      var lastFired = 0L // the latest deadline fired so far, once one has
      var inOrder = true
      var fired = 0
      var cancelledFired = 0
      for i <- 0 until count do {
        deadlines(i) = System.nanoTime() + timers(i).milliseconds * 1_000_000L
        r.submit(
          timers(i),
          new Completion[KqueueTimer] {
            def onComplete(timer: KqueueTimer): Unit = {
              if i % 3 == 0 then cancelledFired += 1
              // The deadline noted here was taken just before the reactor's own, so it can only be earlier.
              if fired > 0 && deadlines(i) < lastFired - 1_000_000L then {
                if inOrder then
                  println(
                    s"first out of order: timer $i (${timers(i).milliseconds} ms), due " +
                      s"${(lastFired - deadlines(i)) / 1000} us before one already fired, after $fired fired"
                  )
                inOrder = false
              }
              lastFired = math.max(lastFired, deadlines(i))
              fired += 1
              if fired == count - (count + 2) / 3 then r.stop()
            }
          }
        )
      }
      for i <- 0 until count by 3 do r.cancel(timers(i))
      KQueueExampleIO.runFailingFast(r)
      println(s"timers fired: $fired of ${count - (count + 2) / 3}")
      println(s"timers fired in order: $inOrder")
      println(s"cancelled timers fired: $cancelledFired")
    }
  }
}
