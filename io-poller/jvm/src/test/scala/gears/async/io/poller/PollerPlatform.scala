package gears.async.io
package poller

import gears.async.Async
import gears.async.io.nio.NioOps
import gears.async.io.nio.NioPoolBackend
import gears.async.io.nio.NioPoolScheduler
import gears.async.io.shareable.Starvation
import gears.async.io.shareable.WorkpoolScheduler

/** The pool backend the shared tests run over on the JVM: `NioPoolBackend`, whose futures its workers carry. */
object PollerPlatform {
  val name = "nio-pool"

  val backend: NioPoolBackend.type = NioPoolBackend

  /** Every backend of this platform's, for a check made of each: the one on the JDK's threads besides. */
  val all: List[(String, Backend { type Interface = NioOps.type })] =
    List("on the JDK's threads" -> gears.async.io.nio.NioBackend, "on the pool" -> NioPoolBackend)

  def runOn[T](workers: Int, onStarvation: Starvation.Handler)(
      program: Io[NioOps.type] ?=> Async.Spawn ?=> T
  ): T = NioPoolBackend.runOn(workers, onStarvation)(program)

  def scheduler(workers: Int, onStarvation: Starvation.Handler): WorkpoolScheduler[NioOps.type] =
    new NioPoolScheduler(workers, onStarvation)

  /** Not here: the JVM keeps a worker for longer than the threshold as it loads and compiles what a test first runs. */
  val failsOnStarvation = false
}
