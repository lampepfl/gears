package nodedemos

import gears.async.Async
import gears.async.io.Io
import gears.async.io.node.{NodeBackend, NodeOps}
import gears.io.demos.{DemoBackends, DemoCommands, DemoRunner}

import scala.scalajs.js

/** The gears demos over the Node.js backend: the testkit's demo command line (`DemoCommands`), linked with
  * `sbt ioNodeDemos/fastLinkJS` and run by Node.js with the command as its arguments, `node <linked>/main.js timer`.
  * The demos that need blocking ops, pipes or Unix datagram sockets, which the Node.js backend lacks, fail saying so.
  */
object NodeDemos {
  private object Runner extends DemoRunner[NodeOps.type] {
    def run[T](program: Io[NodeOps.type] ?=> Async.Spawn ?=> T): T =
      Io.run(using NodeBackend)(program)
  }

  val backends: DemoBackends = DemoBackends(Seq("node" -> Runner), default = "node")

  /** Scala.js gives a main method no arguments: they are Node's, after the program's and the script's names. A demo
    * suspends, so the command runs within `js.async`. A command that fails has said why by the time its promise is
    * rejected: the command line prints a demo's failure and then exits, which Scala.js cannot do for it, so that is
    * done here.
    */
  def main(args: Array[String]): Unit = {
    val process = js.Dynamic.global.process
    val arguments = process.argv.asInstanceOf[js.Array[String]].toArray.drop(2)
    val running = js.async {
      DemoCommands(backends).main(arguments)
    }
    val failed: js.Function1[Any, Unit] = _ => process.exit(1): Unit
    running.`catch`(failed): Unit
  }
}
