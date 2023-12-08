package gears.async.default

import gears.async._
import gears.async.native.ForkJoinSupport

object DefaultSupport extends ForkJoinSupport

given AsyncSupport = DefaultSupport
given DefaultSupport.Scheduler = DefaultSupport
given AsyncOperations = DefaultSupport
