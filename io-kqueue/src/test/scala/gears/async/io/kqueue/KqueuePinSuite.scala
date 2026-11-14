package gears.async.io
package kqueue

import gears.async.Async
import gears.async.AsyncOperations
import gears.async.AsyncSupport
import gears.async.Future

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/** A future inside `pinned` runs on the worker it was on as it entered and no other, whatever resumes it. */
class KqueuePinSuite extends munit.FunSuite {
  override def munitTimeout: Duration = 60.seconds

  private def onPool[T](workers: Int)(program: KqueueScheduler => Async.Spawn ?=> T): T = {
    val pool = KqueueScheduler(workers)
    given poolSupport: (AsyncSupport { type Scheduler = pool.Scheduler }) = pool
    given poolScheduler: pool.Scheduler = pool
    try Async.blocking(Future(program(pool)).await)
    finally pool.shutdown()
  }

  private def pipe(io: KqueueScheduler)(using Async): (KqueueReadable, KqueueWritable) = {
    val read = Slot[KqueueReadable]()
    val write = Slot[KqueueWritable]()
    io.perform(KqueueOps.pipe(read, write))
    (read.clear(), write.clear())
  }

  private def thread(): String = Thread.currentThread().getName()

  private def spin(millis: Long): Unit = {
    val end = System.nanoTime() + millis * 1_000_000L
    while System.nanoTime() < end do Thread.onSpinWait()
  }

  test("a pinned future stays on one worker through reads that wait, timers and futures it awaits") {
    onPool(4) { io =>
      val (in, out) = pipe(io)
      given AsyncOperations = io
      // A group inside the scope, for the futures it spawns: the scope holds through it.
      val elsewhere = io.pinned { Async.group {
        val home = thread()
        var strayed = List.empty[String]
        def check(after: String): Unit = if thread() != home then strayed ::= after
        var i = 0
        while i < 50 do
          val writer = Future { spin(1); io.perform(KqueueOps.write(out, ByteBuffer.wrap(Array[Byte](1)))) }
          io.perform(KqueueOps.read(in, ByteBuffer.allocate(1)))
          check("a read that waited")
          writer.await
          check("an awaited future")
          io.perform(KqueueOps.timer(1))
          check("a timer")
          AsyncOperations.sleep(1)
          check("a sleep")
          // Several at once, so that the worker has futures to spare and others come for them.
          List.fill(4)(Future(spin(1))).foreach(_.await)
          check("futures others took")
          i += 1
        strayed
      } }
      assertEquals(elsewhere, Nil)
      io.perform(KqueueOps.close(in))
      io.perform(KqueueOps.close(out))
    }
  }

  test("pinned futures each keep to one worker while they work between waits") {
    onPool(4) { io =>
      val pipes = List.fill(8)(pipe(io))
      val readers = pipes.map { (in, out) =>
        Future {
          io.pinned {
            var threads = Set.empty[String]
            val buf = ByteBuffer.allocate(1)
            var i = 0
            while i < 20 do
              buf.clear()
              io.perform(KqueueOps.read(in, buf))
              threads += thread()
              spin(1) // work that an unpinned future would be given away for
              threads += thread()
              i += 1
            threads
          }
        }
      }
      val writers = pipes.map { (in, out) =>
        Future {
          var i = 0
          while i < 20 do
            io.perform(KqueueOps.timer(1))
            io.perform(KqueueOps.write(out, ByteBuffer.wrap(Array[Byte](1))))
            i += 1
        }
      }
      writers.foreach(_.await)
      readers.foreach(reader => assertEquals(reader.await.size, 1))
      pipes.foreach { (in, out) =>
        io.perform(KqueueOps.close(in))
        io.perform(KqueueOps.close(out))
      }
    }
  }

  test("a pinned future whose handle another worker listens for is still resumed on its own") {
    onPool(4) { io =>
      val (in, out) = pipe(io)
      val listener = new AtomicReference[String]("")
      val first = Future {
        listener.set(thread())
        io.perform(KqueueOps.read(in, ByteBuffer.allocate(1)))
      }
      io.sleep(50) // it waits: its worker listens for `in` from now on
      io.perform(KqueueOps.write(out, ByteBuffer.wrap(Array[Byte](1))))
      first.await
      // One at a time, on whichever workers they land: each waits on `in`, which the first one's worker reports.
      var strayed = 0
      var i = 0
      while i < 32 do
        List.fill(4)(Future(spin(1))).foreach(_.await) // so that workers come for futures, and readers land on them
        val reader = Future {
          spin(1)
          io.pinned {
            val home = thread()
            io.perform(KqueueOps.read(in, ByteBuffer.allocate(1)))
            (home, thread())
          }
        }
        io.sleep(2)
        io.perform(KqueueOps.write(out, ByteBuffer.wrap(Array[Byte](1))))
        val (home, resumed) = reader.await
        if resumed != home then strayed += 1
        i += 1
      assertEquals(strayed, 0)
      io.perform(KqueueOps.close(in))
      io.perform(KqueueOps.close(out))
    }
  }

  test("a scope inside another stays on the same worker, and what the body returns or throws comes out") {
    onPool(4) { io =>
      val outcome = io.pinned {
        val home = thread()
        val inner = io.pinned { io.perform(KqueueOps.timer(1)); thread() }
        val failure = intercept[IllegalStateException](io.pinned(throw new IllegalStateException("thrown")))
        io.perform(KqueueOps.timer(1))
        (home, inner, thread(), failure.getMessage())
      }
      assertEquals(outcome._2, outcome._1, "the inner scope ran on the outer one's worker")
      assertEquals(outcome._3, outcome._1, "and the outer scope went on there")
      assertEquals(outcome._4, "thrown")
    }
  }
}
