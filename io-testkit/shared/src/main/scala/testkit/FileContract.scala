package gears.async.io
package testkit

import gears.async.Async
import gears.async.Future

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success

/** What a backend with regular files must do: opened by the `open` op, read as a stream, and read, written and synced
  * at offsets; checked through `perform`, as programs over the backend (see `ContractSuite`).
  */
trait FileContract extends ContractSuite {
  val backend: Backend & RegularFiles

  /** Opens the file at `path` with the `open` op, throwing its failure. */
  protected def openFile(io: Io[backend.type], path: Path, write: Boolean)(using Async): backend.File = {
    val into = Slot[backend.File]()
    opening(io, into, backend.ops.open(path.toString, write, into))
  }

  /** Performs `read` into `buf` until the end of the stream, which adds nothing, and returns what `buf` holds. */
  protected def readFileToEnd(io: Io[backend.type], read: backend.Op, buf: ByteBuffer)(using Async): String = {
    var atEnd = false
    while !atEnd do {
      val before = buf.position()
      io.perform(read)
      atEnd = buf.position() == before
    }
    text(buf)
  }

  test("open refuses what is not a regular file, leaving its slot empty") {
    withTempDir { dir =>
      running { io ?=>
        val into = Slot[backend.File]()
        val failure = attempt(io, backend.ops.open(dir.toString, write = false, into))
        assert(failure.isInstanceOf[IOException], String.valueOf(failure))
        intercept[IllegalStateException](into.clear())
        val missing = attempt(io, backend.ops.open(dir.resolve("missing").toString, write = false, into))
        assert(missing.isInstanceOf[IOException], String.valueOf(missing))
      }
    }
  }

  test("an opened file reads to its end as a stream") {
    withTempDir { dir =>
      running { io ?=>
        val path = dir.resolve("contents.txt")
        Files.write(path, "file contents".getBytes(StandardCharsets.UTF_8))
        val file = openFile(io, path, write = false)
        try {
          val buf = allocate(64)
          assertEquals(readFileToEnd(io, backend.ops.read(file, buf), buf), "file contents")
        } finally close(io, file)
      }
    }
  }

  test("a positional read starts at its offset, goes on from where it left off, and ends with nothing added") {
    withTempDir { dir =>
      running { io ?=>
        val path = dir.resolve("digits.txt")
        Files.write(path, "0123456789".getBytes(StandardCharsets.UTF_8))
        val file = openFile(io, path, write = false)
        try {
          val buf = allocate(2)
          val read = backend.ops.read(file, buf, offset = 6)
          io.perform(read)
          assertEquals(text(buf), "67")
          buf.clear()
          io.perform(read)
          assertEquals(text(buf), "89")
          buf.clear()
          io.perform(read)
          assertEquals(buf.position(), 0)
          val fresh = allocate(4)
          io.perform(backend.ops.read(file, fresh, offset = 0))
          assertEquals(text(fresh), "0123")
        } finally close(io, file)
      }
    }
  }

  test("a read of a file into a buffer with no room fails, as a stream or at an offset, which it leaves where it was") {
    withTempDir { dir =>
      running { io ?=>
        val path = dir.resolve("digits.txt")
        Files.write(path, "0123456789".getBytes(StandardCharsets.UTF_8))
        val file = openFile(io, path, write = false)
        try {
          val buf = allocate(4)
          buf.position(buf.limit())
          val streamed = attempt(io, backend.ops.read(file, buf))
          assert(streamed.isInstanceOf[IllegalArgumentException], String.valueOf(streamed))
          val read = backend.ops.read(file, buf, offset = 2)
          val positional = attempt(io, read)
          assert(positional.isInstanceOf[IllegalArgumentException], String.valueOf(positional))
          buf.clear()
          io.perform(read)
          assertEquals(text(buf), "2345")
        } finally close(io, file)
      }
    }
  }

  test("open for writing neither creates nor truncates, and a file is read or written only as it was opened") {
    withTempDir { dir =>
      running { io ?=>
        val missing = dir.resolve("missing.txt")
        val into = Slot[backend.File]()
        val failure = attempt(io, backend.ops.open(missing.toString, write = true, into))
        assert(failure.isInstanceOf[IOException], String.valueOf(failure))
        assert(!Files.exists(missing), "open for writing created the file")
        val path = dir.resolve("kept.txt")
        Files.write(path, "kept".getBytes(StandardCharsets.UTF_8))
        val writable = openFile(io, path, write = true)
        try {
          for op <- List(backend.ops.read(writable, allocate(8), 0), backend.ops.read(writable, allocate(8))) do {
            val failure = attempt(io, op)
            assert(failure.isInstanceOf[IOException], s"a read of a file opened for writing: $failure")
          }
        } finally close(io, writable)
        val readable = openFile(io, path, write = false)
        try {
          for op <- List(backend.ops.write(readable, bytes("x"), 0), backend.ops.write(readable, bytes("x"))) do {
            val failure = attempt(io, op)
            assert(failure.isInstanceOf[IOException], s"a write of a file opened for reading: $failure")
          }
        } finally close(io, readable)
        assertEquals(Files.readString(path), "kept")
      }
    }
  }

  test("a negative offset, or a path with a NUL, is refused as the op is built") {
    withTempDir { dir =>
      running { io ?=>
        val path = dir.resolve("file.txt")
        Files.write(path, "x".getBytes(StandardCharsets.UTF_8))
        val file = openFile(io, path, write = true)
        try {
          intercept[IllegalArgumentException](backend.ops.read(file, allocate(8), -1))
          intercept[IllegalArgumentException](backend.ops.write(file, bytes("x"), -1))
        } finally close(io, file)
        intercept[IllegalArgumentException](backend.ops.open(s"$path\u0000.txt", write = false, Slot[backend.File]()))
      }
    }
  }

  test("a file written as a stream is written from its start, each write after the last, and read the same way") {
    withTempDir { dir =>
      running { io ?=>
        val path = dir.resolve("stream.txt")
        Files.write(path, "0123456789".getBytes(StandardCharsets.UTF_8))
        val writing = openFile(io, path, write = true)
        try {
          for part <- List("ab", "cd") do {
            val buf = bytes(part)
            while buf.hasRemaining do io.perform(backend.ops.write(writing, buf))
          }
        } finally close(io, writing)
        assertEquals(Files.readString(path), "abcd456789", "neither truncated nor appended to")
        val reading = openFile(io, path, write = false)
        try {
          val first = allocate(4)
          io.perform(backend.ops.read(reading, first))
          assertEquals(text(first), "abcd")
          val rest = allocate(16)
          io.perform(backend.ops.read(reading, rest))
          assertEquals(text(rest), "456789")
        } finally close(io, reading)
      }
    }
  }

  test("a positional write lands at its offset, and sync completes") {
    withTempDir { dir =>
      running { io ?=>
        val path = dir.resolve("overwritten.txt")
        Files.write(path, "0123456789".getBytes(StandardCharsets.UTF_8))
        val file = openFile(io, path, write = true)
        try {
          io.perform(backend.ops.write(file, bytes("ab"), offset = 2))
          val sync = backend.ops.sync(file)
          io.perform(sync)
          io.perform(sync) // repeatable
          assertEquals(Files.readString(path), "01ab456789")
        } finally close(io, file)
      }
    }
  }

  test("a positional write with nothing remaining completes, writing nothing, and fails once the file is closed") {
    withTempDir { dir =>
      running { io ?=>
        val path = dir.resolve("unchanged.txt")
        Files.write(path, "0123".getBytes(StandardCharsets.UTF_8))
        val file = openFile(io, path, write = true)
        val empty = allocate(4)
        empty.limit(0)
        val write = backend.ops.write(file, empty, offset = 2)
        try io.perform(write)
        finally close(io, file)
        assertEquals(Files.readString(path), "0123")
        val failure = attempt(io, write)
        assert(failure.isInstanceOf[IOException], s"a closed file fails first: $failure")
      }
    }
  }

  test("a positional read with a time limit that finishes in time may be performed again") {
    withTempDir { dir =>
      running { io ?=>
        val path = dir.resolve("digits.txt")
        Files.write(path, "0123456789".getBytes(StandardCharsets.UTF_8))
        val file = openFile(io, path, write = false)
        try {
          val buf = allocate(2)
          val read = backend.ops.read(file, buf, offset = 0)
          for expected <- Seq("01", "23", "45") do {
            buf.clear()
            io.perform(read, 5.seconds)
            assertEquals(text(buf), expected)
          }
        } finally close(io, file)
      }
    }
  }

  test("a positional read raced by a close of its file completes, or fails as a closed handle's op does, untouched") {
    withTempDir { dir =>
      running { io ?=>
        val path = dir.resolve("large.bin")
        Files.write(path, new Array[Byte](1 << 20))
        val buf = allocate(1 << 20)
        for round <- 1 to 64 do {
          val file = openFile(io, path, write = false)
          buf.clear()
          val read = Future(io.perform(backend.ops.read(file, buf, offset = 0))) // before or as the close comes
          val until = System.nanoTime() + (round % 8) * 25_000L // a little later each round, so the read is under way
          while System.nanoTime() < until do Thread.onSpinWait()
          close(io, file)
          read.awaitResult match {
            case Success(_)       => assert(buf.position() > 0, "a read that completed read nothing")
            case Failure(failure) =>
              assert(failure.isInstanceOf[ClosedChannelException], String.valueOf(failure))
              assertEquals(buf.position(), 0, "a read that failed moved its buffer")
          }
        }
      }
    }
  }

  test("a positional op on a closed file fails") {
    withTempDir { dir =>
      running { io ?=>
        val path = dir.resolve("closed.txt")
        Files.write(path, "x".getBytes(StandardCharsets.UTF_8))
        val file = openFile(io, path, write = false)
        close(io, file)
        val failure = attempt(io, backend.ops.read(file, allocate(4), offset = 0))
        assert(failure.isInstanceOf[ClosedChannelException], String.valueOf(failure))
      }
    }
  }
}
