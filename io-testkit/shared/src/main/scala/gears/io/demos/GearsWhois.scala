package gears.io.demos

import gears.async.Async
import gears.async.io.Io
import gears.async.io.IpSocketAddress
import gears.async.io.IpStreams
import gears.async.io.Resolving
import gears.async.io.testkit.BufferArena
import gears.io.testkit.SimpleIO

import java.nio.charset.StandardCharsets

import DemoIO.text

/** Resolve, connect to port 43, send one query line, print the raw reply. Written with `SimpleIO` where a result reads
  * better than an op and a slot: `resolve` returns the addresses, `read` the bytes received.
  */
object GearsWhois {
  def run(host: String, port: Int, query: String)(using io: Io[IpStreams & Resolving], async: Async): Unit = {
    BufferArena.scoped { arena =>
      val buf = arena.allocate(8192)
      val resolvedAll = SimpleIO.resolve(host) // a failed lookup throws
      val resolved = resolvedAll.head
      val address = IpSocketAddress(resolved, port)
      println(s"resolved $host to $address (${resolvedAll.length} addresses)")
      val fd = SimpleIO.connect(address)
      try {
        println(s"connected; sending query `$query`")
        buf.clear()
        buf.put(s"$query\r\n".getBytes(StandardCharsets.UTF_8))
        buf.flip()
        SimpleIO.writeAll(fd, buf)
        var received = 0L
        var eof = false
        while !eof do {
          buf.clear()
          val got = SimpleIO.read(fd, buf)
          if got == 0 then eof = true // nothing added: the server closed the connection
          else {
            received += got
            buf.flip()
            print(text(buf)) // Raw reply text, exactly as received.
          }
        }
        println(s"--- server closed the connection after $received bytes")
      } finally io.perform(io.ops.close(fd))
    }
  }

  /** Resolves a host and prints every address. */
  def resolve(host: String)(using io: Io[Resolving], async: Async): Unit = {
    val resolvedAll = SimpleIO.resolve(host) // a failed lookup throws
    resolvedAll.foreach { a =>
      println(s"${a.protocol} $a")
    }
    println(s"resolved $host to ${resolvedAll.length} addresses")
  }
}
