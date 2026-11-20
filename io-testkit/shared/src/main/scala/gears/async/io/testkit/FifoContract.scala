package gears.async.io
package testkit

import gears.async.Async
import gears.async.Future

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** FIFO operations, requiring only Fifos and host filesystem setup. */
trait FifoContract extends FileSystemFixture {
  val backend: Backend { type Interface <: Fifos }

  /** A FIFO's read end from the `openFifoForReading` op, whose failure is thrown. */
  protected def openFifoForReading(io: Io[backend.Interface], path: String)(using Async): io.ops.Readable = {
    opening(io)(slot => io.ops.openFifoForReading(path, slot))
  }

  /** A FIFO's write end from the `openFifoForWriting` op, whose failure is thrown. */
  protected def openFifoForWriting(io: Io[backend.Interface], path: String)(using Async): io.ops.Writable = {
    opening(io)(slot => io.ops.openFifoForWriting(path, slot))
  }

  test("a FIFO carries what its writer writes, then ends when the writer closes") {
    withTempDir { dir =>
      running { io ?=>
        val fifo = dir.resolve("fifo")
        mkfifo(fifo)
        val reader = openFifoForReading(io, fifo.toString) // a non-blocking writer needs a reader to open
        try {
          val writer = openFifoForWriting(io, fifo.toString)
          writeAll(io, writer, bytes("through the fifo"))
          close(io, writer)
          val buf = allocate(64)
          assertEquals(readToEnd(io, io.ops.read(reader, buf), buf), "through the fifo")
        } finally close(io, reader)
      }
    }
  }

  test("a read waiting on a FIFO when its writer closes completes at the end of the stream") {
    withTempDir { dir =>
      running { io ?=>
        val fifo = dir.resolve("fifo")
        mkfifo(fifo)
        val reader = openFifoForReading(io, fifo.toString)
        try {
          val writer = openFifoForWriting(io, fifo.toString)
          val buf = allocate(16)
          val read = Future(io.perform(io.ops.read(reader, buf)))
          io.perform(io.ops.timer(50))
          assert(read.poll().isEmpty, "the read finished with the writer open and nothing written")
          close(io, writer)
          read.await
          assertEquals(buf.position(), 0, "at the end of the stream, nothing read")
        } finally close(io, reader)
      }
    }
  }
}
