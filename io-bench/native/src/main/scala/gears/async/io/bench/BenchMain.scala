package gears.async.io
package bench

import gears.async.io.eventpolling.EventPollingBackend
import gears.async.io.uring.UringBackend

import scala.scalanative.meta.LinktimeInfo

/** The bench over this branch's native backends: `--backend kqueue` (`EventPollingBackend`, kqueue or epoll by the
  * system, the default; `epoll` names it too) or, on Linux, `--backend uring` (`UringBackend`), on `--workers` workers,
  * with `CapBench`'s own arguments.
  */
object BenchMain {
  def main(args: Array[String]): Unit = {
    if args.headOption.contains("suspend") then return SuspendBench.run(args.tail)
    var backend = "kqueue"
    val rest = scala.collection.mutable.ArrayBuffer[String]()
    var i = 0
    while i < args.length do
      if args(i) == "--backend" then backend = args(i + 1) else { rest += args(i); rest += args(i + 1) }
      i += 2
    val (workers, _, parsed) = CapBench.parse(rest.toArray)
    val warn = shareable.Starvation.printWarning
    backend match
      case "kqueue" | "epoll" => EventPollingBackend.runOn(workers, warn)(io ?=> CapBench.run(io, parsed))
      case "uring"            => uring(workers, parsed)
      case other              => throw new IllegalArgumentException(s"no backend $other: kqueue, epoll, uring (Linux)")
  }

  // - decided as the program is linked, so that a build for another system does not link liburing
  private def uring(workers: Int, parsed: CapBench.Args): Unit =
    if LinktimeInfo.isLinux then
      UringBackend.runOn(workers, shareable.Starvation.printWarning)(io ?=> CapBench.run(io, parsed))
    else throw new IllegalArgumentException("io_uring is Linux's alone")
}
