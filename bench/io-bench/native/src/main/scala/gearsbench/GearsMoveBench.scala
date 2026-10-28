package gearsbench

import gears.async.io.UnixAddress
import gears.async.io.internal.Completion
import gears.async.io.internal.NativeBuffer
import gears.async.io.internal.NonBlocking
import gears.async.io.kqueue.KqueueBackend.Handle
import gears.async.io.kqueue.KqueueHandles
import gears.async.io.kqueue.KqueueKinds
import gears.async.io.kqueue.KqueueOp
import gears.async.io.kqueue.KqueueOps
import gears.async.io.kqueue.KqueueReactor

import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import scala.scalanative.unsafe.Zone

/** Measures what moving a descriptor between kqueue reactors costs, run as `iobench gears move-bench`: ping-pong round
  * trips of 64 bytes against an echo server on a reactor thread of its own, driven by completions on the client
  * reactors directly, with no scheduler. Three ways:
  *   - `stay`: one client reactor and one connection, round trips back to back;
  *   - `hand-off`: two client reactors, each on its own thread and with its own connection, taking turns every N round
  *     trips, so the work moves between threads but no descriptor does;
  *   - `move`: the same, sharing one connection, so its descriptor moves with every turn: the new reactor starts over
  *     with it and registers a filter, and the old one drops its own when the descriptor's next data wakes it.
  * `move` less `hand-off`, times N, is what each move of a descriptor costs beyond moving the work.
  */
object GearsMoveBench {
  inline val MessageSize = 64

  /** A reactor whose own thread runs the tasks given to it between turns, counting the turns that return. */
  final class Driven(name: String) {
    val reactor: KqueueReactor = KqueueReactor.open()
    private val tasks = new ConcurrentLinkedQueue[Runnable]()
    @volatile private var running = true
    val turns = new AtomicLong
    private val thread = new Thread(() => loop(), name)
    thread.setDaemon(true)
    thread.start()

    private def loop(): Unit = {
      while running do {
        var task = tasks.poll()
        while task != null do {
          task.run()
          task = tasks.poll()
        }
        if running then reactor.runOnce(block = true)
        turns.incrementAndGet()
      }
      reactor.close()
    }

    /** Runs `body` on the reactor's thread, without waiting for it. */
    def post(body: => Unit): Unit = {
      tasks.add(() => body)
      reactor.wakeup()
    }

    /** Runs `body` on the reactor's thread, and waits until it has. */
    def onThread(body: => Unit): Unit = {
      val ran = new CountDownLatch(1)
      tasks.add(() => { body; ran.countDown() })
      reactor.wakeup()
      ran.await()
    }

    def stop(): Unit = {
      running = false
      reactor.wakeup()
    }
  }

  /** A connection's client end, with its buffer and ops, which any client reactor may submit in turn. */
  final class Conn(val fd: KqueueKinds.Connection, zone: Zone) {
    val buf = NativeBuffer.allocate(MessageSize)(using zone)
    val write = KqueueOps.write(fd, buf)
    val read = KqueueOps.read(fd, buf)
  }

  /** Echoes everything `fd` sends, on `server`'s thread, until it closes. */
  private def echo(server: Driven, fd: KqueueKinds.Connection, zone: Zone): Unit = server.onThread {
    val buf = NativeBuffer.allocate(8192)(using zone)
    val read = KqueueOps.read(fd, buf)
    val write = KqueueOps.write(fd, buf)
    lazy val onRead: Completion[KqueueOp] = _ =>
      if buf.position() > 0 then {
        buf.flip()
        server.reactor.submit(write, onWrite)
      }
    lazy val onWrite: Completion[KqueueOp] = _ =>
      if buf.hasRemaining() then server.reactor.submit(write, onWrite)
      else {
        buf.clear()
        server.reactor.submit(read, onRead)
      }
    server.reactor.submit(read, onRead)
  }

  /** Runs `count` round trips, each on `reactors(i)` with `conns(i)`, where `i` changes after every `every` of them,
    * and returns the microseconds each took. The state is used by one thread at a time, the reactor running the current
    * round trip, and passes between them through a reactor's task queue.
    */
  private def measure(reactors: Array[Driven], conns: Array[Conn], count: Int, every: Int): Double = {
    val done = new CountDownLatch(1)
    var start = 0L
    var completed = 0
    var current = 0
    def roundTrip(): Unit = {
      val conn = conns(current)
      conn.buf.clear()
      reactors(current).reactor.submit(conn.write, onWrite)
    }
    lazy val onWrite: Completion[KqueueOp] = _ => {
      val conn = conns(current)
      if conn.buf.hasRemaining() then reactors(current).reactor.submit(conn.write, onWrite)
      else {
        conn.buf.clear()
        reactors(current).reactor.submit(conn.read, onRead)
      }
    }
    lazy val onRead: Completion[KqueueOp] = _ => {
      val conn = conns(current)
      if conn.buf.position() == 0 then throw new IllegalStateException("The echo server closed the connection")
      if conn.buf.hasRemaining() then reactors(current).reactor.submit(conn.read, onRead)
      else {
        completed += 1
        if completed == count then done.countDown()
        else if reactors.length > 1 && completed % every == 0 then {
          current = 1 - current
          reactors(current).post(roundTrip())
        } else roundTrip()
      }
    }
    reactors(0).post {
      start = System.nanoTime()
      roundTrip()
    }
    done.await()
    (System.nanoTime() - start) / 1e3 / count
  }

  /** Runs every way, or with `only` set to `stay`, `hand-off` or `move`, that way alone, taking turns every `every`. */
  def run(roundTrips: Int, only: String = "", every: Int = 1): Unit = {
    val zone = Zone.open()
    val path = s"/tmp/move-bench-${System.nanoTime() % 1_000_000}.sock"
    val address = UnixAddress(path)
    Files.deleteIfExists(Paths.get(path))
    val server = new Driven("move-server")
    val a = new Driven("move-a")
    val b = new Driven("move-b")
    try {
      val listener = KqueueHandles.listen(address)
      def connection(): Conn = {
        val client = KqueueHandles.connect(address) // a Unix socket connects at once
        var accepted = NonBlocking.accept(KqueueHandles.descriptor(listener))
        while accepted < 0 do {
          Thread.`yield`()
          accepted = NonBlocking.accept(KqueueHandles.descriptor(listener))
        }
        echo(server, KqueueHandles.connection(accepted), zone)
        new Conn(client, zone)
      }
      val first = connection()
      val second = connection()
      def line(name: String, us: Double): Unit = println(f"move-bench: $name%-18s $us%7.2f us per round trip")
      def runAll(): Unit = {
        measure(Array(a), Array(first), roundTrips / 10, Int.MaxValue) // warm-up
        line("stay", measure(Array(a), Array(first), roundTrips, Int.MaxValue))
        for every <- Seq(1, 4, 16, 64) do {
          val handOff = measure(Array(a, b), Array(first, second), roundTrips, every)
          val move = measure(Array(a, b), Array(first, first), roundTrips, every)
          line(s"hand-off every $every", handOff)
          line(s"move every $every", move)
          println(f"move-bench: each move costs ${(move - handOff) * every}%.2f us beyond the hand-off (every $every)")
        }
      }
      only match {
        case "stay"     => line("stay", measure(Array(a), Array(first), roundTrips, Int.MaxValue))
        case "hand-off" => line(s"hand-off every $every", measure(Array(a, b), Array(first, second), roundTrips, every))
        case "move"     => line(s"move every $every", measure(Array(a, b), Array(first, first), roundTrips, every))
        case ""         => runAll()
        case other      => throw new IllegalArgumentException(s"Unknown way `$other`; try stay, hand-off or move")
      }
    } finally {
      server.stop()
      a.stop()
      b.stop()
      Files.deleteIfExists(Paths.get(path))
    }
  }
}
