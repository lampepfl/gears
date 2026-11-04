package gears.async.io
package testkit

/** Programs testing pipe opening and occupied output slots. */
trait PipeFuturesContract extends PipeFixture {

  test("an op performed with a slot not yet cleared fails with IllegalArgumentException, opening nothing") {
    val outcome = running { io ?=>
      val (in, out) = pipe(io)
      val read = Slot[Readable]()
      val write = Slot[Writable]()
      io.perform(io.backend.ops.pipe(read, write))
      val first = read.clear() // `write` still holds the first pipe's end
      val refused =
        try {
          io.perform(io.backend.ops.pipe(read, write))
          "performed"
        } catch case e: IllegalArgumentException => "refused"
      val nothingOpened =
        try {
          read.clear()
          false
        } catch case _: IllegalStateException => true
      for h <- List[Handle](in, out, first, write.clear()) do io.perform(io.backend.ops.close(h))
      s"$refused $nothingOpened"
    }
    assertEquals(outcome, "refused true")
  }
}
