package example

import gears.async.Async
import gears.async.AsyncOperations
import gears.async.io.Io
import gears.async.io.internal.ReactorRuns
import gears.async.io.internal.TakePolicy
import gears.async.io.kqueue.KqueueBackend
import gears.io.demos.{DemoBackends, DemoCommands, DemoRunner}

/** The gears mirrors of the plain demos, run as `iokqueuedemos gears <command>` with the same commands and flags: the
  * testkit's demo command line (`DemoCommands`) over kqueue.
  *
  * This is the edge: the demos only know the `gears.async.io` interface, and this is the one place that chooses kqueue
  * and starts runs over it. Kqueue has every capability the demos ask for.
  */
object GearsDemos {

  /** Runs programs over kqueue: with `Io.run`, or with `ReactorRuns.run` on a pool of the workers asked for. */
  private object KqueueRunner extends DemoRunner[KqueueBackend.type] {
    val backend: KqueueBackend.type = KqueueBackend

    def run[T](program: Io[KqueueBackend.type] ?=> (Async.Spawn, AsyncOperations) ?=> T): T =
      Io.run(using backend)(program)

    def onPool[T](workers: Int, policy: TakePolicy)(
        program: Io[KqueueBackend.type] ?=> (Async.Spawn, AsyncOperations) ?=> T
    ): T =
      ReactorRuns.run(workers, policy)(using backend)(program)
  }

  /** The one backend here, `kqueue`. */
  val backends: DemoBackends = DemoBackends(Seq("kqueue" -> KqueueRunner), default = "kqueue")

  def main(args: Array[String]): Unit = DemoCommands(backends).main(args)
}
