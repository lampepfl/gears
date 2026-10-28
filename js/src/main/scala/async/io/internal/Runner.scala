package gears.async.io
package internal

import gears.async.Async
import gears.async.AsyncOperations

/** What `Io.run` does on Scala.js: nothing yet, since no backend runs there (see docs/io/hosts.md). */
object Runner {

  /** For `OnCaller.yield`: false, to leave it to gears' own. */
  private[io] def yieldHere(): Boolean = false

  def run[T](using backend: Backend)(program: Io[backend.type] ?=> (Async.Spawn, AsyncOperations) ?=> T): T =
    throw new UnsupportedOperationException(s"No I/O backend runs on Scala.js yet, so $backend cannot")
}
