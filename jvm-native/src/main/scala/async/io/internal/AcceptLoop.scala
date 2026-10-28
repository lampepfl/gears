package gears.async.io
package internal

import scala.reflect.ClassTag

/** The loop of an accept-all op (`StreamSocketOps.acceptAll`), the same on every backend that accepts from a listening
  * socket of its own: accepts until none waits or `max` are, allocating nothing until the first is accepted, since a
  * perform that would block, as most do on a busy listener's edge, has nothing to return.
  */
private[io] object AcceptLoop {

  /** Accepts with `acceptOne`, which returns the next connection, or null if none waits, up to `max` times: the
    * connections accepted, or null if none was. A failure with none accepted is thrown; with some, they are returned,
    * and the next accept fails instead.
    */
  inline def acceptAll[C <: AnyRef](max: Int)(inline acceptOne: C | Null)(using ClassTag[C]): Array[C] | Null = {
    var clients: Array[C] | Null = null
    var count = 0
    var more = true
    try
      while more && count < max do {
        val client = acceptOne
        if client == null then more = false
        else {
          if clients == null then clients = new Array[C](max)
          clients.nn(count) = client.nn
          count += 1
        }
      }
    catch {
      case failure: Throwable => if count == 0 then throw failure
    }
    if count == 0 || count == max then clients
    else java.util.Arrays.copyOf(clients.nn.asInstanceOf[Array[AnyRef]], count).asInstanceOf[Array[C]]
  }
}
