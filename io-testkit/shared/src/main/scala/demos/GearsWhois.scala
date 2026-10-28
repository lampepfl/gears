package gears.io.demos

import gears.async.Async
import gears.async.io.AddressFamily
import gears.async.io.Io
import gears.async.io.IpAddress
import gears.async.io.IpStreams
import gears.async.io.Resolving
import gears.async.io.testkit.BufferArena
import gears.io.higherlevel.HigherLevel

import java.nio.charset.StandardCharsets

import DemoIO.text

/** Mirror of `example.KQueueExampleWhois`: resolve, connect to port 43, send one query line, print the raw reply.
  * Written with the higher level where a result reads better than an op and a slot: `resolve` returns the addresses,
  * `read` the bytes received.
  */
object GearsWhois {
  def run(host: String, port: Int, query: String)(using io: Io[IpStreams & Resolving], async: Async): Unit = {
    BufferArena.scoped { arena =>
      val buf = arena.allocate(8192)
      val resolvedAll = HigherLevel.resolve(host) // a failed lookup throws
      val resolved = resolvedAll.head
      val address = resolved.family match {
        case AddressFamily.IPv6 => IpAddress.IPv6(resolved.host, port)
        case AddressFamily.IPv4 => IpAddress.IPv4(resolved.host, port)
      }
      println(s"resolved $host to $address (${resolvedAll.length} addresses)")
      val fd = HigherLevel.connect(address)
      try {
        println(s"connected; sending query `$query`")
        buf.clear()
        buf.put(s"$query\r\n".getBytes(StandardCharsets.UTF_8))
        buf.flip()
        HigherLevel.writeAll(fd, buf)
        var received = 0L
        var eof = false
        while !eof do {
          buf.clear()
          val got = HigherLevel.read(fd, buf)
          if got == 0 then eof = true // nothing added: the server closed the connection
          else {
            received += got
            buf.flip()
            print(text(buf)) // Raw reply text, exactly as received.
          }
        }
        println(s"--- server closed the connection after $received bytes")
      } finally io.perform(io.backend.ops.close(fd))
    }
  }

  /** Resolves a host through the reactor's blocker pool and prints every address. */
  def resolve(host: String)(using io: Io[Resolving], async: Async): Unit = {
    val resolvedAll = HigherLevel.resolve(host) // a failed lookup throws
    resolvedAll.foreach { a =>
      val family = if a.family == AddressFamily.IPv4 then "IPv4" else "IPv6"
      println(s"$family ${a.host}")
    }
    println(s"resolved $host to ${resolvedAll.length} addresses")
  }
}
