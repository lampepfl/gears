package gears.async.io
package internal

import gears.async.io.AddressFamily
import gears.async.io.ResolvedAddress

import scala.scalanative.posix.arpa.inet
import scala.scalanative.posix.netdb
import scala.scalanative.posix.netdbOps.*
import scala.scalanative.posix.netinet.in
import scala.scalanative.posix.sys.socket
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Host name resolution with POSIX `getaddrinfo`. It blocks, so a reactor runs it off its own thread. */
object PosixResolver {

  /** Every stream address for `host`, in numeric form, IPv4 and IPv6, or the resolver's error message. Blocks. */
  def lookup(host: String): Either[String, List[ResolvedAddress]] = Zone.acquire { implicit z =>
    val hints = alloc[netdb.addrinfo]()
    hints.ai_socktype = socket.SOCK_STREAM // one entry per address rather than one per socket type
    val results = alloc[Ptr[netdb.addrinfo]]()
    val status = netdb.getaddrinfo(toCString(host), null, hints, results)
    if status != 0 then Left(fromCString(netdb.gai_strerror(status)))
    else {
      val text = alloc[Byte](64)
      var found = List.empty[ResolvedAddress]
      var entry = !results
      while entry != null do {
        val family = entry.ai_family
        val address =
          if family == socket.AF_INET then entry.ai_addr.asInstanceOf[Ptr[in.sockaddr_in]].at3.asInstanceOf[Ptr[Byte]]
          else if family == socket.AF_INET6 then
            entry.ai_addr.asInstanceOf[Ptr[in.sockaddr_in6]].at4.asInstanceOf[Ptr[Byte]]
          else null
        if address != null && inet.inet_ntop(family, address, text, 64.toUInt) != null then
          found = ResolvedAddress(
            if family == socket.AF_INET then AddressFamily.IPv4 else AddressFamily.IPv6,
            fromCString(text)
          ) :: found
        entry = entry.ai_next
      }
      netdb.freeaddrinfo(!results)
      Right(found.reverse)
    }
  }
}
