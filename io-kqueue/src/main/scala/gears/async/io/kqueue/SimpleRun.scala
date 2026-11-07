package gears.async.io.kqueue

import gears.async.io.Io
import gears.async.Async.Source
import gears.async.Listener

import scala.concurrent.duration.given

@main def simpleRun(): Unit = {
  given KqueueBackend.type = KqueueBackend

  Io.run { io ?=>
    // summon[gears.async.Async].scheduler.schedule(500.millis, () => println("scheduled"))
    println("here we go...")
    io.perform(io.backend.ops.timer(500))
    println("after timer")
    io.perform(io.backend.ops.timer(500))
    println("done")
  }
}
