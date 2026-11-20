package gears.io.demos

import gears.async.Async
import gears.async.io.Interface
import gears.async.io.Io

/** How a demo's program runs over a backend: what a backend's main application gives the shared demo commands
  * (`DemoCommands`), the one place that starts a run, so that the demos themselves only take an `Io` and an `Async` and
  * never name a backend. A main writes one per backend it offers, with `Io.run`.
  */
trait DemoRunner[+I <: Interface] {

  /** Runs `program` over the backend, as `Io.run` does: its `Io` has the backend's ops, whose capabilities the commands
    * check before running a demo that needs them.
    */
  def run[T](program: Io[I] ?=> Async.Spawn ?=> T): T
}

/** The backends a main application offers its demos, each by the name `--backend` chooses it with, and the one chosen
  * when `--backend` is not given, `default`.
  */
final class DemoBackends(val offered: Seq[(String, DemoRunner[Interface])], val default: String) {
  require(offered.nonEmpty, "A main application offers at least one backend")
  require(offered.exists(_._1 == default), s"The default backend `$default` is not one of ${names.mkString(", ")}")

  /** The names of the backends offered, in order. */
  def names: Seq[String] = offered.map(_._1)

  /** The backend named, or the default when none is: unknown names are refused, listing the ones there are. */
  def named(name: Option[String]): (String, DemoRunner[Interface]) = {
    val chosen = name.getOrElse(default)
    offered
      .find(_._1 == chosen)
      .getOrElse(throw new IllegalArgumentException(s"Unknown backend `$chosen`; try ${names.mkString(", ")}"))
  }

  /** The runner of the backend named, or of the default, as one over a backend whose ops are `C`, which `has`
    * recognises: for a demo that needs `C`, which fails, naming `needs`, if the backend lacks it.
    */
  def needing[C <: Interface](
      name: Option[String],
      needs: String
  )(has: PartialFunction[Interface, C]): DemoRunner[C] = {
    val (chosen, runner) = named(name)
    if runner.run(io ?=> has.isDefinedAt(io.ops)) then
      // The ops of the `Io` the runner gives its programs are a `C`: so is every `Io` it gives them.
      runner.asInstanceOf[DemoRunner[C]]
    else throw new IllegalArgumentException(s"The backend `$chosen` has no $needs, which this demo needs")
  }
}
