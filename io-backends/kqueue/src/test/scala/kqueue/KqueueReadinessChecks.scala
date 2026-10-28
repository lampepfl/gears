package gears.async.io
package kqueue

import gears.async.io.internal.Interest
import gears.async.io.internal.Reactor
import gears.async.io.testkit.ReactorContract

import java.util.concurrent.CountDownLatch

/** Kqueue's own readiness, outside the interface (`KqueueOps.ready`, `readNow`, `writeNow`): a read of data already
  * waiting finished at once, `ready` ops, and handles used without waiting.
  */
trait KqueueReadinessChecks extends ReactorContract {
  val backend: KqueueBackend.type

  test("trySubmit finishes a read of data already waiting without calling its completion") {
    withReactor { r =>
      val (in, out) = pipe(r)
      try {
        backend.ops.writeNow(out, bytes("ready"))
        val buf = allocate(16)
        val outcome = Outcome()
        assert(r.trySubmit(backend.ops.read(in, buf), outcome))
        assertEquals(text(buf), "ready")
        for _ <- 1 to 3 do r.runOnce(block = false)
        assertEquals(outcome.completed, 0)
      } finally {
        close(r, in)
        close(r, out)
      }
    }
  }

  test("readNow into a buffer with no room throws, reading nothing; writeNow with nothing remaining returns 0") {
    withReactor { r =>
      val (in, out) = pipe(r)
      try {
        backend.ops.writeNow(out, bytes("kept"))
        val full = allocate(4)
        full.position(full.limit())
        intercept[IllegalArgumentException](backend.ops.readNow(in, full))
        val buf = allocate(16)
        assertEquals(backend.ops.readNow(in, buf), 4)
        assertEquals(text(buf), "kept", "the refused read took none of the bytes")
        val empty = allocate(4)
        empty.limit(0)
        close(r, in) // a write of bytes would fail now; one of nothing touches nothing
        assertEquals(backend.ops.writeNow(out, empty), 0)
      } finally close(r, out)
    }
  }

  test("ready completes once the handle is ready, again at once while it still is, and waits once it is drained") {
    withReactor { r =>
      val (in, out) = pipe(r)
      try {
        val buf = allocate(64)
        val ready = backend.ops.ready(in, Interest.Read)
        val first = Outcome()
        r.submit(ready, first)
        for _ <- 1 to 5 do r.runOnce(block = false)
        assertEquals(first.completed, 0)
        backend.ops.writeNow(out, bytes("part"))
        turnUntil(r)(first.finished)
        assertEquals(first.completed, 1)
        val stillReady = Outcome() // the data has not been taken yet
        r.submit(ready, stillReady)
        turnUntil(r)(stillReady.finished)
        assertEquals(stillReady.completed, 1)
        assertEquals(backend.ops.readNow(in, buf), 4)
        assertEquals(text(buf), "part")
        val drained = Outcome()
        r.submit(ready, drained)
        for _ <- 1 to 5 do r.runOnce(block = false)
        assertEquals(drained.completed, 0)
        backend.ops.writeNow(out, bytes("!"))
        turnUntil(r)(drained.finished)
        assertEquals(backend.ops.readNow(in, buf), 1)
        assertEquals(text(buf), "part!")
      } finally {
        close(r, in)
        close(r, out)
      }
    }
  }

  test("closing a handle that a reactor blocked on another thread waits to write ends its peer's stream") {
    val (in, out) = pipeAlone()
    val registered = new CountDownLatch(1)
    @volatile var other: Reactor | Null = null
    val blocked = new Thread(() => {
      val b = backend.open()
      other = b
      b.submit(backend.ops.ready(out, Interest.Write), _ => registered.countDown())
      b.run() // blocks between turns until stopped
      b.close()
    })
    blocked.start()
    try {
      registered.await()
      Thread.sleep(20) // so that the other reactor is blocked again
      closeAlone(out)
      withReactor { r =>
        val buf = allocate(16)
        val outcome = Outcome()
        r.submit(backend.ops.read(in, buf), outcome)
        turnUntil(r)(outcome.finished)
        assertEquals((outcome.completed, buf.position()), (1, 0))
      }
    } finally {
      val b = other
      if b != null then {
        b.stop()
        b.wakeup()
      }
      blocked.join()
      closeAlone(in)
    }
  }

  test("readNow reports the end of the stream") {
    val (in, out) = pipeAlone()
    try {
      closeAlone(out)
      assertEquals(backend.ops.readNow(in, allocate(16)), -1)
    } finally closeAlone(in)
  }
}
