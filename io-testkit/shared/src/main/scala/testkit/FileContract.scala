package gears.async.io
package testkit

import gears.async.io.internal.ReactorBackend

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** What a backend with regular files must do (see `ReactorContract`): opened by the `open` op, read as a stream, and
  * read, written and synced at offsets.
  */
trait FileContract extends ReactorContract {
  val backend: ReactorBackend & Blocking & Pipes & RegularFiles

  /** Opens the file at `path` through `r` with the `open` op, throwing its failure. */
  protected def open(r: R, path: Path, write: Boolean)(using munit.Location): File = {
    val into = Slot[File]()
    val failure = run(r, backend.ops.open(path.toString, write, into))
    if failure != null then throw failure
    into.clear()
  }

  test("open refuses what is not a regular file, leaving its slot empty") {
    val dir = tempDir()
    try
      withReactor { r =>
        val into = Slot[File]()
        val failure = run(r, backend.ops.open(dir.toString, write = false, into))
        assert(failure.isInstanceOf[IOException], String.valueOf(failure))
        intercept[IllegalStateException](into.clear())
        val missing = run(r, backend.ops.open(dir.resolve("missing").toString, write = false, into))
        assert(missing.isInstanceOf[IOException], String.valueOf(missing))
      }
    finally deleteAll(dir)
  }

  test("an opened file reads to its end as a stream") {
    val dir = tempDir()
    try
      withReactor { r =>
        val path = dir.resolve("contents.txt")
        Files.write(path, "file contents".getBytes(StandardCharsets.UTF_8))
        val file = open(r, path, write = false)
        try {
          val buf = allocate(64)
          assertEquals(readToEnd(r, backend.ops.read(file, buf), buf), "file contents")
        } finally close(r, file)
      }
    finally deleteAll(dir)
  }

  test("a positional read starts at its offset, goes on from where it left off, and ends with nothing added") {
    val dir = tempDir()
    try
      withReactor { r =>
        val path = dir.resolve("digits.txt")
        Files.write(path, "0123456789".getBytes(StandardCharsets.UTF_8))
        val file = open(r, path, write = false)
        try {
          val buf = allocate(2)
          val read = backend.ops.read(file, buf, offset = 6)
          assertEquals(run(r, read), null)
          assertEquals(text(buf), "67")
          buf.clear()
          assertEquals(run(r, read), null)
          assertEquals(text(buf), "89")
          buf.clear()
          assertEquals(run(r, read), null)
          assertEquals(buf.position(), 0)
          val fresh = allocate(4)
          assertEquals(run(r, backend.ops.read(file, fresh, offset = 0)), null)
          assertEquals(text(fresh), "0123")
        } finally close(r, file)
      }
    finally deleteAll(dir)
  }

  test("a read of a file into a buffer with no room fails, as a stream or at an offset, which it leaves where it was") {
    val dir = tempDir()
    try
      withReactor { r =>
        val path = dir.resolve("digits.txt")
        Files.write(path, "0123456789".getBytes(StandardCharsets.UTF_8))
        val file = open(r, path, write = false)
        try {
          val buf = allocate(4)
          buf.position(buf.limit())
          val streamed = run(r, backend.ops.read(file, buf))
          assert(streamed.isInstanceOf[IllegalArgumentException], String.valueOf(streamed))
          val read = backend.ops.read(file, buf, offset = 2)
          val positional = run(r, read)
          assert(positional.isInstanceOf[IllegalArgumentException], String.valueOf(positional))
          buf.clear()
          assertEquals(run(r, read), null)
          assertEquals(text(buf), "2345")
        } finally close(r, file)
      }
    finally deleteAll(dir)
  }

  test("open for writing neither creates nor truncates, and a file is read or written only as it was opened") {
    val dir = tempDir()
    try
      withReactor { r =>
        val missing = dir.resolve("missing.txt")
        val into = Slot[File]()
        val failure = run(r, backend.ops.open(missing.toString, write = true, into))
        assert(failure.isInstanceOf[IOException], String.valueOf(failure))
        assert(!Files.exists(missing), "open for writing created the file")
        val path = dir.resolve("kept.txt")
        Files.write(path, "kept".getBytes(StandardCharsets.UTF_8))
        val writable = open(r, path, write = true)
        try {
          for op <- List(backend.ops.read(writable, allocate(8), 0), backend.ops.read(writable, allocate(8))) do {
            val failure = run(r, op)
            assert(failure.isInstanceOf[IOException], s"a read of a file opened for writing: $failure")
          }
        } finally close(r, writable)
        val readable = open(r, path, write = false)
        try {
          for op <- List(backend.ops.write(readable, bytes("x"), 0), backend.ops.write(readable, bytes("x"))) do {
            val failure = run(r, op)
            assert(failure.isInstanceOf[IOException], s"a write of a file opened for reading: $failure")
          }
        } finally close(r, readable)
        assertEquals(Files.readString(path), "kept")
      }
    finally deleteAll(dir)
  }

  test("a negative offset, or a path with a NUL, is refused as the op is built") {
    val dir = tempDir()
    try
      withReactor { r =>
        val path = dir.resolve("file.txt")
        Files.write(path, "x".getBytes(StandardCharsets.UTF_8))
        val file = open(r, path, write = true)
        try {
          intercept[IllegalArgumentException](backend.ops.read(file, allocate(8), -1))
          intercept[IllegalArgumentException](backend.ops.write(file, bytes("x"), -1))
        } finally close(r, file)
        intercept[IllegalArgumentException](backend.ops.open(s"$path\u0000.txt", write = false, Slot[File]()))
      }
    finally deleteAll(dir)
  }

  test("a file written as a stream is written from its start, each write after the last, and read the same way") {
    val dir = tempDir()
    try
      withReactor { r =>
        val path = dir.resolve("stream.txt")
        Files.write(path, "0123456789".getBytes(StandardCharsets.UTF_8))
        val writing = open(r, path, write = true)
        try {
          for part <- List("ab", "cd") do {
            val buf = bytes(part)
            while buf.hasRemaining do assertEquals(run(r, backend.ops.write(writing, buf)), null)
          }
        } finally close(r, writing)
        assertEquals(Files.readString(path), "abcd456789", "neither truncated nor appended to")
        val reading = open(r, path, write = false)
        try {
          val first = allocate(4)
          assertEquals(run(r, backend.ops.read(reading, first)), null)
          assertEquals(text(first), "abcd")
          val rest = allocate(16)
          assertEquals(run(r, backend.ops.read(reading, rest)), null)
          assertEquals(text(rest), "456789")
        } finally close(r, reading)
      }
    finally deleteAll(dir)
  }

  test("a positional write lands at its offset, and sync completes") {
    val dir = tempDir()
    try
      withReactor { r =>
        val path = dir.resolve("overwritten.txt")
        Files.write(path, "0123456789".getBytes(StandardCharsets.UTF_8))
        val file = open(r, path, write = true)
        try {
          val write = backend.ops.write(file, bytes("ab"), offset = 2)
          assertEquals(run(r, write), null)
          val sync = backend.ops.sync(file)
          assertEquals(run(r, sync), null)
          assertEquals(run(r, sync), null) // repeatable
          assertEquals(Files.readString(path), "01ab456789")
        } finally close(r, file)
      }
    finally deleteAll(dir)
  }

  test("a positional write with nothing remaining completes, writing nothing, and fails once the file is closed") {
    val dir = tempDir()
    try
      withReactor { r =>
        val path = dir.resolve("unchanged.txt")
        Files.write(path, "0123".getBytes(StandardCharsets.UTF_8))
        val file = open(r, path, write = true)
        val empty = allocate(4)
        empty.limit(0)
        val write = backend.ops.write(file, empty, offset = 2)
        try assertEquals(run(r, write), null)
        finally close(r, file)
        assertEquals(Files.readString(path), "0123")
        val failure = run(r, write)
        assert(failure.isInstanceOf[IOException], s"a closed file fails first: $failure")
      }
    finally deleteAll(dir)
  }

  test("a positional read with a time limit that finishes in time may be submitted again, and the reactor closes") {
    val dir = tempDir()
    try {
      val path = dir.resolve("digits.txt")
      Files.write(path, "0123456789".getBytes(StandardCharsets.UTF_8))
      val reactor = backend.open()
      val file = open(reactor, path, write = false)
      try {
        val buf = allocate(2)
        val read = backend.ops.read(file, buf, offset = 0)
        for expected <- Seq("01", "23", "45") do {
          buf.clear()
          val outcome = Outcome()
          reactor.submit(read, outcome, timeoutMillis = 5_000)
          turnUntil(reactor)(outcome.finished)
          assertEquals(outcome.completed, 1, String.valueOf(outcome.failure))
          assertEquals(text(buf), expected)
        }
      } finally {
        close(reactor, file)
        reactor.close() // well before the limits are due, with nothing left of them in the reactor
      }
    } finally deleteAll(dir)
  }

  test("a positional read raced by a close of its file completes, or fails as a closed handle's op does, untouched") {
    val dir = tempDir()
    try
      withReactor { r =>
        val path = dir.resolve("large.bin")
        Files.write(path, new Array[Byte](1 << 20))
        val buf = allocate(1 << 20)
        for round <- 1 to 64 do {
          val file = open(r, path, write = false)
          buf.clear()
          val read = Outcome()
          r.submit(backend.ops.read(file, buf, offset = 0), read) // on a blocker, before or as the close comes
          val until = System.nanoTime() + (round % 8) * 25_000L // a little later each round, so the read is under way
          while System.nanoTime() < until do Thread.onSpinWait()
          val closed = Outcome()
          r.submit(backend.ops.close(file), closed)
          turnUntil(r)(read.finished && closed.finished)
          assertEquals(closed.completed, 1, String.valueOf(closed.failure))
          if read.completed == 1 then assert(buf.position() > 0, "a read that completed read nothing")
          else {
            assert(read.failure.isInstanceOf[java.nio.channels.ClosedChannelException], String.valueOf(read.failure))
            assertEquals(buf.position(), 0, "a read that failed moved its buffer")
          }
        }
      }
    finally deleteAll(dir)
  }

  test("a positional op on a closed file fails") {
    val dir = tempDir()
    try
      withReactor { r =>
        val path = dir.resolve("closed.txt")
        Files.write(path, "x".getBytes(StandardCharsets.UTF_8))
        val file = open(r, path, write = false)
        close(r, file)
        val failure = run(r, backend.ops.read(file, allocate(4), offset = 0))
        assert(failure.isInstanceOf[java.nio.channels.ClosedChannelException], String.valueOf(failure))
      }
    finally deleteAll(dir)
  }
}
