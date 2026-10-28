package gears.async.io
package testkit

import gears.async.io.internal.ReactorBackend

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** What a backend with FIFOs must do (see `ReactorContract`); with regular files too, to tell the two apart. */
trait FifoContract extends FileContract {
  val backend: ReactorBackend & Blocking & Pipes & Fifos & RegularFiles

  /** A FIFO's read end from the `openFifoForReading` op, whose failure is thrown. */
  protected def openFifoForReading(r: R, path: String)(using munit.Location): Readable = {
    val into = Slot[Readable]()
    opened(r, into, backend.ops.openFifoForReading(path, into))
  }

  /** A FIFO's write end from the `openFifoForWriting` op, whose failure is thrown. */
  protected def openFifoForWriting(r: R, path: String)(using munit.Location): Writable = {
    val into = Slot[Writable]()
    opened(r, into, backend.ops.openFifoForWriting(path, into))
  }

  test("a FIFO carries what its writer writes, then ends when the writer closes") {
    val dir = tempDir()
    try
      withReactor { r =>
        val fifo = dir.resolve("fifo")
        val made = new ProcessBuilder("mkfifo", fifo.toString).start().waitFor()
        assertEquals(made, 0)
        val reader = openFifoForReading(r, fifo.toString) // a non-blocking writer needs a reader to open
        try {
          val writer = openFifoForWriting(r, fifo.toString)
          val sent = Outcome()
          r.submit(backend.ops.write(writer, bytes("through the fifo")), sent)
          turnUntil(r)(sent.finished)
          assertEquals(sent.completed, 1, String.valueOf(sent.failure))
          closeAlone(writer)
          val buf = allocate(64)
          assertEquals(readToEnd(r, backend.ops.read(reader, buf), buf), "through the fifo")
        } finally closeAlone(reader)
      }
    finally deleteAll(dir)
  }
  test("a read waiting on a FIFO when its writer closes completes at the end of the stream") {
    val dir = tempDir()
    try
      withReactor { r =>
        val fifo = dir.resolve("fifo")
        assertEquals(new ProcessBuilder("mkfifo", fifo.toString).start().waitFor(), 0)
        val reader = openFifoForReading(r, fifo.toString)
        try {
          val writer = openFifoForWriting(r, fifo.toString)
          val buf = allocate(16)
          val read = Outcome()
          r.submit(backend.ops.read(reader, buf), read)
          for _ <- 1 to 5 do r.runOnce(block = false)
          assert(!read.finished, "the read finished with the writer open and nothing written")
          closeAlone(writer)
          turnUntil(r)(read.finished)
          assertEquals(read.completed, 1, String.valueOf(read.failure))
          assertEquals(buf.position(), 0, "at the end of the stream, nothing read")
        } finally closeAlone(reader)
      }
    finally deleteAll(dir)
  }

  test("open refuses a FIFO, and openFifoForReading a regular file") {
    val dir = tempDir()
    try
      withReactor { r =>
        val fifo = dir.resolve("fifo")
        assertEquals(new ProcessBuilder("mkfifo", fifo.toString).start().waitFor(), 0)
        intercept[java.io.IOException](open(r, fifo, write = false))
        val file = dir.resolve("file")
        Files.write(file, "x".getBytes(StandardCharsets.UTF_8))
        val into = Slot[Readable]()
        val failure = run(r, backend.ops.openFifoForReading(file.toString, into))
        assert(failure.isInstanceOf[java.io.IOException], String.valueOf(failure))
        intercept[IllegalStateException](into.clear())
      }
    finally deleteAll(dir)
  }
}
