package gears.async.io
package bench

import gears.async.Async
import gears.async.Future
import gears.async.Listener
import gears.async.io.kqueue.KqueueBackend

import scala.scalanative.runtime.Continuations

/** What a suspension costs on Scala Native, layer by layer, on one thread: the runtime's own continuations at several
  * depths of stack, then a future's `await` on a source another future completes, then an op that waits (a timer of
  * no time). Run by `BenchMain` given `suspend` as its first argument; `--rounds` is how many of each.
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
    if chosen("await") || chosen("op") then
      KqueueBackend.runOn(1, shareable.Starvation.printWarning) { io ?=>
        for _ <- 1 to 2 do
          if chosen("await") then awaits(rounds)
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
