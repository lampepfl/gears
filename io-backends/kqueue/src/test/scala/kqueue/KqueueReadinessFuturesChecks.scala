package gears.async.io
package kqueue

import gears.async.Future
import gears.async.io.internal.Interest
import gears.async.io.testkit.FuturesContract

import java.nio.charset.StandardCharsets

/** `FuturesContract`'s checks of kqueue's own readiness (`KqueueOps.ready`), from futures. */
trait KqueueReadinessFuturesChecks extends FuturesContract {
  val backend: KqueueBackend.type

  test("ready completes once the handle is ready, and the handle can then be used without waiting") {
    val outcome = running { io ?=>
      val (in, out) = pipe(io)
      try {
        val buf = arena.allocate(16)
        val ready = io.backend.ops.ready(in, Interest.Read)
        val waiting = Future(io.perform(ready))
        io.perform(io.backend.ops.timer(20))
        io.backend.ops.writeNow(out, arena.of("ready".getBytes(StandardCharsets.UTF_8)))
        waiting.await
        val read = io.backend.ops.readNow(in, buf)
        s"${text(buf)} ($read bytes)"
      } finally {
        io.perform(io.backend.ops.close(in))
        io.perform(io.backend.ops.close(out))
      }
    }
    assertEquals(outcome, "ready (5 bytes)")
  }

}
