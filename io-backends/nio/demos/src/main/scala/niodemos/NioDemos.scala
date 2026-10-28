package niodemos

import gears.async.Async
import gears.async.AsyncOperations
import gears.async.io.Backend
import gears.async.io.Io
import gears.async.io.internal.ReactorBackend
import gears.async.io.internal.ReactorRuns
import gears.async.io.internal.TakePolicy
import gears.async.io.nio.NioBackend
import gears.async.io.nio.NioOnCaller
import gears.io.demos.{DemoBackends, DemoCommands, DemoRunner}

/** The gears demos on the JVM, over NIO: the testkit's demo command line (`DemoCommands`), run as
  * `sbt "ioNioDemos/run <command>"`, with the commands and flags of `iokqueuedemos gears`. The demos that need FIFOs or
  * Unix datagram sockets, which NIO lacks, fail saying so.
  *
  * This is the edge, the one place that chooses NIO and starts runs: `--backend caller`, `NioOnCaller`, with no
  * reactor, each op performed on the future's own virtual thread; or `--backend reactors`, `NioBackend`, its futures
  * carried by the reactor pool (see `gears.async.io.Io.run`). The default is `caller`, or what `-Dgears.io.nio.backend`
  * names, which the bench's launchers set. The demos that drive a reactor (`cancel`, `reactor-sock-serve`) run on
  * `NioBackend` whichever is chosen, as `NioOnCaller` has none.
  */
object NioDemos {

  /** Runs programs over `backend` with `Io.run`, and on a pool of workers with `ReactorRuns.run` when it has reactors.
    */
  private final class NioRunner[B <: Backend](val backend: B) extends DemoRunner[B] {
    def run[T](program: Io[B] ?=> (Async.Spawn, AsyncOperations) ?=> T): T = Io.run(using backend)(program)

    def onPool[T](workers: Int, policy: TakePolicy)(program: Io[B] ?=> (Async.Spawn, AsyncOperations) ?=> T): T =
      backend match {
        case reactors: (ReactorBackend & backend.type) => ReactorRuns.run(workers, policy)(using reactors)(program)
        case _                                         => Io.run(using backend)(program)
      }
  }

  /** NIO's backends, by the names `--backend` and `-Dgears.io.nio.backend` choose them with. */
  val backends: DemoBackends = DemoBackends(
    Seq("caller" -> NioRunner(NioOnCaller), "reactors" -> NioRunner(NioBackend)),
    default = sys.props.getOrElse("gears.io.nio.backend", "caller")
  )

  def main(args: Array[String]): Unit = DemoCommands(backends).main(args)
}
