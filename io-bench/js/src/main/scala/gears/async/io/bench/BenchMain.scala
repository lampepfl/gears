package gears.async.io
package bench

import gears.async.io.node.NodeBackend

import scala.scalajs.js

/** The bench over this branch's Node.js backend, with `CapBench`'s own arguments; `--workers` is taken and ignored,
  * Node having one thread. Linked with `ioCapBenchJS/fullLinkJS` and run as `node <linked>/main.js <arguments>`, since
  * Scala.js gives a main method no arguments: they are Node's, after the program's and the script's names. The
  * scenarios over what the backend lacks, pipes, blocking calls and Unix datagram sockets, are skipped.
  */
object BenchMain {
  def main(args: Array[String]): Unit = {
    val process = js.Dynamic.global.process
    val arguments = process.argv.asInstanceOf[js.Array[String]].toArray.drop(2)
    val rest = arguments.grouped(2).filter(_(0) != "--backend").flatten.toArray
    val (_, _, parsed) = CapBench.parse(rest)
    val running = js.async {
      Io.run(using NodeBackend)(io ?=> CapBench.run(io, parsed))
    }
    val failed: js.Function1[Any, Unit] = failure => {
      js.Dynamic.global.console.error(failure.asInstanceOf[js.Any])
      process.exit(1): Unit
    }
    running.`catch`(failed): Unit
  }
}
