package gears.async.io.kqueue

import gears.async.Async.Source
import gears.async.CancellationException
import gears.async.Listener
import gears.async.io.Io

import scala.concurrent.TimeoutException
import scala.concurrent.duration.given

@main def simpleRun(): Unit = {
  given KqueueBackend.type = KqueueBackend

  Io.run { io ?=>
    println("here we go...")
    try
      io.perform(io.backend.ops.timer(500))
      println(s"after timer observed in thread: ${Thread.currentThread().getName}")
    catch
      case e: CancellationException => println(s"timer cancelled observed in thread: ${Thread.currentThread().getName}")
      case e: TimeoutException      => println(s"timer timed out observed in thread: ${Thread.currentThread().getName}")
    // io.perform(io.backend.ops.timer(500))
    // println("done")
  }
}
