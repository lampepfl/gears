package gears.async.io
package testkit

import gears.async.Async

/** Supplies independently closable connected stream ends. Writes go from `out` to `in`; closing `out` delivers EOF
  * after queued bytes. Provide room for at least 64 bytes of setup writes before the tests start a reader. The fixture
  * owns any extra setup resources and releases them before returning the pair.
  */
trait StreamFixture extends ContractSuite {
  protected def stream(io: Io[backend.type])(using Async.Spawn): (Readable, Writable)
}

/** Supplies the stream fixture using only the Pipes capability. */
trait PipeFixture extends StreamFixture {
  val backend: Backend & Pipes

  protected def pipe(io: Io[backend.type])(using Async): (Readable, Writable) = {
    val read = Slot[Readable]()
    val write = Slot[Writable]()
    io.perform(io.backend.ops.pipe(read, write))
    (read.clear(), write.clear())
  }

  protected def stream(io: Io[backend.type])(using Async.Spawn): (Readable, Writable) = pipe(io)
}
