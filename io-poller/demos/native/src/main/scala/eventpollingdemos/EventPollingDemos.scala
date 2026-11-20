package eventpollingdemos

import gears.async.Async
import gears.async.io.Io
import gears.async.io.eventpolling.EventPollingBackend
import gears.io.demos.{DemoBackends, DemoCommands, DemoRunner}

import scala.scalanative.meta.LinktimeInfo

/** The gears demos over the native backend: the testkit's demo command line (`DemoCommands`), run as
  * `sbt "ioPollerDemosNative/run <command>"`. The backend is over the event queue of the system the program is linked
  * for. `eventpolling`, the default, is whichever that is; it is also offered by the queue's own name, `epoll` on Linux
  * and `kqueue` on macOS and FreeBSD, and the other's name is not a backend of the program. It has every capability the
  * demos ask for.
  */
object EventPollingDemos {
  private object Runner extends DemoRunner[EventPollingBackend.Interface] {
    val backend: EventPollingBackend.type = EventPollingBackend
    def run[T](program: Io[EventPollingBackend.Interface] ?=> Async.Spawn ?=> T): T =
      Io.run(using backend)(program)
  }

  /** The event queue of the system this program is linked for. */
  val queue: String = if LinktimeInfo.isLinux then "epoll" else "kqueue"

  val backends: DemoBackends = DemoBackends(Seq("eventpolling" -> Runner, queue -> Runner), default = "eventpolling")

  def main(args: Array[String]): Unit = DemoCommands(backends).main(args)
}
