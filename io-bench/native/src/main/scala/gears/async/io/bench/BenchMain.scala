package gears.async.io
package bench

import gears.async.io.kqueue.KqueueBackend

/** The bench over this branch's kqueue backend, on `--workers` workers, with `CapBench`'s own arguments. */
object BenchMain {
  def main(args: Array[String]): Unit = {
    val rest = args.grouped(2).filter(_(0) != "--backend").flatten.toArray
    val (workers, _, parsed) = CapBench.parse(rest)
    KqueueBackend.runOn(workers, shareable.Starvation.printWarning)(io ?=> CapBench.run(io, parsed))
  }
}
