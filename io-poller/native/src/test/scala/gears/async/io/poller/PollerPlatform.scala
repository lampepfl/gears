package gears.async.io
package poller

import gears.async.Async
import gears.async.io.eventpolling.EventPollingBackend
import gears.async.io.eventpolling.EventPollingOps
import gears.async.io.shareable.Starvation
import gears.async.io.shareable.WorkpoolScheduler

import scala.scalanative.meta.LinktimeInfo

/** The pool backend the shared tests run over on Scala Native: `EventPollingBackend`, over kqueue or epoll by the
  * system the tests are linked for.
  */
object PollerPlatform {
  val name = if LinktimeInfo.isLinux then "epoll" else "kqueue"

  val backend: EventPollingBackend.type = EventPollingBackend

  /** Every backend of this platform's, for a check made of each. */
  val all: List[(String, Backend { type Interface = EventPollingOps.type })] = List(
    s"over $name" -> EventPollingBackend
  )

  def runOn[T](workers: Int, onStarvation: Starvation.Handler)(
      program: Io[EventPollingOps.type] ?=> Async.Spawn ?=> T
  ): T = EventPollingBackend.runOn(workers, onStarvation)(program)

  def scheduler(workers: Int, onStarvation: Starvation.Handler): WorkpoolScheduler[EventPollingOps.type] =
    EventPollingBackend.scheduler(workers, onStarvation)

  val failsOnStarvation = true
}
