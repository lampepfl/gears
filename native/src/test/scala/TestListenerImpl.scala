import gears.async.*
import gears.async.default.given

trait TestListenerImpl:
  given Async.FromSync.Blocking = Async.FromSync.BlockingWithLocks()
