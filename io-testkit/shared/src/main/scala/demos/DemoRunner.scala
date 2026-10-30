package gears.io.demos

import gears.async.Async
import gears.async.AsyncOperations
import gears.async.io.Backend
import gears.async.io.Io

/** How a demo's program runs over a backend: what a backend's main application gives the shared demo commands
  * (`DemoCommands`), the one place that starts a run, so that the demos themselves only take an `Io` and an `Async` and
  * never name a backend. A main writes one per backend it offers, with `Io.run`.
  */
trait DemoRunner[+B <: Backend] {

  /** The backend the programs run over, whose capabilities the commands check before running a demo that needs them.
    */
  val backend: B

  /** Runs `program` over the backend, as `Io.run` does. */
  def run[T](program: Io[B] ?=> (Async.Spawn, AsyncOperations) ?=> T): T
}

/** The backends a main application offers its demos, each by the name `--backend` chooses it with, and the one chosen
  * when `--backend` is not given, `default`.
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
}
