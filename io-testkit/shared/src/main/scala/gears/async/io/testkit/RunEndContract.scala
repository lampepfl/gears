package gears.async.io
package testkit

import gears.async.Future

import java.util.concurrent.CancellationException
import scala.concurrent.duration.*

/** What a run leaves when its program returns: the ops its futures still had under way are cancelled, at once, the
  * handles it opened and did not close are closed, and no other run may use them; and a time limit met in time leaves
  * nothing to wait for. What it leaves of blocking ops, and what passes between runs under way at once, is
  * `ThreadedRunContract`'s, for backends with threads.
  */
trait RunEndContract extends IpStreamFixture {
  private def seconds(since: Long): Double = (System.nanoTime() - since) / 1e9

  private val anyLoopbackPort = IpSocketAddress(IpAddress.IPv4Loopback, 0)

  test("a run that returns with a read and a long timer pending cancels them at once") {
    val started = System.nanoTime()
    val pending = running { io ?=>
      val (in, _) = stream(io)
      val reading = Future(io.perform(io.ops.read(in, allocate(16))))
      val sleeping = Future(io.perform(io.ops.timer(60_000)))
      settle(io)
      List(reading, sleeping) // neither awaited, and the stream never closed
    }
    assert(seconds(started) < 5, s"the run took ${seconds(started)} s to end")
    for future <- pending do {
      val outcome = future.poll()
      assert(outcome.exists(_.failed.toOption.exists(_.isInstanceOf[CancellationException])), String.valueOf(outcome))
    }
  }

  test("a run closes what it left open, which no other run may use") {
    var left: AnyRef | Null = null
    var port = 0
    running { io ?=>
      val listener = opening[io.ops.Listener](io)(slot => io.ops.listen(anyLoopbackPort, slot))
      val address = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress]
      port = address.port
      val (client, server) = pair(io, listener)(opening[io.ops.Connection](io)(slot => io.ops.connect(address, slot)))
      left = (listener, client, server) // never closed, and carried out of the run
    }
    running { io ?=>
      // Closed: the listener's port is free to listen on.
      val again =
        opening[io.ops.Listener](io)(slot => io.ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, port), slot))
      close(io, again)
      // A handle is its run's: brought into another, here by a cast, as the suite knows only the interface, it is
      // refused.
      val (listener, client, server) = left.asInstanceOf[(io.ops.Listener, io.ops.Connection, io.ops.Connection)]
      val refusals = List[(String, io.Op)](
        "read" -> io.ops.read(client, allocate(16)),
        "write" -> io.ops.write(server, bytes("y")),
        "accept" -> io.ops.accept(listener, Slot[io.ops.Connection]()),
        "close" -> io.ops.close(client),
        "close of a listener" -> io.ops.close(listener)
      )
      for (what, op) <- refusals do {
        val refused = attempt(io, op)
        assert(refused.isInstanceOf[IllegalArgumentException], s"$what: $refused")
      }
    }
  }

  test("ops performed with long time limits, met in time, leave nothing for the run's end to wait for") {
    val started = System.nanoTime()
    running { io ?=>
      for _ <- 1 to 3 do io.perform(io.ops.timer(1), 60.seconds)
      val (in, out) = stream(io)
      try {
        writeAll(io, out, bytes("z"))
        val buf = allocate(16)
        io.perform(io.ops.read(in, buf), 60.seconds)
        assertEquals(text(buf), "z")
      } finally {
        close(io, in)
        close(io, out)
      }
    }
    assert(seconds(started) < 5, s"the run took ${seconds(started)} s to end")
  }
}
