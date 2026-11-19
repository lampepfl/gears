package gears.async.io
package posix

import scala.scalanative.posix.netdb
import scala.scalanative.posix.netdbOps.*
import scala.scalanative.posix.sys.socket
import scala.scalanative.unsafe.*

/** Host name resolution with POSIX `getaddrinfo`. It blocks, so a reactor runs it off its own thread. */
object PosixResolver {

  /** Every stream address for `host`, IPv4 and IPv6, with IPv6 scopes, each once, or the resolver's error message.
    * Blocks.
    */
  def lookup(host: String): Either[String, List[IpAddress]] = Zone.acquire { implicit z =>
    // A bracketed IPv6 literal, as the JDK takes one (`[fe80::1%en0]`): what is inside, which must be numeric.
    val bracketed = host.length > 2 && host.head == '[' && host.last == ']'
    val name = if bracketed then host.substring(1, host.length - 1) else host
    val hints = alloc[netdb.addrinfo]()
    hints.ai_socktype = socket.SOCK_STREAM // one entry per address rather than one per socket type
    if bracketed then {
      hints.ai_family = socket.AF_INET6
      hints.ai_flags = netdb.AI_NUMERICHOST
    }
    val results = alloc[Ptr[netdb.addrinfo]]()
    val status = netdb.getaddrinfo(toCString(name), null, hints, results)
    if status != 0 then Left(fromCString(netdb.gai_strerror(status)))
    else {
      var found = List.empty[IpAddress]
      var entry = !results
      while entry != null do {
        val family = entry.ai_family
        if family == socket.AF_INET || family == socket.AF_INET6 then {
          // Reported as an accepted peer is: an IPv4-mapped address with no scope as the IPv4 address it is.
          val address = PosixSockets.decodeIp(entry.ai_addr).address
          if !found.contains(address) then found = address :: found
        }
        entry = entry.ai_next
      }
      netdb.freeaddrinfo(!results)
      Right(found.reverse)
    }
  }
}
