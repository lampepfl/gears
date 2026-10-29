package gears.async.io.nio

import gears.async.io.internal.ReactorRuns
import gears.async.io.{Io, Slot}
import gears.async.{Async, AsyncOperations, Future}

/** Diagnostic throughput checks for changes to the operation/continuation path. No timing threshold: compare medians in
  * the same environment. Each sample verifies completion, and warm-up runs are excluded from the report.
  */
class RuntimeCostSuite extends munit.FunSuite {
  private def measure(name: String, count: Int)(body: Io[NioBackend.type] ?=> Async.Spawn ?=> Unit): Unit = {
    val samples = (0 until 7)
      .map { _ =>
        val started = System.nanoTime()
        ReactorRuns.run(1)(using NioBackend) { io ?=> body(using io) }
        (System.nanoTime() - started).toDouble / count
      }
      .drop(2)
      .sorted
    println(f"runtime-cost $name: ${samples(samples.size / 2)}%.1f ns/op")
  }

  test("immediate EOF, suspended timers, and group cancellation costs") {
    val reads = 200000
    measure("immediate-eof", reads) {
      val io = summon[Io[NioBackend.type]]
      val readEnd = Slot[io.Readable]()
      val writeEnd = Slot[io.Writable]()
      io.perform(io.backend.ops.pipe(readEnd, writeEnd))
      val in = readEnd.clear()
      io.perform(io.backend.ops.close(writeEnd.clear()))
      try {
        val buffer = java.nio.ByteBuffer.allocateDirect(1)
        val read = io.backend.ops.read(in, buffer)
        var i = 0
        while i < reads do {
          io.perform(read)
          i += 1
        }
        assertEquals(buffer.position(), 0)
      } finally io.perform(io.backend.ops.close(in))
    }
    val timers = 10000
    measure("suspended-timer", timers) {
      val io = summon[Io[NioBackend.type]]
      val timer = io.backend.ops.timer(0)
      var i = 0
      while i < timers do {
        io.perform(timer)
        i += 1
      }
    }
    val groups = 1000
    measure("cancelled-operation", groups * 8) {
      val io = summon[Io[NioBackend.type]]
      var i = 0
      while i < groups do {
        Async.group {
          val started = new java.util.concurrent.atomic.AtomicInteger
          for _ <- 0 until 8 do
            Future {
              started.incrementAndGet()
              io.perform(io.backend.ops.timer(10000))
            }
          val turn = io.backend.ops.timer(0)
          while started.get() < 8 do io.perform(turn)
        }
        i += 1
      }
    }
  }
}
