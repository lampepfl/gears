package referencedemos

import language.experimental.captureChecking
import caps.*

import gears.async.Async
import gears.async.io.Io
import gears.async.io.reference.{ReferenceBackend, ReferenceOps}
import gears.io.demos.{DemoBackends, DemoCommands, DemoRunner}

/** The gears demos over the reference backend: the testkit's demo command line (`DemoCommands`), run as
  * `sbt "ioReferenceDemos/run <command>"`. The demos that need FIFOs or Unix datagram sockets, which the reference
  * backend lacks, fail saying so.
  */
object ReferenceDemos {
  private object Runner extends DemoRunner[ReferenceOps.type] {
    def run[T](program: Io[ReferenceOps.type] ?->{any.except[Control]} Async.Spawn^ ?->{any.except[Control]} T): T =
      Io.run(using ReferenceBackend)(program)
  }

  val backends: DemoBackends = DemoBackends(Seq("reference" -> Runner), default = "reference")

  def main(args: Array[String]): Unit = DemoCommands(backends).main(args)
}
