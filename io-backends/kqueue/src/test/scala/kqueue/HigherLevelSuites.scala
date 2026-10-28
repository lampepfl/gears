package gears.async.io.kqueue

import gears.async.Async
import gears.async.AsyncOperations
import gears.async.io.Io
import gears.async.io.internal.ReactorRuns
import gears.async.io.kqueue.KqueueBackend
import gears.io.higherlevel.HigherLevelChecks

/** The higher level over kqueue's reactors, on a pool of four workers. */
class KqueueHigherLevelSuite extends HigherLevelChecks {
  val backend: KqueueBackend.type = KqueueBackend

  protected def running[T](body: Io[backend.type] ?=> (Async.Spawn, AsyncOperations) ?=> T): T =
    ReactorRuns.run(4)(using backend)(body)
}
