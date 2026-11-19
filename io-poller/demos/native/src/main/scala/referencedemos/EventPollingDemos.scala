package referencedemos

import gears.async.Async
import gears.async.io.Io
import gears.async.io.kqueue.KqueueBackend
import gears.io.demos.{DemoBackends, DemoCommands, DemoRunner}

/** The gears demos over the kqueue backend: the testkit's demo command line (`DemoCommands`), run as
  * `sbt "ioKqueueDemos/run <command>"`. The kqueue backend has every capability the demos ask for.
  */
object KqueueDemos {
  private object Runner extends DemoRunner[KqueueBackend.Interface] {
    val backend: KqueueBackend.type = KqueueBackend
    def run[T](program: Io[KqueueBackend.Interface] ?=> Async.Spawn ?=> T): T =
      Io.run(using backend)(program)
  }

  val backends: DemoBackends = DemoBackends(Seq("kqueue" -> Runner), default = "kqueue")

  def main(args: Array[String]): Unit = DemoCommands(backends).main(args)
}
