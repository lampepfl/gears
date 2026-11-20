package gears.async.io

/** The failure of an op on a closed handle (see `Handles.close`): an `IOException`. The JDK's
  * `java.nio.channels.ClosedChannelException` where there is one, on the JVM and Scala Native, and a class of gears'
  * own on Scala.js, whose library has not the JDK's. A program that names this one catches the same failure on every
  * platform.
  */
type ClosedChannelException = internal.ClosedChannelException
