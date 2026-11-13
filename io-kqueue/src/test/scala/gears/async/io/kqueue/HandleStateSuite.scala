package gears.async.io
package kqueue

import gears.async.io.shareable.AbstractBaseHandle
import gears.async.io.shareable.BaseHandle
import gears.async.io.shareable.EventSource
import gears.async.io.shareable.ReadinessOp
import gears.async.io.shareable.TimerHeap.{TimerOp, TimerSource}
import gears.async.io.shareable.CloseListener
import gears.async.io.shareable.HandleState

import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicInteger

/** A handle's state and its count of claimed work, which share one word: work is claimed only while the handle is
  * open, and a closing handle is closed once, by whoever releases the last of its work.
  */
class HandleStateSuite extends munit.FunSuite {

  private final class TestHandle extends AbstractBaseHandle {
    val cleanups = AtomicInteger(0)
    open()
    def cleanup()(using ClosePermit): Boolean = {
      cleanups.incrementAndGet()
      assert(compareAndSetState(HandleState.ClosingState, HandleState.ClosedState))
      true
    }
  }

  test("claimed work is counted, and leaves the handle open") {
    val handle = TestHandle()
    assertEquals(handle.claimedWork, 0L)
    handle.claimWork()
    handle.claimWork()
    assertEquals(handle.claimedWork, 2L)
    assertEquals(handle.getState, HandleState.OpenState)
    handle.releaseWork()
    handle.releaseWork()
    assertEquals(handle.claimedWork, 0L)
    assertEquals(handle.getState, HandleState.OpenState)
    intercept[IllegalStateException](handle.releaseWork())
  }

  test("an idle handle closes at once, and then refuses work") {
    val handle = TestHandle()
    assert(handle.requestClose())
    assertEquals(handle.getState, HandleState.ClosedState)
    assertEquals(handle.cleanups.get(), 1)
    intercept[ClosedChannelException](handle.claimWork())
    intercept[ClosedChannelException](handle.requestClose())
    assertEquals(handle.cleanups.get(), 1)
  }

  test("a handle with work claimed closes when the last of it is released") {
    val handle = TestHandle()
    handle.claimWork()
    handle.claimWork()
    assert(!handle.requestClose())
    assertEquals(handle.getState, HandleState.ClosingState)
    assertEquals(handle.claimedWork, 2L)
    intercept[ClosedChannelException](handle.claimWork())
    intercept[ClosedChannelException](handle.requestClose())
    handle.releaseWork()
    assertEquals(handle.getState, HandleState.ClosingState)
    assertEquals(handle.cleanups.get(), 0)
    handle.releaseWork()
    assertEquals(handle.getState, HandleState.ClosedState)
    assertEquals(handle.cleanups.get(), 1)
  }

  test("the owner of a close is told once it is done, whether it asks before or after") {
    val told = AtomicInteger(0)
    val owner = new CloseListener { def handleClosed(): Unit = told.incrementAndGet(): Unit }

    val busy = TestHandle()
    busy.claimWork()
    assert(!busy.requestClose())
    busy.onClosed(owner)
    assertEquals(told.get(), 0)
    intercept[IllegalStateException](busy.onClosed(owner))
    busy.releaseWork()
    assertEquals(told.get(), 1)

    val late = TestHandle()
    late.claimWork()
    assert(!late.requestClose())
    late.releaseWork() // closed before its owner asks
    late.onClosed(owner)
    assertEquals(told.get(), 2)
  }

  private final class FakeSource extends EventSource {
    val started = AtomicInteger(0)
    val stopped = AtomicInteger(0)
    def holdTimer(op: TimerOp): TimerSource[?] = throw new UnsupportedOperationException
    def startListening(handle: BaseHandle, read: Boolean): Unit = started.incrementAndGet(): Unit
    override def stopListening(handle: BaseHandle, read: Boolean): Unit = stopped.incrementAndGet(): Unit
  }

  private final class FakeOp extends ReadinessOp {
    def settled: Boolean = false
    def claimAtReady(): Boolean = true
    def readyWakeup(available: Long, source: EventSource): Boolean = true
    def releaseReady(): Boolean = true
    def handleClosing(): Unit = ()
  }

  test("a source starts listening once, however many ops wait, and again only after it was given up") {
    val handle = TestHandle()
    val source = FakeSource()
    val op = FakeOp()
    for _ <- 1 to 3 do
      val tick = handle.readinessTick(read = true)
      assert(handle.awaitReadiness(read = true, op, tick, source) eq source)
      assert(handle.readyWaiter(read = true, source) eq op)
      assert(handle.clearWaiter(read = true, op))
    assertEquals(source.started.get(), 1)
    // Reported ready with no op waiting, time after time: the handle gives the source up.
    while source.stopped.get() == 0 do assert(handle.readyWaiter(read = true, source) == null)
    assert(!handle.listenedBy(read = true, source))
    assert(handle.awaitReadiness(read = true, op, handle.readinessTick(read = true), source) eq source)
    assertEquals(source.started.get(), 2)
  }

  test("a handle is known drained from a wait or a full take until it is next reported, and not without a listener") {
    val handle = TestHandle()
    val source = FakeSource()
    val op = FakeOp()
    def tick = handle.readinessTick(read = true)
    assert(!handle.drainedAt(read = true, tick))
    handle.markDrained(read = true, source) // not its listener: nothing is known
    assert(!handle.drainedAt(read = true, tick))

    val waitedAt = tick
    assert(handle.awaitReadiness(read = true, op, waitedAt, source) eq source)
    assert(handle.drainedAt(read = true, tick), "an op waits because the handle was not ready")
    assert(!handle.drainedAt(read = false, handle.readinessTick(read = false)), "each direction on its own")

    assert(handle.readyWaiter(read = true, source) eq op)
    assert(!handle.drainedAt(read = true, tick), "a report means there may be something")
    handle.markDrained(read = true, source)
    assert(handle.drainedAt(read = true, tick), "the op took all that was reported")
    assert(handle.clearWaiter(read = true, op))

    // An op that read the tick before a report is told to look again, not to wait.
    val stale = tick
    assert(handle.readyWaiter(read = true, source) == null)
    assert(handle.awaitReadiness(read = true, op, stale, source) == null)

    while source.stopped.get() == 0 do handle.readyWaiter(read = true, source): Unit
    handle.markDrained(read = true, source)
    assert(!handle.drainedAt(read = true, tick), "no listener would report anything new")
  }

  test("threads claiming and releasing against a close leave the handle closed once, with no work claimed") {
    for _ <- 1 to 200 do
      val handle = TestHandle()
      val claimed = AtomicInteger(0)
      val workers = List.fill(4)(new Thread(() => {
        var open = true
        while open do
          try
            handle.claimWork()
            claimed.incrementAndGet()
            handle.releaseWork()
          catch case _: ClosedChannelException => open = false
      }))
      workers.foreach(_.start())
      while claimed.get() < 100 do Thread.onSpinWait()
      handle.requestClose()
      workers.foreach(_.join())
      assertEquals(handle.getState, HandleState.ClosedState)
      assertEquals(handle.claimedWork, 0L)
      assertEquals(handle.cleanups.get(), 1)
  }
}
