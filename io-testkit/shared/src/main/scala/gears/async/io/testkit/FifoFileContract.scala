package gears.async.io
package testkit

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Checks that distinguish FIFOs from regular files, requiring both capabilities. */
trait FifoFileContract extends FileSystemFixture {
  val backend: Backend { type Interface <: Fifos & RegularFiles }

  test("open refuses a FIFO, and openFifoForReading a regular file") {
    withTempDir { dir =>
      running { io ?=>
        val fifo = dir.resolve("fifo")
        mkfifo(fifo)
        intercept[java.io.IOException](opening(io)(slot => io.ops.open(fifo.toString, write = false, slot)))
        val file = dir.resolve("file")
        Files.write(file, "x".getBytes(StandardCharsets.UTF_8))
        val into = Slot[io.ops.Readable]()
        val failure = attempt(io, io.ops.openFifoForReading(file.toString, into))
        assert(failure.isInstanceOf[java.io.IOException], String.valueOf(failure))
        intercept[IllegalStateException](into.clear())
      }
    }
  }
}
