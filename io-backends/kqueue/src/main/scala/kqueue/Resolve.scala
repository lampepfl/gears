package gears.async.io
package kqueue

import gears.async.io.ResolvedAddress
import gears.async.io.Slot
import gears.async.io.internal.PosixResolver

import java.io.IOException

/** Resolves a host name without blocking the reactor. There is no non-blocking `getaddrinfo`, so the lookup runs on the
  * blocker pool, the way libuv and tokio do it. Completes after writing the addresses into `into`, or fails with an
  * `IOException` carrying the resolver's error.
  */
final class Resolve(val host: String, into: Slot[List[ResolvedAddress]]) extends KqueueBlocking {
  gears.async.io.internal.Checks.checkHost(host)
  override private[io] def slotRefusal: Throwable | Null = gears.async.io.internal.Checks.notEmpty(into)
  override protected def detachable: Boolean = true // its cancel need not wait for the lookup
  def block(): Unit = PosixResolver.lookup(host) match {
    case Right(addresses) =>
      publish(into, gears.async.io.internal.Checks.resolved(host, addresses)) // not if cancelled meanwhile
    case Left(error) => throw new IOException(s"Failed to resolve $host: $error")
  }
}
