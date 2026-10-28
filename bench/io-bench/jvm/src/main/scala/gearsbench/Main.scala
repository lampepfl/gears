package gearsbench

import mainargs.{ParserForMethods, arg, main}

/** The bench on the JVM, run by the launchers bench/build_jvm.sc writes: the gears scenarios (`BenchCommands`) over the
  * NIO demos' backends (`niodemos.NioDemos`), `--backend caller` or `reactors`, its default `caller` or what
  * `-Dgears.io.nio.backend` names; and the same scenarios with the JDK alone (`jdk-bench`). Its commands may also be
  * run as `gears <command>`, as on Scala Native.
  */
object Main {

  /** The gears bench over NIO, with the JDK's own `jdk-bench`. */
  final class NioBenchCommands extends BenchCommands(niodemos.NioDemos.backends) {
    @main(
      name = "jdk-bench",
      doc = "The bench scenarios with the JDK alone: blocking channels on virtual threads, in structured task scopes " +
        "(needs --enable-preview), over a Unix socket"
    )
    def jdkBench(
        @arg(doc = "comma-separated scenarios to run, or all of them when empty") scenarios: String = "",
        @arg(doc = "multiplies every scenario's operation count") scale: Double = 1.0,
        @arg(doc = "connections in the concurrent scenario") connections: Int = 64,
        @arg(doc =
          "run every scenario once at a fifth of its count first, so the JIT has compiled it"
        ) warmup: mainargs.Flag,
        @arg(doc =
          "ignored, so the same flags as bench's can be passed: the JDK's virtual thread scheduler decides; set "
            + "-Djdk.virtualThreadScheduler.parallelism to bound it"
        ) workers: Int = 1,
        @arg(doc =
          "do the I/O with non-blocking channels and a selector thread for each side, instead of blocking ones"
        )
        selector: mainargs.Flag,
        @arg(
          name = "selector-here",
          doc = "do the I/O with non-blocking channels, each waiting virtual thread selecting on the channel's own " +
            "selector"
        )
        selectorHere: mainargs.Flag
    ): Unit =
      JdkBench.run(
        scenarios.split(',').toSeq.map(_.trim).filter(_.nonEmpty),
        scale,
        connections,
        java.nio.file.Path.of(s"/tmp/jdk-bench-${System.nanoTime() % 1_000_000}.sock"),
        warmup.value,
        selector.value,
        selectorHere.value
      )
  }

  def main(args: Array[String]): Unit = {
    val command = if args.headOption.contains("gears") then args.tail else args
    ParserForMethods(NioBenchCommands()).runOrExit(command.toIndexedSeq)
  }
}
