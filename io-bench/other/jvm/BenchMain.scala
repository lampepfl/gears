package gears.async.io
package bench

import gears.async.io.internal.ReactorRuns
import gears.async.io.internal.TakePolicy
import gears.async.io.nio.NioBackend
import gears.async.io.nio.NioOnCaller

/** The bench over the JVM backends of the branch compared against (`async-io-backends`): `--backend caller`
  * (`NioOnCaller`, the default) or `--backend reactors` (its `NioBackend`, on `--workers` workers). `Compare` puts this
  * in place of this branch's `BenchMain` in a worktree of that branch.
  */
object BenchMain {
  def main(args: Array[String]): Unit = {
    var backend = "caller"
    val rest = scala.collection.mutable.ArrayBuffer[String]()
    var i = 0
    while i < args.length do
      if args(i) == "--backend" then backend = args(i + 1) else { rest += args(i); rest += args(i + 1) }
      i += 2
    val (workers, _, parsed) = CapBench.parse(rest.toArray)
    backend match
      case "caller" =>
        given NioOnCaller.type = NioOnCaller
        Io.run(io ?=> CapBench.run(io, parsed))
      case "reactors" =>
        given NioBackend.type = NioBackend
        ReactorRuns.run(workers, TakePolicy.Affine)(io ?=> CapBench.run(io, parsed))
      case other => throw new IllegalArgumentException(s"no backend $other: caller, reactors")
  }
}
