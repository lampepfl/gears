package gearsbench

import java.io.IOException
import java.net.SocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.StructuredTaskScope
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import GearsBench.MessageSize
import GearsBench.WorkSteps
import GearsBench.report

/** `GearsBench`'s scenarios written against the JDK alone, as a JVM program would without a reactor: blocking channels
  * on virtual threads (or, with `selector`, the least a selector design does: see `SelectorIo`), concurrency with
  * structured task scopes (a preview API, run with `--enable-preview`), and timers on a `ScheduledThreadPoolExecutor`.
  * The echo server, a virtual thread per connection, runs in the same process, over a Unix socket. Each scenario prints
  * its `bench:` line as `GearsBench` does, so `bench/compare.sc` reads both.
  *
  * Where the reactor has no direct counterpart, the closest JDK one stands in: an op built anew is no different from
  * one kept (`pingpong-fresh-ops` is `pingpong`); a blocking op is a subtask forked and joined, so it too runs on
  * another thread; a zero-length timer for `fairness`, and the yields of `mixed` and `starvation`, are `yieldTurn`.
  */
object JdkBench {

  /** Gives up the virtual thread's turn to the threads waiting for a carrier, as gears' `yield` does: parking for a
    * nanosecond unmounts it, and the scheduler's timer wakes it from outside the pool, so that it waits behind the
    * virtual threads already woken, I/O among them. `Thread.yield` does not: on JDK 27 a virtual thread that yields
    * gets its carrier back while the carrier has nothing queued locally, so threads that yield between slices of
    * computation, one per core, kept every carrier, and `starvation`'s round trips never ran.
    */
  private def yieldTurn(): Unit = java.util.concurrent.locks.LockSupport.parkNanos(1)

  private val timers = {
    val executor = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().factory())
    executor.setRemoveOnCancelPolicy(true) // as the reactor's heap drops a cancelled timer
    executor
  }

  private val scenarios: Seq[(String, Int, (SocketAddress, Int, Int) => Unit)] = Seq(
    ("pingpong", 50_000, (a, n, _) => pingPong(a, n)),
    ("pingpong-fresh-ops", 50_000, (a, n, _) => pingPong(a, n)),
    ("pingpong-deadline", 50_000, (a, n, _) => pingPong(a, n, deadline = true)),
    ("concurrent", 100_000, (a, n, c) => concurrent(a, n, c, GearsBench.Work.NoWork)),
    ("concurrent-compute", 100_000, (a, n, c) => concurrent(a, n, c, GearsBench.Work.Inline)),
    ("concurrent-offload", 100_000, (a, n, c) => concurrent(a, n, c, GearsBench.Work.Offload)),
    ("connections", 20_000, (a, n, _) => (1 to n).foreach(_ => pingPong(a, 1))),
    ("pipelined", 200_000, (a, n, _) => pipelined(a, n)),
    ("blocking", 100_000, (_, n, _) => (1 to n).foreach(_ => blocking())),
    ("blocking-io", 50_000, (a, n, _) => pingPong(a, n, blockingFirst = true)),
    ("timers", 200_000, (_, n, _) => timerStress(n)),
    ("fairness", 200_000, (a, n, _) => fairness(a, n)),
    ("starvation", 100_000, (a, n, c) => starvation(a, n, c)),
    ("mixed", 50_000, (a, n, _) => mixed(a, n))
  )

  /** Runs `body` in a scope of its own, joined before it returns: every subtask it forks has finished. */
  private def scoped[A](body: StructuredTaskScope[AnyRef, Void, ?] => A): A = {
    val scope = StructuredTaskScope.open[AnyRef]()
    try {
      val result = body(scope)
      scope.join()
      result
    } finally scope.close()
  }

  // How the clients and the server do their I/O: blocking channels, or a selector each (`run`'s `selector`).
  private var clientIo: Io = BlockingIo
  private var serverIo: Io = BlockingIo

  private def connect(address: SocketAddress): SocketChannel = clientIo.connect(address)

  private def exchange(channel: SocketChannel, buf: ByteBuffer): Unit = {
    buf.clear()
    while buf.hasRemaining do clientIo.write(channel, buf)
    buf.clear()
    while buf.hasRemaining do
      if clientIo.read(channel, buf) < 0 then throw new IOException("The server closed the connection")
  }

  private def blocking(): Unit = scoped(_.fork(() => ()))

  @volatile private var sink = 0L

  private def computeSlice(seed: Long, steps: Int = 10_000): Long = {
    var x = seed | 1L
    var i = 0
    while i < steps do {
      x ^= x << 13
      x ^= x >>> 7
      x ^= x << 17
      i += 1
    }
    x
  }

  private def pingPong(
      address: SocketAddress,
      count: Int,
      deadline: Boolean = false,
      blockingFirst: Boolean = false,
      work: GearsBench.Work = GearsBench.Work.NoWork
  ): Unit = {
    val channel = connect(address)
    try {
      val buf = ByteBuffer.allocateDirect(MessageSize)
      var worked = 0L
      var i = 0
      while i < count do {
        if blockingFirst then blocking()
        val timeout = if deadline then timers.schedule((() => ()): Runnable, 60, TimeUnit.SECONDS) else null
        exchange(channel, buf)
        if timeout != null then timeout.cancel(false)
        work match {
          case GearsBench.Work.NoWork  => ()
          case GearsBench.Work.Inline  => worked += computeSlice(i, WorkSteps)
          case GearsBench.Work.Offload =>
            val seed = i
            val slice: java.util.concurrent.Callable[java.lang.Long] = () => computeSlice(seed, WorkSteps)
            worked += scoped(scope => scope.fork(slice)).get().longValue
        }
        i += 1
      }
      if worked == 1L then sink = worked
    } finally clientIo.close(channel)
  }

  private def concurrent(address: SocketAddress, count: Int, connections: Int, work: GearsBench.Work): Unit = {
    val each = math.max(1, count / connections)
    scoped(scope => (1 to connections).foreach(_ => scope.fork(() => pingPong(address, each, work = work))))
  }

  private def pipelined(address: SocketAddress, count: Int): Unit = {
    val channel = connect(address)
    try
      scoped { scope =>
        scope.fork { () =>
          val out = ByteBuffer.allocateDirect(MessageSize)
          var i = 0
          while i < count do {
            out.clear()
            while out.hasRemaining do clientIo.write(channel, out)
            i += 1
          }
        }
        val in = ByteBuffer.allocateDirect(8192)
        val total = count.toLong * MessageSize
        var received = 0L
        while received < total do {
          in.clear()
          val n = clientIo.read(channel, in)
          if n < 0 then throw new IOException("The server closed the connection")
          received += n
        }
      }
    finally clientIo.close(channel)
  }

  private def timerStress(count: Int): Unit = {
    var done = 0
    while done < count do {
      val batch = math.min(GearsBench.TimerBatch, count - done)
      val firing = (batch + 1) / 2
      val fired = new CountDownLatch(firing)
      val toCancel = (0 until batch).flatMap { i =>
        if i % 2 == 0 then {
          timers.schedule((() => fired.countDown()): Runnable, 0, TimeUnit.MILLISECONDS)
          None
        } else Some(timers.schedule((() => ()): Runnable, 60, TimeUnit.SECONDS))
      }
      fired.await()
      toCancel.foreach(_.cancel(false))
      done += batch
    }
  }

  private def fairness(address: SocketAddress, count: Int): Unit = {
    val done = new AtomicBoolean
    var ticks = 0L
    val start = System.nanoTime()
    scoped { scope =>
      scope.fork { () =>
        while !done.get() do {
          yieldTurn()
          ticks += 1
        }
      }
      pipelined(address, count)
      done.set(true)
    }
    report("fairness-tick", math.max(ticks, 1L), (System.nanoTime() - start) / 1e9)
  }

  private def mixed(address: SocketAddress, count: Int): Unit = {
    val done = new AtomicBoolean
    var slices = 0L
    val start = System.nanoTime()
    scoped { scope =>
      scope.fork { () =>
        var result = 0L
        while !done.get() do {
          result += computeSlice(slices)
          yieldTurn()
          slices += 1
        }
        sink = result
      }
      pingPong(address, count)
      done.set(true)
    }
    report("mixed-compute", slices, (System.nanoTime() - start) / 1e9)
  }

  /** As `GearsBench`'s: `concurrent`'s round trips while a compute thread per core works in slices, yielding between
    * them (`yieldTurn`), and a checker sleeps over and over, recording how late it wakes, as cats-effect's CPU
    * starvation check does.
    */
  private def starvation(address: SocketAddress, count: Int, connections: Int): Unit = {
    val done = new AtomicBoolean
    val interval = GearsBench.StarvationCheckMillis * 1_000_000L
    val late = scala.collection.mutable.ArrayBuffer.empty[Long]
    scoped { scope =>
      scope.fork { () =>
        while !done.get() do {
          val started = System.nanoTime()
          Thread.sleep(GearsBench.StarvationCheckMillis)
          late += math.max(0L, System.nanoTime() - started - interval)
        }
      }
      for core <- 1 to Runtime.getRuntime.availableProcessors do
        scope.fork { () =>
          var result = 0L
          var slice = core.toLong
          while !done.get() do {
            result += computeSlice(slice)
            yieldTurn()
            slice += 1
          }
          if result == 1L then sink = result
        }
      concurrent(address, count, connections, GearsBench.Work.NoWork)
      done.set(true)
    }
    val sorted = late.sorted
    def at(fraction: Double): Long = if sorted.isEmpty then 0L else sorted(((sorted.length - 1) * fraction).toInt)
    report("starvation-p50", 1, at(0.5) / 1e9)
    report("starvation-p99", 1, at(0.99) / 1e9)
    report("starvation-max", 1, at(1.0) / 1e9)
  }

  /** Echoes every connection on a virtual thread of its own, in a scope that lasts as long as the process. */
  private def startServer(path: Path): SocketAddress = {
    Files.deleteIfExists(path)
    val address = UnixDomainSocketAddress.of(path)
    val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    server.bind(address, 128)
    Thread.ofVirtual().start { () =>
      val scope = StructuredTaskScope.open[AnyRef]()
      while true do {
        val client = serverIo.accept(server)
        scope.fork { () =>
          val buf = ByteBuffer.allocateDirect(8192)
          try {
            while serverIo.read(client, buf.clear()) > 0 do {
              buf.flip()
              while buf.hasRemaining do serverIo.write(client, buf)
            }
          } catch case _: IOException => ()
          finally serverIo.close(client)
        }
      }
    }
    address
  }

  /** Runs the named scenarios, or all of them, as `GearsBench.run` does, over a Unix socket at `path`; with `warmup`,
    * each runs first at a fifth of its count, unreported, so the JIT has compiled it. With `selector`, the clients and
    * the server each do their I/O through a selector of their own (`SelectorIo`) instead of blocking channels; with
    * `selectorHere`, each virtual thread waits on a selector of the channel's own (`SelectorHereIo`).
    */
  def run(
      names: Seq[String],
      scale: Double,
      connections: Int,
      path: Path,
      warmup: Boolean,
      selector: Boolean,
      selectorHere: Boolean = false
  ): Unit = {
    if selector then {
      clientIo = new SelectorIo
      serverIo = new SelectorIo
    }
    if selectorHere then {
      clientIo = SelectorHereIo
      serverIo = SelectorHereIo
    }
    val selected =
      if names.isEmpty then scenarios
      else names.map(name => scenarios.find(_._1 == name).getOrElse(throw new IllegalArgumentException(name)))
    val address = startServer(path)
    try {
      if warmup then
        for (_, base, body) <- selected do body(address, math.max(1, (base * scale / 5).toInt), connections)
      println(f"bench: ${"scenario"}%-18s ${"ops"}%10s ${"seconds"}%9s ${"ops/s"}%12s ${"us/op"}%8s")
      for (name, base, body) <- selected do {
        val count = math.max(1, (base * scale).toInt)
        val start = System.nanoTime()
        body(address, count, connections)
        report(name, count, (System.nanoTime() - start) / 1e9)
      }
    } finally Files.deleteIfExists(path)
  }
}
