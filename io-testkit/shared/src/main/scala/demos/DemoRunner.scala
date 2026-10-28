package gears.io.demos

import gears.async.Async
import gears.async.AsyncOperations
import gears.async.io.Backend
import gears.async.io.Io
import gears.async.io.internal.ReactorBackend
import gears.async.io.internal.TakePolicy

/** How a demo's program runs over a backend: what a backend's main application gives the shared demo commands
  * (`DemoCommands`), the one place that starts a run, so that the demos themselves only take an `Io` and an `Async` and
  * never name a backend. A main writes one per backend it offers, with `Io.run` and, for a reactor backend,
  * `ReactorRuns.run`.
  */
trait DemoRunner[+B <: Backend] {

  /** The backend the programs run over, whose capabilities the commands check before running a demo that needs them.
    */
  val backend: B

  /** Runs `program` over the backend, as `Io.run` does. */
  def run[T](program: Io[B] ?=> (Async.Spawn, AsyncOperations) ?=> T): T

  /** Runs `program` over the backend on a pool of `workers` workers taking tasks by `policy`, for the demos and benches
    * that compare them: a backend with reactors runs it as `ReactorRuns.run` does; one without, which performs on the
    * caller, has no pool for them to shape, and runs it as `run` does.
    */
  def onPool[T](workers: Int, policy: TakePolicy)(program: Io[B] ?=> (Async.Spawn, AsyncOperations) ?=> T): T

  /** Whether the backend has reactors, which the demos that drive a reactor or need a pool of workers ask for. */
  final def hasReactors: Boolean = backend.isInstanceOf[ReactorBackend]
}

/** The backends a main application offers its demos, each by the name `--backend` chooses it with, and the one chosen
  * when `--backend` is not given, `default`. A demo that needs reactors (`GearsCancel`, `ReactorServerSocket`) runs on
  * the chosen backend if it has them, or else on the first offered that does, as one with none, such as NIO's
  * `NioOnCaller`, cannot run it.
  */
final class DemoBackends(val offered: Seq[(String, DemoRunner[Backend])], val default: String) {
  require(offered.nonEmpty, "A main application offers at least one backend")
  require(offered.exists(_._1 == default), s"The default backend `$default` is not one of ${names.mkString(", ")}")

  /** The names of the backends offered, in order. */
  def names: Seq[String] = offered.map(_._1)

  /** The backend named, or the default when none is: unknown names are refused, listing the ones there are. */
  def named(name: Option[String]): (String, DemoRunner[Backend]) = {
    val chosen = name.getOrElse(default)
    offered
      .find(_._1 == chosen)
      .getOrElse(throw new IllegalArgumentException(s"Unknown backend `$chosen`; try ${names.mkString(", ")}"))
  }

  /** The runner of the backend named, or of the default, as one over a backend with `C`, which `has` recognises: for a
    * demo that needs `C`, which fails, naming `needs`, if the backend lacks it.
    */
  def needing[C <: Backend](name: Option[String], needs: String)(has: PartialFunction[Backend, C]): DemoRunner[C] = {
    val (chosen, runner) = named(name)
    if has.isDefinedAt(runner.backend) then
      // The runner runs its programs over `runner.backend`, which is a `C`: so is every `Io` it gives them.
      runner.asInstanceOf[DemoRunner[C]]
    else throw new IllegalArgumentException(s"The backend `$chosen` has no $needs, which this demo needs")
  }

  /** The backend named, if it has reactors; or else the first offered that does, for a demo that needs them. */
  def withReactors(name: Option[String]): (String, DemoRunner[Backend]) = {
    val chosen = named(name)
    if chosen._2.hasReactors then chosen
    else
      offered
        .find(_._2.hasReactors)
        .getOrElse(
          throw new IllegalArgumentException(
            s"This demo drives a reactor, and none of the backends offered (${names.mkString(", ")}) has one"
          )
        )
  }
}
