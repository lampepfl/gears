package gears.async.io
package bench

import gears.async.io.nio.NioBackend
import gears.async.io.nio.NioPoolBackend

/** The bench over this branch's JVM backends: `--backend threads` (`NioBackend`, the default) or `--backend pool`
  * (`NioPoolBackend`, which needs `--add-opens java.base/java.lang=ALL-UNNAMED`), on `--workers` workers, with
  * `CapBench`'s own arguments.
  */
object BenchMain {
  def main(args: Array[String]): Unit = {
    var backend = "threads"
    val rest = scala.collection.mutable.ArrayBuffer[String]()
    var i = 0
    while i < args.length do
      if args(i) == "--backend" then backend = args(i + 1) else { rest += args(i); rest += args(i + 1) }
      i += 2
    val (workers, _, parsed) = CapBench.parse(rest.toArray)
    val warn = shareable.Starvation.printWarning
    backend match
      case "threads" => NioBackend.runOn(workers, warn)(io ?=> CapBench.run(io, parsed))
      case "pool"    => NioPoolBackend.runOn(workers, warn)(io ?=> CapBench.run(io, parsed))
      case other     => throw new IllegalArgumentException(s"no backend $other: threads, pool")
  }
}
