package gears.async.io.kqueue

sealed trait KqueueHandle

sealed trait KqueueReadable extends KqueueHandle
sealed trait KqueueWritable extends KqueueHandle
