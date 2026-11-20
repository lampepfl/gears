package gears.async.io.internal

/** What `gears.async.io.ClosedChannelException` is on Scala.js, whose library has not the JDK's class: a class of
  * gears' own, as the JDK's is an `IOException` with no message of its own.
  */
class ClosedChannelException extends java.io.IOException
