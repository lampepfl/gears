package cebench

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.IORuntimeConfig
import cats.syntax.all.*
import com.comcast.ip4s.GenSocketAddress
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import com.comcast.ip4s.SocketAddress
import com.comcast.ip4s.UnixSocketAddress
import fs2.Chunk
import fs2.Stream
import fs2.io.net.Network
import fs2.io.net.Socket
import mainargs.Flag
import mainargs.ParserForMethods
import mainargs.arg
import mainargs.main

/** The scenarios of `GearsBench` written with cats-effect and fs2, to compare against: the same echo server, message
  * size, operation counts and result lines, so `bench/compare.sc` reads them alike (pass the executable as
  * `<label>=<path>@raw`, since it runs as `cebench bench`). As in `GearsBench`, the echo server runs on a runtime of
  * its own and the clients on another, each with one worker thread, polling its own kqueue, unless `--workers` says
  * otherwise. `pingpong-fresh-ops` has no counterpart: fs2 builds no op values to reuse. On the JVM the workers poll a
  * `Selector` each instead, for TCP only: fs2 does Unix sockets there as blocking calls on the blocking pool.
  * `--warmup` runs every scenario first at a fifth of its count, as `GearsBench` does.
  */
object CeBench {

  /** The size of each message a client sends and the server echoes back. */
  inline val MessageSize = 64

  /** How many timers the `timers` scenario has pending at once. */
  inline val TimerBatch = 1000

  final case class Scenario(name: String, baseCount: Int, body: (GenSocketAddress, Int, Int) => IO[Unit])

  val scenarios: Seq[Scenario] = Seq(
    Scenario("pingpong", 50_000, (address, count, _) => pingPong(address, count, deadline = false)),
    Scenario("pingpong-deadline", 50_000, (address, count, _) => pingPong(address, count, deadline = true)),
    Scenario(
      "concurrent",
      100_000,
      (address, count, connections) => {
        val each = math.max(1, count / connections)
        (1 to connections).toList.parTraverse_(_ => pingPong(address, each, deadline = false))
      }
    ),
    Scenario("connections", 20_000, (address, count, _) => repeat(count)(pingPong(address, 1, deadline = false))),
    Scenario("pipelined", 200_000, (address, count, _) => pipelined(address, count)),
    Scenario("blocking", 100_000, (_, count, _) => repeat(count)(IO.blocking(()))),
    Scenario(
      "blocking-io",
      50_000,
      (address, count, _) => connect(address).use(s => repeat(count)(IO.blocking(()) >> roundTrip(s)))
    ),
    Scenario("timers", 200_000, (_, count, _) => timerStress(count)),
    Scenario("fairness", 200_000, (address, count, _) => fairness(address, count)),
    Scenario("starvation", 100_000, (address, count, connections) => starvation(address, count, connections)),
    Scenario("mixed", 50_000, (address, count, _) => mixed(address, count))
  )

  private val message: Chunk[Byte] = Chunk.array(Array.fill[Byte](MessageSize)(1))

  /** Runs `io` `n` times, one after another. */
  private def repeat(n: Int)(io: IO[Unit]): IO[Unit] =
    if n <= 0 then IO.unit else io >> repeat(n - 1)(io)

  /** Runs `io` until `done` holds, counting the runs in `count`. */
  private def until(done: Ref[IO, Boolean], count: Ref[IO, Long])(io: IO[Unit]): IO[Unit] =
    done.get.flatMap(if _ then IO.unit else io >> count.update(_ + 1) >> until(done, count)(io))

  /** Connects to `address`, trying again while the server turns the connection away. On the JVM, fs2 listens with the
    * JDK's default backlog of 50, which `concurrent`'s 64 connections at once overflow; on Scala Native it is 128.
    */
  private def connect(address: GenSocketAddress): cats.effect.Resource[IO, Socket[IO]] =
    Network[IO].connect(address).handleErrorWith {
      case _: java.net.ConnectException | _: java.net.SocketException =>
        cats.effect.Resource.eval(IO.sleep(100.micros)) >> connect(address)
      case e => cats.effect.Resource.raiseError[IO, Socket[IO], Throwable](e)
    }

  /** Sends one message and waits for all of its echo. */
  private def roundTrip(socket: Socket[IO]): IO[Unit] =
    socket.write(message) >> socket.readN(MessageSize).flatMap { echoed =>
      if echoed.size < MessageSize then IO.raiseError(new IOException("The server closed the connection"))
      else IO.unit
    }

  /** Sends `count` messages one at a time, waiting for each echo before sending the next. With `deadline`, each round
    * trip also has a timeout, which is cancelled once the echo is back.
    */
  private def pingPong(address: GenSocketAddress, count: Int, deadline: Boolean): IO[Unit] =
    connect(address).use { socket =>
      repeat(count)(if deadline then roundTrip(socket).timeout(60.seconds) else roundTrip(socket))
    }

  /** Writes `count` messages, each as its own write, from one fiber while reading the echoes in another. */
  private def pipelined(address: GenSocketAddress, count: Int): IO[Unit] =
    connect(address).use { socket =>
      val total = count.toLong * MessageSize
      def read(received: Long): IO[Unit] =
        if received >= total then IO.unit
        else
          socket.read(8192).flatMap {
            case Some(chunk) => read(received + chunk.size)
            case None        => IO.raiseError(new IOException("The server closed the connection"))
          }
      (repeat(count)(socket.write(message)), read(0)).parTupled.void
    }

  /** Starts timers `TimerBatch` at a time, each a fiber sleeping: half for 0 ms, which are waited for, and half for a
    * minute, which are cancelled once the others have finished.
    */
  private def timerStress(count: Int): IO[Unit] = {
    def batch(size: Int): IO[Unit] =
      for {
        short <- List.fill((size + 1) / 2)(IO.sleep(Duration.Zero).start).sequence
        long <- List.fill(size / 2)(IO.sleep(60.seconds).start).sequence
        _ <- short.traverse_(_.join)
        _ <- long.traverse_(_.cancel)
      } yield ()
    def from(done: Int): IO[Unit] =
      if done >= count then IO.unit
      else {
        val size = math.min(TimerBatch, count - done)
        batch(size) >> from(done + size)
      }
    from(0)
  }

  /** Runs `pipelined` while a ticker fiber sleeps for zero time over and over, and reports the ticker as
    * `fairness-tick`: the time between ticks over the same period.
    */
  private def fairness(address: GenSocketAddress, count: Int): IO[Unit] =
    for {
      done <- Ref[IO].of(false)
      ticks <- Ref[IO].of(0L)
      start <- IO.monotonic
      ticker <- until(done, ticks)(IO.sleep(Duration.Zero)).start
      _ <- pipelined(address, count)
      _ <- done.set(true)
      end <- IO.monotonic
      _ <- ticker.join
      ticked <- ticks.get
      _ <- IO(report("fairness-tick", math.max(ticked, 1L), (end - start).toNanos / 1e9))
    } yield ()

  // Where compute slices leave their results, so that the work cannot be optimised away.
  @volatile private var computeSink = 0L

  /** About ten thousand steps of arithmetic: a slice of CPU-bound work, as in `GearsBench`. */
  private def computeSlice(seed: Long): Long = {
    var x = seed | 1L
    var i = 0
    while i < 10_000 do {
      x ^= x << 13
      x ^= x >>> 7
      x ^= x << 17
      i += 1
    }
    x
  }

  /** Ping-pongs `count` messages while a compute fiber runs slices of work, ceding between them, and reports the
    * compute side as `mixed-compute`: the time per slice over the same period.
    */
  private def mixed(address: GenSocketAddress, count: Int): IO[Unit] =
    for {
      done <- Ref[IO].of(false)
      slices <- Ref[IO].of(0L)
      start <- IO.monotonic
      compute <- until(done, slices)(IO(computeSink += computeSlice(computeSink)) >> IO.cede).start
      _ <- pingPong(address, count, deadline = false)
      _ <- done.set(true)
      end <- IO.monotonic
      _ <- compute.join
      sliced <- slices.get
      _ <- IO(report("mixed-compute", math.max(sliced, 1L), (end - start).toNanos / 1e9))
    } yield ()

  /** As `GearsBench`'s: `concurrent`'s round trips while a compute fiber per core works in slices, ceding between them,
    * and a checker sleeps 1 ms over and over, recording how late it wakes, as cats-effect's own CPU starvation check
    * does (`CpuStarvationCheck`, which sleeps a second). Reports the median, 99th percentile and worst lateness, in µs,
    * as `starvation-p50`, `-p99` and `-max`.
    */
  private def starvation(address: GenSocketAddress, count: Int, connections: Int): IO[Unit] = {
    val interval = 1.millis
    for {
      done <- Ref[IO].of(false)
      late <- Ref[IO].of(Vector.empty[Long])
      checker <- {
        val check = for {
          started <- IO.monotonic
          _ <- IO.sleep(interval)
          ended <- IO.monotonic
          _ <- late.update(_ :+ math.max(0L, (ended - started - interval).toNanos))
        } yield ()
        def loop: IO[Unit] = done.get.flatMap(if _ then IO.unit else check >> loop)
        loop.start
      }
      computes <- (1 to Runtime.getRuntime.availableProcessors).toList.traverse { core =>
        def loop(slice: Long): IO[Unit] =
          done.get.flatMap(
            if _ then IO.unit
            else IO(computeSink += computeSlice(slice)) >> IO.cede >> loop(slice + 1)
          )
        loop(core.toLong).start
      }
      each = math.max(1, count / connections)
      _ <- (1 to connections).toList.parTraverse_(_ => pingPong(address, each, deadline = false))
      _ <- done.set(true)
      _ <- checker.join
      _ <- computes.traverse_(_.join)
      sorted <- late.get.map(_.sorted)
      _ <- IO {
        def at(fraction: Double): Long = if sorted.isEmpty then 0L else sorted(((sorted.length - 1) * fraction).toInt)
        report("starvation-p50", 1, at(0.5) / 1e9)
        report("starvation-p99", 1, at(0.99) / 1e9)
        report("starvation-max", 1, at(1.0) / 1e9)
      }
    } yield ()
  }

  /** Prints one result line, in the format `bench/compare.sc` reads. */
  def report(name: String, count: Long, seconds: Double): Unit =
    println(f"bench: $name%-18s $count%10d $seconds%9.3f ${count / seconds}%12.0f ${seconds * 1e6 / count}%8.2f")

  /** Echoes everything a connection sends until it closes; a client that goes away ends only its own connection. A
    * plain loop of reads and writes, as `GearsBench`'s server is, rather than a stream.
    */
  private def echo(socket: Socket[IO]): IO[Unit] = {
    def loop: IO[Unit] = socket.read(8192).flatMap {
      case Some(chunk) => socket.write(chunk) >> loop
      case None        => IO.unit
    }
    loop.handleError(_ => ())
  }

  /** Listens on `address`, counts down `listening`, then echoes every connection, each in a fiber of its own. */
  private def serve(address: GenSocketAddress, listening: CountDownLatch): IO[Unit] =
    Network[IO].bind(address).use { server =>
      IO(listening.countDown()) >>
        server.accept.map(socket => Stream.eval(echo(socket))).parJoinUnbounded.compile.drain
    }

  /** A runtime of `workers` threads, each polling its own kqueue (a `Selector` on the JVM), as cats-effect's default
    * runtime is built.
    */
  private def runtime(workers: Int, prefix: String): IORuntime = {
    val (compute, poller, computeDown) =
      IORuntime.createWorkStealingComputeThreadPool(threads = workers, threadPrefix = prefix)
    val (blocking, blockingDown) = IORuntime.createDefaultBlockingExecutionContext(s"$prefix-blocking")
    IORuntime(compute, blocking, compute, List(poller), () => { computeDown(); blockingDown() }, IORuntimeConfig())
  }

  @main(doc = "The gears bench scenarios with cats-effect and fs2; see bench/compare.sc")
  def bench(
      @arg(doc = "comma-separated scenarios to run, or all of them when empty") scenarios: String = "",
      @arg(doc = "multiplies every scenario's operation count") scale: Double = 1.0,
      @arg(doc = "connections in the concurrent scenario") connections: Int = 64,
      @arg(doc = "use TCP on 127.0.0.1 at --port instead of a Unix socket") tcp: Flag,
      @arg port: Int = 19876,
      @arg(doc = "worker threads for the clients, and as many again for the server") workers: Int = 1,
      @arg(doc = "run every scenario once at a fifth of its count first, so the JIT has compiled it") warmup: Flag
  ): Unit = {
    val names = scenarios.split(',').toSeq.map(_.trim).filter(_.nonEmpty)
    val selected =
      if names.isEmpty then this.scenarios
      else
        names.map(name =>
          this.scenarios
            .find(_.name == name)
            .getOrElse(
              throw new IllegalArgumentException(
                s"Unknown scenario `$name`; try ${this.scenarios.map(_.name).mkString(", ")}"
              )
            )
        )
    val unixPath = s"/tmp/ce-bench-${System.nanoTime() % 1_000_000}.sock"
    val address: GenSocketAddress =
      if tcp.value then SocketAddress(Host.fromString("127.0.0.1").get, Port.fromInt(port).get)
      else UnixSocketAddress(unixPath)
    Files.deleteIfExists(Paths.get(unixPath))
    val serverRuntime = runtime(workers, "bench-server")
    val clientRuntime = runtime(workers, "bench-client")
    try {
      val listening = new CountDownLatch(1)
      serve(address, listening).unsafeRunAndForget()(using serverRuntime)
      listening.await()
      // On the JVM, a first pass at a fifth of each count, so the JIT has compiled what is measured; the few results
      // a scenario prints itself come before the measured ones, which `compare.sc` keeps.
      val warm =
        if warmup.value then
          selected.toList.traverse_(scenario =>
            scenario.body(address, math.max(1, (scenario.baseCount * scale / 5).toInt), connections)
          )
        else IO.unit
      val program =
        warm >> IO.println(f"bench: ${"scenario"}%-18s ${"ops"}%10s ${"seconds"}%9s ${"ops/s"}%12s ${"us/op"}%8s") >>
          selected.toList.traverse_ { scenario =>
            val count = math.max(1, (scenario.baseCount * scale).toInt)
            for {
              start <- IO.monotonic
              _ <- scenario.body(address, count, connections)
              end <- IO.monotonic
              _ <- IO(report(scenario.name, count, (end - start).toNanos / 1e9))
            } yield ()
          }
      program.unsafeRunSync()(using clientRuntime)
    } finally Files.deleteIfExists(Paths.get(unixPath))
    // The server's fibers serve until the process ends, so end it rather than wait for the runtimes to wind down.
    System.out.flush()
    sys.exit(0)
  }

  @main(name = "target-info", doc = "How the executable was built")
  def targetInfo(): Unit = Platform.targetInfo.foreach(println)

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args.toIndexedSeq)
}
