package gears.async.default

import gears.async._
import gears.async.native.ForkJoinSupport

object DefaultSupport extends ForkJoinSupport

// Preserve the existing given's name and erased type, with its scheduler type made explicit.
given given_AsyncSupport: (AsyncSupport { type Scheduler = DefaultSupport.Scheduler }) = DefaultSupport
given DefaultSupport.Scheduler = DefaultSupport
given AsyncOperations = DefaultSupport
