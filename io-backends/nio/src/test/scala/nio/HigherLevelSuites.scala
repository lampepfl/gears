package gears.async.io.nio

import gears.async.Async
import gears.async.AsyncOperations
import gears.async.io.Io
import gears.async.io.internal.ReactorRuns
import gears.async.io.nio.NioBackend
import gears.async.io.nio.NioOnCaller
import gears.io.higherlevel.HigherLevelChecks

/** The higher level over `NioOnCaller`: gears' own virtual threads, each op performed on its own thread. */
class NioOnCallerHigherLevelSuite extends HigherLevelChecks {
  val backend: NioOnCaller.type = NioOnCaller

  protected def running[T](body: Io[backend.type] ?=> (Async.Spawn, AsyncOperations) ?=> T): T =
    Io.run(using backend)(body)
}

/** The higher level over `NioBackend`, on a pool of several workers driving its reactors. */
class NioReactorsHigherLevelSuite extends HigherLevelChecks {
  val backend: NioBackend.type = NioBackend

  protected def running[T](body: Io[backend.type] ?=> (Async.Spawn, AsyncOperations) ?=> T): T =
    ReactorRuns.run(4)(using backend)(body)
}
