package gears.async.io
package bench

import gears.async.io.internal.ReactorRuns
import gears.async.io.internal.TakePolicy
import gears.async.io.kqueue.KqueueBackend

/** The bench over the kqueue backend of the branch compared against (`async-io-backends`), on `--workers` workers.
  * `Compare` puts this in place of this branch's `BenchMain` in a worktree of that branch.
  */
object BenchMain {
  def main(args: Array[String]): Unit = {
    val rest = args.grouped(2).filter(_(0) != "--backend").flatten.toArray
    val (workers, _, parsed) = CapBench.parse(rest)
    given KqueueBackend.type = KqueueBackend
    ReactorRuns.run(workers, TakePolicy.Affine)(io ?=> CapBench.run(io, parsed))
  }
}
