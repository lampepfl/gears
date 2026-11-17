package gears.async.io
package nio

import gears.async.Async
import gears.async.Future

import scala.concurrent.duration.*

/** Where each of the two backends runs a program's futures, and that both wake them from their workers. */
class NioSchedulingSuite extends munit.FunSuite {
  override def munitTimeout: Duration = 60.seconds

  private def carrierName(): String = VirtualCarriers.currentCarrier().getName()

  private def isWorker(name: String): Boolean = name.startsWith("WorkpoolSchedulerWorker-")

  test("on the JDK's threads a future is a virtual thread that no worker carries, before and after it waits") {
    given NioBackend.type = NioBackend
    Io.run { io ?=>
      val seen = Future {
        assert(Thread.currentThread().isVirtual())
        val before = carrierName()
        io.perform(io.ops.timer(5))
        (before, carrierName())
      }.await
      assert(!isWorker(seen._1), seen)
      assert(!isWorker(seen._2), seen)
    }
  }

  test("on the pool a future is a virtual thread that a worker carries, before and after it waits") {
    given NioPoolBackend.type = NioPoolBackend
    Io.run { io ?=>
      val seen = Future {
        assert(Thread.currentThread().isVirtual())
        val before = carrierName()
        io.perform(io.ops.timer(5))
        (before, carrierName())
      }.await
      assert(isWorker(seen._1), seen)
      assert(isWorker(seen._2), seen)
    }
  }

  test("on the pool a future woken by a worker's selector goes on on that worker") {
    given NioPoolBackend.type = NioPoolBackend
    NioPoolBackend.runOn(4, shareable.Starvation.printWarning) { io ?=>
      val ops = io.ops
      val read = Slot[ops.Readable]()
      val write = Slot[ops.Writable]()
      io.perform(ops.pipe(read, write))
      val (in, out) = (read.clear(), write.clear())
      val reading = Future {
        val buf = java.nio.ByteBuffer.allocateDirect(8)
        val waitedOn = carrierName() // the worker that listens for the pipe from here on
        val woken = for _ <- 1 to 20 yield {
          buf.clear()
          io.perform(ops.read(in, buf))
          carrierName()
        }
        (waitedOn, woken.toSet)
      }
      val buf = java.nio.ByteBuffer.allocateDirect(1)
      for _ <- 1 to 20 do
        io.perform(ops.timer(2)) // so that the read waits each time
        buf.clear()
        io.perform(ops.write(out, buf))
      val (waitedOn, woken) = reading.await
      assertEquals(woken, Set(waitedOn))
      io.perform(ops.close(in))
      io.perform(ops.close(out))
    }
  }

  test("on the pool, futures that only compute and yield share the workers with those that wait") {
    given NioPoolBackend.type = NioPoolBackend
    NioPoolBackend.runOn(2, shareable.Starvation.printWarning) { io ?=>
      @volatile var stop = false
      val spinning = (1 to 4).map(_ => Future { while !stop do io.`yield`() })
      val started = System.nanoTime()
      for _ <- 1 to 20 do io.perform(io.ops.timer(1))
      val took = (System.nanoTime() - started) / 1_000_000L
      stop = true
      spinning.foreach(_.await)
      assert(took < 2000, s"20 timers of 1 ms took $took ms beside futures that yield")
    }
  }
}
