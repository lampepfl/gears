import gears.async.*
import gears.async.default.given

val SingleThreadedSupport = summon[Async.FromSync.BlockingWithLocks]
