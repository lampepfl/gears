package gears.async.io
package nio

import gears.async.Async
import gears.async.AsyncOperations
import gears.async.Future
import gears.async.VThreadScheduler
import gears.async.VThreadSupport
import gears.async.io.Io
import gears.async.io.internal.OnCaller
import gears.async.io.internal.ReactorRuns
import gears.async.io.internal.ReactorScheduler
import gears.async.io.nio.NioBackend
import gears.async.io.nio.NioOnCaller
import gears.async.io.testkit.BufferArena
import gears.async.io.testkit.FuturesContract

import java.nio.charset.StandardCharsets

/** `FuturesContract` over `NioOnCaller`: gears' own virtual threads, each op performed on its own thread. */
class NioOnCallerSuite extends FuturesContract {
  val backend: NioOnCaller.type = NioOnCaller

  protected def running[T](body: Io[backend.type] ?=> (Async.Spawn, AsyncOperations) ?=> T): T =
    Io.run(using backend)(body)

  test("NIO's kinds of handle are distinct types, though one class underneath") {
    assert(compileErrors("def k(c: NioBackend.Connection): NioBackend.Listener = c").nonEmpty)
    assert(compileErrors("def k(r: NioBackend.Readable): NioBackend.Writable = r").nonEmpty)
    assert(compileErrors("def k(f: NioBackend.File): NioBackend.Connection = f").nonEmpty)
    assertEquals(compileErrors("def k(c: NioBackend.Connection): NioBackend.Readable = c"), "")
  }

  test("a pool of workers, an internal knob, refuses a caller backend, which has nothing for it to run") {
    intercept[IllegalArgumentException](ReactorRuns.run(2)(using NioOnCaller)(42))
  }

  test("a program over NioOnCaller needs no pool: its Io, an OnCaller, performs on the calling thread") {
    given VThreadSupport.type = VThreadSupport
    given VThreadScheduler.type = VThreadScheduler
    var outcome = ""
    Async.blocking {
      val io = OnCaller(NioOnCaller)
      val read = Slot[io.Readable]()
      val write = Slot[io.Writable]()
      io.perform(io.backend.ops.pipe(read, write)) // on this, a platform thread
      val (in, out) = (read.clear(), write.clear())
      val arena = BufferArena.open()
      try {
        val buf = arena.allocate(16)
        val reading = Future(io.perform(io.backend.ops.read(in, buf))) // on a virtual thread
        io.perform(io.backend.ops.timer(10)) // on this, a platform thread
        io.perform(io.backend.ops.write(out, arena.of("no runner".getBytes(StandardCharsets.UTF_8))))
        reading.await
        buf.flip()
        outcome = StandardCharsets.UTF_8.decode(buf).toString
      } finally {
        io.perform(io.backend.ops.close(in))
        io.perform(io.backend.ops.close(out))
        arena.close()
      }
    }
    assertEquals(outcome, "no runner")
  }

  test("an interrupt the caller's thread already had neither fails nor closes a file it reads, and is kept") {
    given VThreadSupport.type = VThreadSupport
    given VThreadScheduler.type = VThreadScheduler
    val path = java.nio.file.Files.createTempFile("gears-io", ".txt")
    java.nio.file.Files.writeString(path, "file")
    var read = ""
    var kept = false
    try
      Async.blocking {
        val io = OnCaller(NioOnCaller)
        val into = Slot[io.File]()
        io.perform(io.backend.ops.open(path.toString, write = false, into))
        val file = into.clear()
        val arena = BufferArena.open()
        try {
          val buf = arena.allocate(16)
          Thread.currentThread().interrupt() // a JDK FileChannel used with it set closes
          io.perform(io.backend.ops.read(file, buf, 0))
          kept = Thread.interrupted()
          buf.flip()
          read = StandardCharsets.UTF_8.decode(buf).toString
          io.perform(io.backend.ops.read(file, arena.allocate(16), 0)) // the file is still open
        } finally {
          io.perform(io.backend.ops.close(file))
          arena.close()
        }
      }
    finally java.nio.file.Files.delete(path)
    assertEquals(read, "file")
    assert(kept, "the interrupt was lost")
  }
}

/** `FuturesContract` over `NioBackend`: futures carried by a pool of `workers` workers driving its reactors. */
abstract class ReactorFuturesSuite(workers: Int) extends FuturesContract {
  val backend: NioBackend.type = NioBackend

  protected def running[T](body: Io[backend.type] ?=> (Async.Spawn, AsyncOperations) ?=> T): T =
    ReactorRuns.run(workers)(using backend)(body)
}

class ReactorCarriersSuite extends ReactorFuturesSuite(workers = 1) {
  test("perform reuses the operation's completion state without replacing its attachment") {
    running { io ?=>
      val op = NioOps.timer(0)
      val attachment = new Object
      op.attachment = attachment
      io.perform(op)
      io.perform(op)
      assert(op.attachment eq attachment)
    }
  }

  test("a program's Io is an OnPool over the backend: its ops go to the reactor of the worker running the caller") {
    var onWorker = false
    running { io ?=>
      onWorker = summon[Async].scheduler match {
        case s: ReactorScheduler => s.currentWorker.reactor.isInstanceOf[gears.async.io.nio.NioReactor]
        case _                   => false
      }
    }
    assert(onWorker)
  }

  test("a program's Io cannot start a run: it is not a Backend") {
    val errors = compileErrors("running(io ?=> Io.run(using io)(()))")
    assert(errors.nonEmpty, errors)
  }

  test("an op of a reactor backend cannot be performed with the backend itself, only with a program's Io") {
    val errors = compileErrors("def outside(using Async): Unit = NioBackend.perform(NioBackend.ops.timer(1))")
    assert(errors.nonEmpty, errors)
  }
}
class ReactorCarriersOnFourSuite extends ReactorFuturesSuite(workers = 4)
