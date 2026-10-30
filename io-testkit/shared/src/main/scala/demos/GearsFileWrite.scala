package gears.io.demos

import gears.async.Async
import gears.async.io.Fifos
import gears.async.io.Io
import gears.async.io.testkit.BufferArena
import gears.io.higherlevel.HigherLevel

import java.nio.charset.StandardCharsets

/** Writes the message again after every completed write. */
object GearsFileWrite {
  def run(fifo: String, message: String)(using io: Io[Fifos], async: Async): Unit = {
    BufferArena.scoped { arena =>
      val bytes = message.getBytes(StandardCharsets.UTF_8)
      val buf = arena.allocate(math.max(bytes.length, 1))
      println(s"attempt to open file $fifo")
      val file = HigherLevel.openFifoForWriting(fifo)
      try {
        println(s"opened file: $file")
        println("Event registered for file write.")
        println("starting to poll...")
        val write = io.backend.ops.write(file, buf) // one repeatable op, resubmitted for every write
        while true do {
          buf.clear()
          buf.put(bytes)
          buf.flip()
          io.perform(write)
          println(s"actually wrote ${buf.position()} bytes") // the buffer was loaded at position 0
        }
      } finally io.perform(io.backend.ops.close(file))
    }
  }
}
