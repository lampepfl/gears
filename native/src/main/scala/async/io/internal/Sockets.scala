package gears.async.io
package internal

import java.io.IOException
import java.nio.charset.StandardCharsets

object Sockets {
  type Flavor = Byte
  object Flavor {
    inline val Unix = 1
    inline val IPv4 = 2
    inline val IPv6 = 3
  }
  type Transport = Byte
  object Transport {
    inline val Stream = 100
    inline val Datagram = 99
  }

  def checkUnixPathLength(sock: String): Unit = {
    if (sock.isEmpty || sock.contains('\u0000')) {
      throw new IOException("Socket path must be non-empty and must not contain a NUL character")
    }
    if (sock.getBytes(StandardCharsets.UTF_8).length > maxPathLength - 1) {
      throw new IOException(
        s"Socket path exceeds maximum length of ${maxPathLength - 1} UTF-8 bytes: $sock"
      )
    }
  }

  def maxPathLength: Int = PosixSockets.maxPathLength
}
