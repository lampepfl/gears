package gears.async.io
package kqueue

import gears.async.io.shareable.AbstractBaseHandle
import gears.async.io.shareable.BaseHandle
import gears.async.io.shareable.CloseListener
import gears.async.io.shareable.EventSource
import gears.async.io.shareable.HandleState
import gears.async.io.shareable.ReadinessOp
import gears.async.io.shareable.TimerHeap.TimerOp

import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicInteger

/** A handle's state and its count of claimed work, which share one word: work is claimed only while the handle is open,
  * and a closing handle is closed once, by whoever releases the last of its work.
  */
class HandleStateSuite extends munit.FunSuite {

  private final class TestHandle extends AbstractBaseHandle {
    type Source = EventSource
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
    type Timer = TimerOp
    def addTimer(op: TimerOp): Unit = throw new UnsupportedOperationException
    def removeTimer(op: TimerOp): Boolean = false
    def notifyDeadlineChange(op: TimerOp): Unit = ()
    def startListening(handle: BaseHandle, read: Boolean): Unit = started.incrementAndGet(): Unit
    override def stopListening(handle: BaseHandle, read: Boolean): Unit = stopped.incrementAndGet(): Unit
  }

  /** An op as the handle sees it: `moves` is what its work does when it is woken. */
  private final class FakeOp extends ReadinessOp {
    var moves = 1
    var woken = 0
    var released = 0
    def settled: Boolean = false
    def claimAtReady(): Boolean = true
    def readyWakeup(): Int = { woken += 1; moves }
    def releaseReady(): Boolean = { released += 1; true }
    def handleClosing(): Unit = ()
  }

  test("a source starts listening once, however many ops wait, and again only after it was given up") {
    val handle = TestHandle()
    val source = FakeSource()
    val op = FakeOp()
    for _ <- 1 to 3 do
      val tick = handle.readiness(read = true).tick
      assert(handle.readiness(read = true).registerWaiter(op, tick, source) eq source)
      handle.readiness(read = true).report(source, ReadinessOp.Unknown)
      assert(!handle.readiness(read = true).clearWaiter(op), "an op that did its work is no longer the waiter")
    assertEquals(op.woken, 3)
    assertEquals(source.started.get(), 1)
    // Reported ready with no op waiting, time after time: the handle gives the source up.
    while source.stopped.get() == 0 do handle.readiness(read = true).report(source, ReadinessOp.Unknown)
    assert(!handle.readiness(read = true).listenedBy(source))
    assert(handle.readiness(read = true).registerWaiter(op, handle.readiness(read = true).tick, source) eq source)
    assertEquals(source.started.get(), 2)
  }

  test("a handle is known drained from a wait or a full take until it is next reported, and not without a listener") {
    val handle = TestHandle()
    val source = FakeSource()
    val op = FakeOp()
    def tick = handle.readiness(read = true).tick
    def drained = handle.readiness(read = true).drainedAt(tick)
    def waitNow(): Unit = assert(handle.readiness(read = true).registerWaiter(op, tick, source) eq source)
    assert(!drained)

    waitNow()
    assert(drained, "an op waits because the handle was not ready")
    assert(!handle.readiness(read = false).drainedAt(handle.readiness(read = false).tick), "each direction on its own")

    op.moves = 4
    handle.readiness(read = true).report(source, available = 4)
    assert(drained, "the op took all that was reported")

    // Reported with no op waiting: there may be something, and an op that read the tick before is told to look again.
    val stale = tick
    handle.readiness(read = true).report(source, available = 4)
    assert(!drained)
    assert(handle.readiness(read = true).registerWaiter(op, stale, source) == null)

    waitNow()
    op.moves = 4
    handle.readiness(read = true).report(source, available = 10)
    assert(!drained, "the op left some of what was reported")

    waitNow()
    op.moves = 0
    handle.readiness(read = true).report(source, ReadinessOp.Unknown)
    assert(!drained, "the end of a stream, which no report follows")

    // An op that finds the handle not ready after all waits on, its claim given back.
    waitNow()
    op.moves = ReadinessOp.Blocked
    handle.readiness(read = true).report(source, available = 4)
    assertEquals(op.released, 1)
    assert(handle.readiness(read = true).clearWaiter(op), "it was still the waiter")

    // A report from a source that is not the listener is not counted.
    val before = tick
    handle.readiness(read = true).report(FakeSource(), available = 4)
    assertEquals(tick, before)

    while source.stopped.get() == 0 do handle.readiness(read = true).report(source, ReadinessOp.Unknown)
    assert(!drained, "no listener would report anything new")
  }

  test("a close waits for work a report has under way, and no report does work once the handle has closed") {
    for _ <- 1 to 300 do
      val handle = TestHandle()
      val source = FakeSource()
      val afterCleanup = AtomicInteger(0)
      val working = AtomicInteger(0)
      val op = new ReadinessOp {
        def settled: Boolean = false
        def claimAtReady(): Boolean = true
        // The work of an op: it must never run beside, or after, the handle's cleanup.
        def readyWakeup(): Int = {
          working.incrementAndGet()
          if handle.cleanups.get() > 0 then afterCleanup.incrementAndGet()
          Thread.onSpinWait()
          if handle.cleanups.get() > 0 then afterCleanup.incrementAndGet()
          ReadinessOp.Blocked // so that it stays the waiter, report after report
        }
        def releaseReady(): Boolean = true
        def handleClosing(): Unit = ()
      }
      assert(handle.readiness(read = true).registerWaiter(op, handle.readiness(read = true).tick, source) eq source)
      val reporter = new Thread(() => {
        while handle.getState != HandleState.ClosedState do
          handle.readiness(read = true).report(source, ReadinessOp.Unknown)
        var i = 0
        while i < 100 do { handle.readiness(read = true).report(source, ReadinessOp.Unknown); i += 1 }
      })
      reporter.start()
      while working.get() < 50 do Thread.onSpinWait()
      assert(handle.requestClose())
      reporter.join()
      assertEquals(afterCleanup.get(), 0)
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
