import gears.async.*
import gears.async.{js => gearsJS}

import scalajs.js

trait TestListenerImpl:
  given Async.FromSync.Blocking = gearsJS.UnsafeJsAsyncFromSync
