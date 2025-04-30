import gears.async.*
import gears.async.native.SuspendExecutorWithSleep

import java.util.concurrent.ForkJoinPool
import scala.concurrent.JavaConversions.asExecutionContext

val SingleThreadedSupport =
  val t = SuspendExecutorWithSleep(new ForkJoinPool(1))
  Async.FromSync.BlockingWithLocks(using t, t)
