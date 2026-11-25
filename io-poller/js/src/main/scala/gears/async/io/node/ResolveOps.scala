package gears.async.io
package node

import gears.async.io.node.base.CallOp
import gears.async.io.node.base.SingleUseOp
import gears.async.io.node.host.Addresses
import gears.async.io.node.host.NodeErr
import gears.async.io.node.internal.Dns

import java.io.IOException
import scala.scalajs.js

/** Resolves a host name: an address written out is read here, with its scope, by number or by an interface's name; any
  * other name is looked up by the system's resolver, with `dns.lookup`, on a thread of Node's. Node cannot stop a
  * lookup, so one cancelled or out of time is left to run on, its result dropped.
  */
private[node] abstract class AbstractResolveOp(name: String, into: Slot[List[IpAddress]])
    extends SingleUseOp
    with CallOp {
  override def onEntry(): Unit = Slots.requireEmpty(into)

  private val found: js.Function2[js.Dynamic, js.Dynamic, Unit] = (failure, entries) =>
    if pending then
      complete(
        if NodeErr.failed(failure) then new IOException(s"Failed to resolve $name: ${NodeErr.io(failure).getMessage}")
        else
          try {
            val addresses = entries
              .asInstanceOf[js.Array[js.Dynamic]]
              .toList
              .flatMap(entry => Option(Addresses.parse(entry.address.asInstanceOf[String])).map(Addresses.unmapped))
              .distinct
            if addresses.isEmpty then throw new IOException(s"Failed to resolve $name: it has no IPv4 or IPv6 address")
            into.set(addresses)
            null
          } catch case refused: Exception => refused
      )

  protected final def call(): Unit = {
    val bare = if name.startsWith("[") && name.endsWith("]") then name.substring(1, name.length - 1) else name
    val written = Addresses.parse(bare)
    if written != null then {
      into.set(List(Addresses.unmapped(written)))
      complete(null)
    } else NodeErr.guard(Dns.lookup(name, js.Dynamic.literal(all = true), found))
  }
}
