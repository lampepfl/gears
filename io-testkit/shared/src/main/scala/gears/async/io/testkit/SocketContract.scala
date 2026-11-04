package gears.async.io
package testkit

/** Convenience aggregate for backends with both IP transports. Each transport can also be checked independently. */
trait SocketContract extends IpStreamContract with IpDatagramContract {
  override val backend: Backend & IpSockets
}
