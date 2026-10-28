package gears.async.io
package internal

/** The readiness a reactor's command waits for before it runs: kqueue's and NIO's, which wait for a descriptor or a
  * channel to be ready and then do its operation. Not part of the interface, whose ops say what is done, not what is
  * waited for.
  */
enum Interest {
  case Read, Write
}
