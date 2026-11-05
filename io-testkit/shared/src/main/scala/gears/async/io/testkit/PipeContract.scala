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
        writeAll(io, out, bytes("then the end"))
        close(io, out)
        val buf = allocate(64)
        assertEquals(readToEnd(io, io.backend.ops.read(in, buf), buf), "then the end")
      } finally close(io, in)
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

  test("a second write pending on a pipe fails with IllegalArgumentException") {
    running { io ?=>
      val (in, out) = pipe(io)
      try {
        // Fill the pipe so that a write remains pending.
        val filler = allocate(1 << 20)
        val writing = Future(writeAll(io, out, filler))
        settle(io)
        val result = writing.poll()
        result.foreach(_.get) // a failed write is a failure, not an unsupported workload
        assume(result.isEmpty, "the pipe accepted the whole payload; there is no pending write to test")
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
