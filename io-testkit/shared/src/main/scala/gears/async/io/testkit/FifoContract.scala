package gears.async.io
package testkit

import gears.async.Async
import gears.async.Future

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** FIFO operations, requiring only Fifos and host filesystem setup. */
trait FifoContract extends FileSystemFixture {
  val backend: Backend & Fifos

  /** A FIFO's read end from the `openFifoForReading` op, whose failure is thrown. */
  protected def openFifoForReading(io: Io[backend.type], path: String)(using Async): Readable = {
    val into = Slot[Readable]()
    opening(io, into, backend.ops.openFifoForReading(path, into))
  }

  /** A FIFO's write end from the `openFifoForWriting` op, whose failure is thrown. */
  protected def openFifoForWriting(io: Io[backend.type], path: String)(using Async): Writable = {
    val into = Slot[Writable]()
    opening(io, into, backend.ops.openFifoForWriting(path, into))
  }

  protected def mkfifo(path: java.nio.file.Path)(using munit.Location): Unit =
    assertEquals(new ProcessBuilder("mkfifo", path.toString).start().waitFor(), 0)

  test("a FIFO carries what its writer writes, then ends when the writer closes") {
    withTempDir { dir =>
      running { io ?=>
        val fifo = dir.resolve("fifo")
        mkfifo(fifo)
        val reader = openFifoForReading(io, fifo.toString) // a non-blocking writer needs a reader to open
        try {
          val writer = openFifoForWriting(io, fifo.toString)
          io.perform(backend.ops.write(writer, bytes("through the fifo")))
          close(io, writer)
          val buf = allocate(64)
          assertEquals(readToEnd(io, backend.ops.read(reader, buf), buf), "through the fifo")
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
          val read = Future(io.perform(backend.ops.read(reader, buf)))
          io.perform(backend.ops.timer(50))
          assert(read.poll().isEmpty, "the read finished with the writer open and nothing written")
          close(io, writer)
          read.await
          assertEquals(buf.position(), 0, "at the end of the stream, nothing read")
        } finally close(io, reader)
      }
    }
  }
}
