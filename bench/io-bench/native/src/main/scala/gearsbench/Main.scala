package gearsbench

import mainargs.{ParserForMethods, arg, main}

/** The bench on Scala Native, the executable `iobench` that bench/build.sc builds for bench/compare.sc: `iobench gears
  * <command>` runs the gears scenarios (`BenchCommands`) over the kqueue demos' backends (`example.GearsDemos`), and
  * `iobench <command>` the same scenarios written against `KqueueLoop` directly (`KQueueExampleBench`), the reference
  * compare.sc runs for a build marked `@raw`.
  */
object Main {

  /** The gears bench over kqueue, with kqueue's own `move-bench`. */
  final class KqueueBenchCommands extends BenchCommands(example.GearsDemos.backends) {
    @main(name = "move-bench", doc = "Measures what moving a descriptor between kqueue reactors costs")
    def moveBench(
        @arg(name = "round-trips") roundTrips: Int = 100_000,
        @arg(doc = "stay, hand-off or move alone, instead of every way") only: String = "",
        @arg(doc = "with --only, how many round trips between turns") every: Int = 1
    ): Unit = {
      GearsMoveBench.run(roundTrips, only, every)
    }
  }

  /** The bench against `KqueueLoop`, and what bench/build.sc checks an executable with. */
  object Raw {
    @main(doc = "The gears bench scenarios against KqueueLoop directly, with no reactor; see bench/compare.sc")
    def bench(
        @arg(doc = "comma-separated scenarios to run, or all of them when empty") scenarios: String = "",
        @arg(doc = "multiplies every scenario's operation count") scale: Double = 1.0,
        @arg(doc = "connections in the concurrent scenario") connections: Int = 64,
        @arg(doc = "use TCP on 127.0.0.1 at --port instead of a Unix socket") tcp: mainargs.Flag,
        @arg port: Int = 19876
    ): Unit = {
      val address =
        if tcp.value then gears.async.io.IpAddress.IPv4("127.0.0.1", port)
        else gears.async.io.UnixAddress(s"/tmp/kqueue-bench-${System.nanoTime() % 1_000_000}.sock")
      KQueueExampleBench.run(scenarios.split(',').toSeq.map(_.trim).filter(_.nonEmpty), scale, connections, address)
    }
    @main(name = "target-info", doc = "How the executable was linked: bench/build.sc checks for a release build")
    def targetInfo(): Unit = {
      (
        s"target.arch: ${scalanative.meta.LinktimeInfo.target.arch}",
        s"target.vendor: ${scalanative.meta.LinktimeInfo.target.vendor}",
        s"target.os: ${scalanative.meta.LinktimeInfo.target.os}",
        s"target.env: ${scalanative.meta.LinktimeInfo.target.env}",
        s"continuations: ${scalanative.meta.LinktimeInfo.isContinuationsSupported}",
        s"gc: ${scalanative.meta.LinktimeInfo.garbageCollector}",
        s"multithreaded: ${scalanative.meta.LinktimeInfo.isMultithreadingEnabled}",
        s"debug: ${scalanative.meta.LinktimeInfo.debugMode}",
        s"release: ${scalanative.meta.LinktimeInfo.releaseMode}"
      ).productIterator.foreach(println)
    }
  }

  def main(args: Array[String]): Unit = {
    if args.headOption.contains("gears") then ParserForMethods(KqueueBenchCommands()).runOrExit(args.tail.toIndexedSeq)
    else ParserForMethods(Raw).runOrExit(args.toIndexedSeq)
  }
}
