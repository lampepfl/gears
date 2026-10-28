package gears.async.io
package internal

import gears.async.Async
import gears.async.AsyncOperations

/** The runner's knobs, which `Io.run` chooses itself: how many workers a pool has, and which tasks a worker out of work
  * may take from another (`TakePolicy`). For benches, demos and tests that compare them, and as unsupported as the rest
  * of `internal`.
  */
object ReactorRuns {

  /** Runs `program` over `backend` on a pool of `workers` workers driving its reactors, under `policy`, as `Io.run`
    * does with a worker per available processor; a backend with no reactors, which performs on the caller, is refused
    * with an `IllegalArgumentException`, since a pool would have nothing to run.
    */
  def run[T](workers: Int, policy: TakePolicy = TakePolicy.Affine)(using
      backend: Backend
  )(program: Io[backend.type] ?=> (Async.Spawn, AsyncOperations) ?=> T): T =
    backend match {
      case reactors: (ReactorBackend & backend.type) => Runner.onPool(workers, policy)(using reactors)(program)
      case _                                         =>
        throw new IllegalArgumentException(
          s"$backend performs its ops on the caller, so a pool of workers has nothing to run; use Io.run"
        )
    }
}
