package niodemos

import gears.async.Async
import gears.async.io.Io
import gears.async.io.nio.{NioBackend, NioOps, NioPoolBackend}
import gears.io.demos.{DemoBackends, DemoCommands, DemoRunner}

/** The gears demos over the JVM backends: the testkit's demo command line (`DemoCommands`), run as
  * `sbt "ioPollerDemosJVM/run <command>"`, on the JDK's virtual threads, or with `--backend nio-pool` on virtual
  * threads that the backend's own pool carries. The demos that need FIFOs or Unix datagram sockets, which the JDK
  * lacks, fail saying so.
  */
object NioDemos {
  private object OnThreads extends DemoRunner[NioOps.type] {
    def run[T](program: Io[NioOps.type] ?=> Async.Spawn ?=> T): T = Io.run(using NioBackend)(program)
  }

  private object OnPool extends DemoRunner[NioOps.type] {
    def run[T](program: Io[NioOps.type] ?=> Async.Spawn ?=> T): T = Io.run(using NioPoolBackend)(program)
  }

  val backends: DemoBackends = DemoBackends(Seq("nio" -> OnThreads, "nio-pool" -> OnPool), default = "nio")

  def main(args: Array[String]): Unit = DemoCommands(backends).main(args)
}
