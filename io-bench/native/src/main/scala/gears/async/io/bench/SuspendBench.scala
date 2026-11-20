package gears.async.io
package bench

import gears.async.Async
import gears.async.Future
import gears.async.Listener
import gears.async.io.eventpolling.EventPollingBackend

import scala.scalanative.runtime.Continuations

/** What a suspension costs on Scala Native, layer by layer, on one thread: the runtime's own continuations at several
  * depths of stack, then a future's `await` on a source another future completes, then an op that waits (a timer of no
  * time), and ops that are done as they are tried (`enter`). Run by `BenchMain` given `suspend` as its first argument;
  * `--rounds` is how many of each.
  */
object SuspendBench {

  private def report(name: String, rounds: Int, nanos: Long): Unit =
    println(f"suspend: $name%-34s ${nanos.toDouble / rounds}%8.1f ns each")

  // ---- the runtime's continuations alone

  private var pending: (Unit => Unit) | Null = null
  private var sink = 0L

  /** `depth` frames, none of them a tail call, each with a few words of its own, and then the loop that suspends. */
  @noinline private def frames(depth: Int, rounds: Int)(using Continuations.BoundaryLabel[Unit]): Long =
    if depth == 0 then
      var i = 0
      while i < rounds do
        Continuations.suspend[Unit, Unit](k => pending = k)
        i += 1
      i.toLong
    else
      val a = depth * 31L
      val b = frames(depth - 1, rounds)
      a + b + depth

  private def raw(depth: Int, rounds: Int): Unit = {
    val start = System.nanoTime()
    Continuations.boundary[Unit] { sink += frames(depth, rounds) }
    var resumed = 0
    while pending != null do
      val k = pending.nn
      pending = null
      k(())
      resumed += 1
    report(s"runtime, $depth frames deep", resumed, System.nanoTime() - start)
  }

  // ---- a future's await

  /** A source one listener waits on at a time, which `fire` completes. */
  private final class Cell extends Async.Source[Unit] {
    private var waiting: Listener[Unit] | Null = null
    private var fired = false // a `fire` that found nobody waiting, kept for whoever comes
    def poll(k: Listener[Unit]): Boolean = false
    def onComplete(k: Listener[Unit]): Unit =
      if fired then
        fired = false
        k.completeNow((), this): Unit
      else waiting = k
    def dropListener(k: Listener[Unit]): Unit = if waiting eq k then waiting = null
    def fire(): Unit =
      val k = waiting
      if k == null then fired = true
      else
        waiting = null
        k.nn.completeNow((), this): Unit
  }

  /** Two futures of one worker, each waking the other and then waiting to be woken: an await and a resume a round. */
  private def awaits(rounds: Int)(using Async.Spawn): Unit = {
    val ping = new Cell
    val pong = new Cell
    val start = System.nanoTime()
    val a = Future {
      var i = 0
      while i < rounds do
        pong.fire()
        Async.current.await(ping)
        i += 1
      pong.fire()
    }
    val b = Future {
      var i = 0
      while i < rounds do
        Async.current.await(pong)
        ping.fire()
        i += 1
    }
    a.await; b.await
    report("await, two futures taking turns", rounds * 2, System.nanoTime() - start)
  }

  def run(args: Array[String]): Unit = {
    val rounds = args.sliding(2).collectFirst { case Array("--rounds", n) => n.toInt }.getOrElse(1_000_000)
    val only = args.sliding(2).collectFirst { case Array("--only", what) => what }
    def chosen(what: String): Boolean = only.forall(_ == what)
    if chosen("runtime") then for _ <- 1 to 2 do for depth <- Seq(0, 8, 32, 128) do raw(depth, rounds)
    if chosen("await") || chosen("op") || chosen("enter") then
      EventPollingBackend.runOn(1, shareable.Starvation.printWarning) { io ?=>
        for _ <- 1 to 2 do
          if chosen("await") then awaits(rounds)
          if chosen("enter") then
            // Ops that are done as they are tried: what entering and leaving one costs, with and without a call to
            // the system, beside the call alone.
            val in = Slot[io.ops.Readable]()
            val out = Slot[io.ops.Writable]()
            io.perform(io.ops.pipe(in, out))
            val (readEnd, writeEnd) = (in.clear(), out.clear())
            val nothing = BenchPlatform.buffer(1)
            nothing.limit(0)
            val writeNothing = io.ops.write(writeEnd, nothing)
            var start = System.nanoTime()
            var i = 0
            while i < rounds do
              io.perform(writeNothing)
              i += 1
            report("op, a write of nothing", rounds, System.nanoTime() - start)
            val sent = BenchPlatform.buffer(1)
            val got = BenchPlatform.buffer(1)
            val write = io.ops.write(writeEnd, sent)
            val read = io.ops.read(readEnd, got)
            start = System.nanoTime()
            i = 0
            while i < rounds do
              sent.clear()
              io.perform(write)
              got.clear()
              io.perform(read)
              i += 1
            report("op, a byte written, a byte read", rounds * 2, System.nanoTime() - start)
            // The same two calls with no op around them.
            val fdOut = writeEnd.asInstanceOf[gears.async.io.posix.PosixKernelHandle].fd
            val fdIn = readEnd.asInstanceOf[gears.async.io.posix.PosixKernelHandle].fd
            start = System.nanoTime()
            i = 0
            while i < rounds do
              sent.clear()
              gears.async.io.posix.NonBlocking.write(fdOut, sent): Unit
              got.clear()
              gears.async.io.posix.NonBlocking.read(fdIn, got): Unit
              i += 1
            report("the two calls alone", rounds * 2, System.nanoTime() - start)
            io.perform(io.ops.close(readEnd))
            io.perform(io.ops.close(writeEnd))
          if chosen("op") then
            val timer = io.ops.timer(0)
            val start = System.nanoTime()
            var i = 0
            while i < rounds do
              io.perform(timer)
              i += 1
            report("op, a timer of no time", rounds, System.nanoTime() - start)
      }
  }
}
