package gears.io.demos

import gears.async.Async
import gears.async.io.Fifos
import gears.async.io.Io
import gears.async.io.testkit.BufferArena
import gears.io.higherlevel.HigherLevel

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

import DemoIO.drainTo

/** Reads a FIFO as its writer writes it: one repeatable read waits for bytes and takes all there are, up to the chunk's
  * room.
  */
object GearsFileRead {
  def run(fifo: String)(using io: Io[Fifos], async: Async): Unit = {
    BufferArena.scoped { arena =>
      val chunk = arena.allocate(1024)
      val contents = new ByteArrayOutputStream()
      println(s"attempt to open file $fifo")
      val file = HigherLevel.openFifoForReading(fifo)
      try {
        println(s"opened file: $file")
        // One repeatable op, performed again for each chunk; a read that adds nothing is the writer's end.
        val read = io.backend.ops.read(file, chunk)
        println("Event registered for file read.") // the plain demo's words, for which its checks wait
        println("starting to poll...")
        var heard = false // bytes came since the last end reported: a writer has been
        while true do {
          chunk.clear()
          io.perform(read)
          if chunk.position() == 0 then { // the end: no writer has the FIFO open
            if heard then { // everything the writer sent is in `contents`
              println(s"End of file reached. Contents are ${contents.size} long.")
              println(s"File contents: `${contents.toString(StandardCharsets.UTF_8)}`")
              contents.reset() // Start over for the next writer
              heard = false
            }
            // With no writer the FIFO stays at its end, and nothing reports one opening it: look again shortly.
            io.sleep(50)
          } else {
            heard = true
            println(s"actually read ${chunk.position()} bytes")
            chunk.flip()
            drainTo(chunk, contents)
          }
        }
      } finally io.perform(io.backend.ops.close(file))
    }
  }
}
