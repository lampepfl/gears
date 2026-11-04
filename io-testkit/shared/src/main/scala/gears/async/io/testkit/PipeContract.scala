package gears.async.io
package testkit

import gears.async.Future

import java.io.IOException

/** Pipe opening, one-shot operations and pipe-specific flow and close behaviour. */
trait PipeContract extends StreamContract with PipeFixture {

  test("a pipe refuses an occupied output slot without opening either end") {
    running { io ?=>
      val read = Slot[Readable]()
      val write = Slot[Writable]()
      io.perform(io.backend.ops.pipe(read, write))
      val in = read.clear()
      val refused = attempt(io, io.backend.ops.pipe(read, write)) // `write` still holds the first pipe's end
      assert(refused.isInstanceOf[IllegalArgumentException], String.valueOf(refused))
      intercept[IllegalStateException](read.clear()) // nothing opened
      close(io, in)
      close(io, write.clear())
    }
  }

  test("a write to a pipe whose reader has closed fails with an IOException, not a signal that ends the process") {
    running { io ?=>
      val (in, out) = pipe(io)
      close(io, in)
      try {
        val failure = attempt(io, io.backend.ops.write(out, bytes("nobody reads this")))
        assert(failure.isInstanceOf[IOException], String.valueOf(failure))
      } finally close(io, out)
    }
  }

  test("a pipe's reads end after the bytes written before its writer closed") {
    running { io ?=>
      val (in, out) = pipe(io)
      try {
        io.perform(io.backend.ops.write(out, bytes("then the end")))
        close(io, out)
        val buf = allocate(64)
        assertEquals(readToEnd(io, io.backend.ops.read(in, buf), buf), "then the end")
      } finally close(io, in)
    }
  }

  test("writes fill a pipe, wait while it is full, and go on as it drains") {
    running { io ?=>
      val (in, out) = pipe(io)
      try {
        val size = 1 << 20
        val source = allocate(size)
        while source.hasRemaining do source.put((source.position() % 251).toByte)
        source.flip()
        val sink = allocate(size)
        val write = io.backend.ops.write(out, source)
        val read = io.backend.ops.read(in, sink)
        val writer = Future(while source.hasRemaining do io.perform(write))
        val reader = Future(while sink.hasRemaining do io.perform(read))
        writer.await
        reader.await
        sink.flip()
        var i = 0
        while i < size do {
          if sink.get(i) != (i % 251).toByte then fail(s"byte $i is wrong")
          i += 1
        }
      } finally {
        close(io, in)
        close(io, out)
      }
    }
  }

  test("an op that opens or closes is one-shot: performed again, it throws IllegalStateException") {
    running { io ?=>
      val read = Slot[Readable]()
      val write = Slot[Writable]()
      val opening = io.backend.ops.pipe(read, write)
      io.perform(opening)
      val (in, out) = (read.clear(), write.clear())
      intercept[IllegalStateException](io.perform(opening))
      val closing = io.backend.ops.close(in)
      io.perform(closing)
      intercept[IllegalStateException](io.perform(closing))
      close(io, out)
    }
  }

  test("a one-shot op performed by two futures at once opens once, the other refused") {
    running { io ?=>
      for _ <- 1 to 100 do {
        val read = Slot[Readable]()
        val write = Slot[Writable]()
        val opening = io.backend.ops.pipe(read, write)
        val start = Future.Promise[Unit]()
        val both = List.fill(2)(Future {
          start.await
          io.perform(opening)
        })
        start.complete(scala.util.Success(()))
        val failures = both.map(failureOf)
        assertEquals(failures.count(_ == null), 1, failures.mkString(", "))
        assert(failures.exists(_.isInstanceOf[IllegalStateException]), failures.mkString(", "))
        close(io, read.clear())
        close(io, write.clear())
      }
    }
  }

  test("a second read pending on a handle, or a second write, fails with IllegalArgumentException") {
    running { io ?=>
      val (in, out) = pipe(io)
      try {
        val first = Future(io.perform(io.backend.ops.read(in, allocate(16))))
        settle(io)
        val second = attempt(io, io.backend.ops.read(in, allocate(16)))
        assert(second.isInstanceOf[IllegalArgumentException], s"a second read: $second")
        val fromAnother = failureOf(Future(io.perform(io.backend.ops.read(in, allocate(16)))))
        assert(fromAnother.isInstanceOf[IllegalArgumentException], s"a second read from another future: $fromAnother")
        assert(first.poll().isEmpty, "the first read is still pending")
        io.perform(io.backend.ops.write(out, bytes("x")))
        first.await
        val again = Future(io.perform(io.backend.ops.read(in, allocate(16)))) // once the first has finished
        settle(io)
        io.perform(io.backend.ops.write(out, bytes("y")))
        again.await

        // Writes likewise: a pipe filled, so that one write waits.
        val filler = allocate(1 << 20)
        val writing = Future(while filler.hasRemaining do io.perform(io.backend.ops.write(out, filler)))
        settle(io)
        val secondWrite = attempt(io, io.backend.ops.write(out, bytes("z")))
        assert(secondWrite.isInstanceOf[IllegalArgumentException], s"a second write: $secondWrite")
        writing.cancel()
        writing.awaitResult
      } finally {
        close(io, in)
        close(io, out)
      }
    }
  }
}
