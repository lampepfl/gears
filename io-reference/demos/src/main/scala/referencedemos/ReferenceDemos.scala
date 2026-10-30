package referencedemos

import gears.async.Async
import gears.async.AsyncOperations
import gears.async.io.Io
import gears.async.io.reference.ReferenceBackend
import gears.io.demos.{DemoBackends, DemoCommands, DemoRunner}

/** The gears demos over the reference backend: the testkit's demo command line (`DemoCommands`), run as
  * `sbt "ioReferenceDemos/run <command>"`. The demos that need FIFOs or Unix datagram sockets, which the reference
  * backend lacks, fail saying so.
  */
object ReferenceDemos {
  private object Runner extends DemoRunner[ReferenceBackend.type] {
    val backend: ReferenceBackend.type = ReferenceBackend
    def run[T](program: Io[ReferenceBackend.type] ?=> (Async.Spawn, AsyncOperations) ?=> T): T =
      Io.run(using backend)(program)
  }

  val backends: DemoBackends = DemoBackends(Seq("reference" -> Runner), default = "reference")

  def main(args: Array[String]): Unit = DemoCommands(backends).main(args)
}
