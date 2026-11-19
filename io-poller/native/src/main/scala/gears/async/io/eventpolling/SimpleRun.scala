package gears.async.io.kqueue

import gears.async.Async
import gears.async.Async.Source
import gears.async.CancellationException
import gears.async.Future
import gears.async.Listener
import gears.async.io.Io
import gears.async.io.Pipes
import gears.async.io.Slot

import java.io.IOException
import scala.concurrent.TimeoutException
import scala.concurrent.duration.given
import scala.scalanative.memory.PointerBuffer
import scala.scalanative.unsafe.Zone

def testProgram(io: Io[Pipes])(using Async.Spawn): Unit =
  println("here we go...")
  try
    io.perform(io.ops.timer(500))
    println(s"after timer observed in thread: ${Thread.currentThread().getName}")
  catch
    case e: CancellationException =>
      println(s"timer cancelled observed in thread: ${Thread.currentThread().getName}")
    case e: TimeoutException => println(s"timer timed out observed in thread: ${Thread.currentThread().getName}")

  val pRead = Slot[io.ops.Readable]()
  val pWrite = Slot[io.ops.Writable]()
  io.perform(io.ops.pipe(pRead, pWrite))
  val read = pRead.clear()
  val write = pWrite.clear()
  println(
    s"pipe created with read: $read, write: $write in thread: ${Thread.currentThread().getName}"
  )
  val zone = Zone.open()
  try {
    val message = "Hello, pipe!"
    val messageBytes = message.getBytes("UTF-8")

    val fut = Future {
      val ptr = zone.alloc(1024)
      val buffer = PointerBuffer.wrap(ptr, 1024)
      try
        io.perform(io.ops.read(read, buffer))
        println(s"read succeeded observed in thread: ${Thread.currentThread().getName}")
        buffer.flip()
        val v = java.nio.charset.StandardCharsets.UTF_8.decode(buffer).toString()
        println(s"read value: `${v}` of ${v.length} bytes observed in thread: ${Thread.currentThread().getName}")
      catch
        case e: IOException =>
          println(s"read failed observed in thread: ${Thread.currentThread().getName}. ${e.getMessage}")
      io.perform(io.ops.close(read))
      println(s"pipe-read closed in thread: ${Thread.currentThread().getName}")
    }

    // part 2

    val fut2 = Future {
      val ptr2 = zone.alloc(1024)
      val buffer2 = PointerBuffer.wrap(ptr2, messageBytes.length)
      buffer2.put(messageBytes)
      buffer2.flip()
      try
        io.perform(io.ops.write(write, buffer2))
        println(s"write succeeded observed in thread: ${Thread.currentThread().getName}")
      catch
        case e: IOException =>
          println(s"write failed observed in thread: ${Thread.currentThread().getName}. ${e.getMessage}")
      io.perform(io.ops.close(write))
      println(s"pipe-write closed in thread: ${Thread.currentThread().getName}")
    }

    fut.await
    fut2.await

  } finally zone.close()

  // io.perform(io.ops.timer(500))
  // println("done")

@main def simpleRun(): Unit = {
  given KqueueBackend.type = KqueueBackend
  Io.run { io ?=>
    testProgram(io)
  }
}
