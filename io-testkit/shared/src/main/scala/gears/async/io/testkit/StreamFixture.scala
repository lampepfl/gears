package gears.async.io
package testkit

import gears.async.Async

/** Supplies independently closable connected stream ends. Writes go from `out` to `in`; closing `out` delivers EOF
  * after queued bytes. Provide room for at least 64 bytes of setup writes before the tests start a reader. The fixture
  * owns any extra setup resources and releases them before returning the pair.
  */
trait StreamFixture extends ContractSuite {
  protected def stream(io: Io[backend.Interface])(using Async.Spawn): (io.ops.Readable, io.ops.Writable)
}

/** Supplies the stream fixture using only the Pipes capability. */
trait PipeFixture extends StreamFixture {
  val backend: Backend { type Interface <: Pipes }

  protected def pipe(io: Io[backend.Interface])(using Async): (io.ops.Readable, io.ops.Writable) = {
    val read = Slot[io.ops.Readable]()
    val write = Slot[io.ops.Writable]()
    io.perform(io.ops.pipe(read, write))
    (read.clear(), write.clear())
  }

  protected def stream(io: Io[backend.Interface])(using Async.Spawn): (io.ops.Readable, io.ops.Writable) = pipe(io)
}
