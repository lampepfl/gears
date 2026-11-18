package gears.async.io
package kqueue

import gears.async.io.shareable.AbstractBaseHandle
import gears.async.io.shareable.AbstractReadinessHandle
import gears.async.io.shareable.BaseHandle
import gears.async.io.shareable.CloseListener
import gears.async.io.shareable.EventSource
import gears.async.io.shareable.HandleOwner
import gears.async.io.shareable.HandleState
import gears.async.io.shareable.ReadinessHandle
import gears.async.io.shareable.ReadinessOp
import gears.async.io.shareable.TimerHeap.TimerOp

import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicInteger

/** A handle's state and its count of claimed work, which share one word: work is claimed only while the handle is open,
  * and a closing handle is closed once, by whoever releases the last of its work.
  */
class HandleStateSuite extends munit.FunSuite {

  private final class TestHandle(queued: Boolean = false)
      extends AbstractBaseHandle(new HandleOwner),
        AbstractReadinessHandle {
    type Source = EventSource { type Handle = ReadinessHandle }
    override protected def queuesWaiters(read: Boolean): Boolean = queued
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
    type Handle = ReadinessHandle
    val handleOwner = new HandleOwner
    val started = AtomicInteger(0)
    val stopped = AtomicInteger(0)
    type Timer = TimerOp
    def addTimer(op: TimerOp): Unit = throw new UnsupportedOperationException
    protected def makeSchedule(nanos: Long, body: Runnable) = throw new UnsupportedOperationException
    def removeTimer(op: TimerOp): Boolean = false
    def notifyDeadlineChange(op: TimerOp): Unit = ()
    var onStart: () => Unit = () => ()
    def startListening(handle: ReadinessHandle, read: Boolean): Unit =
      started.incrementAndGet()
      onStart()
    override def stopListening(handle: ReadinessHandle, read: Boolean): Unit = stopped.incrementAndGet(): Unit
  }

  /** An op as the handle sees it: `moves` is what its work does when it is woken. */
  private final class FakeOp extends ReadinessOp {
    var moves = 1
    var woken = 0
    var released = 0
    def settled: Boolean = false
    def claimAtReady(): Boolean = claimable
    def readyWakeup(): Int = { woken += 1; moves }
    def releaseReady(): Boolean = { released += 1; true }
    var failed: Throwable | Null = null
    def failReady(failure: Throwable): Unit = failed = failure
    def handleClosing(): Unit = closed += 1
    var closed = 0
    var claimable = true
  }

  test("a source starts listening once, however many ops wait, and again only after it was given up") {
    val handle = TestHandle()
    val source = FakeSource()
    val op = FakeOp()
    for _ <- 1 to 3 do
      val tick = handle.readiness(read = true).tick
      assert(handle.readiness(read = true).registerWaiter(op, tick, tried = true, source) eq source)
      handle.readiness(read = true).report(source, ReadinessOp.Unknown)
      assert(!handle.readiness(read = true).clearWaiter(op), "an op that did its work is no longer the waiter")
    assertEquals(op.woken, 3)
    assertEquals(source.started.get(), 1)
    // Reported ready with no op waiting, time after time: the handle gives the source up.
    while source.stopped.get() == 0 do handle.readiness(read = true).report(source, ReadinessOp.Unknown)
    assert(!handle.readiness(read = true).listenedBy(source))
    assert(
      handle
        .readiness(read = true)
        .registerWaiter(op, handle.readiness(read = true).tick, tried = true, source) eq source
    )
    assertEquals(source.started.get(), 2)
  }

  test("a handle is known drained from a wait or a full take until it is next reported, and not without a listener") {
    val handle = TestHandle()
    val source = FakeSource()
    val op = FakeOp()
    def tick = handle.readiness(read = true).tick
    def drained = handle.readiness(read = true).drainedAt(tick)
    def waitNow(): Unit = assert(handle.readiness(read = true).registerWaiter(op, tick, tried = true, source) eq source)
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
    assert(handle.readiness(read = true).registerWaiter(op, stale, tried = true, source) == null)

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

  test("ops on a handle that queues its waiters take turns, in the order they came") {
    val handle = TestHandle(queued = true)
    val source = FakeSource()
    val readiness = handle.readiness(read = true)
    val first, second, third = FakeOp()
    assert(!readiness.waitsWithoutTrying(readiness.tick), "with no queue an op tries its work")
    assert(readiness.registerWaiter(first, readiness.tick, tried = true, source) eq source)
    // Behind another, an op waits without trying, whatever it knows of the handle.
    assert(readiness.waitsWithoutTrying(readiness.tick))
    assert(readiness.registerWaiter(second, readiness.tick, tried = false, source) eq source)
    assert(readiness.registerWaiter(third, readiness.tick - 7, tried = false, source) eq source)
    assertEquals(source.started.get(), 1)

    // One report serves them from the head until the handle is not ready any more.
    second.moves = ReadinessOp.Blocked
    readiness.report(source, ReadinessOp.Unknown)
    assertEquals((first.woken, second.woken, third.woken), (1, 1, 0))
    assertEquals(second.released, 1)
    assert(!readiness.clearWaiter(first), "an op that did its work has left the queue")

    // The op that found it not ready is still at the head, and the next report begins with it.
    second.moves = 1
    readiness.report(source, ReadinessOp.Unknown)
    assertEquals((second.woken, third.woken), (2, 1))
    assert(!readiness.waitsWithoutTrying(readiness.tick), "the queue is empty, and the handle may have more")
  }

  test("an op that leaves a queue, or was settled otherwise, is passed over") {
    val handle = TestHandle(queued = true)
    val source = FakeSource()
    val readiness = handle.readiness(read = true)
    val cancelled, timedOut, last = FakeOp()
    for op <- List(cancelled, timedOut, last) do
      assert(readiness.registerWaiter(op, readiness.tick, tried = true, source) eq source)
    assert(readiness.clearWaiter(cancelled))
    assert(!readiness.clearWaiter(cancelled))
    timedOut.claimable = false // settled by its time limit, its performer yet to clear it
    readiness.report(source, ReadinessOp.Unknown)
    assertEquals((cancelled.woken, timedOut.woken, last.woken), (0, 0, 1))
    assert(!readiness.clearWaiter(timedOut))
  }

  test("an op that never tried its work does not join an empty queue unless the handle is known drained") {
    val handle = TestHandle(queued = true)
    val source = FakeSource()
    val readiness = handle.readiness(read = true)
    val op = FakeOp()
    // It saw a queue, which has gone by now: it must look for itself.
    assert(readiness.registerWaiter(op, readiness.tick, tried = false, source) == null)
    // An op that tried, and found the handle not ready, leaves it known drained, even once it has left.
    assert(readiness.registerWaiter(op, readiness.tick, tried = true, source) eq source)
    assert(readiness.clearWaiter(op))
    assert(readiness.waitsWithoutTrying(readiness.tick))
    assert(readiness.registerWaiter(op, readiness.tick, tried = false, source) eq source)
    // Reported since: there may be something.
    assert(readiness.clearWaiter(op))
    val stale = readiness.tick
    readiness.report(source, ReadinessOp.Unknown)
    assert(readiness.registerWaiter(op, stale, tried = true, source) == null)
  }

  test("a queue's listener that can't start fails the ops that came to wait meanwhile, and the queue starts afresh") {
    val handle = TestHandle(queued = true)
    val readiness = handle.readiness(read = true)
    val source = FakeSource()
    val first, second = FakeOp()
    val refused = new java.io.IOException("expected: a source that can't listen")
    source.onStart = () =>
      // Another op comes to wait while the source is starting: behind the first, on the listener there seems to be.
      assert(readiness.registerWaiter(second, readiness.tick, tried = false, source) eq source)
      throw refused
    val thrown = intercept[java.io.IOException](readiness.registerWaiter(first, readiness.tick, tried = true, source))
    assert(thrown eq refused)
    assert(second.failed eq refused, "the op behind it was left waiting on no listener")
    assert(first.failed == null, "the op whose wait it was is told by the throw, not failed as well")
    assert(!readiness.clearWaiter(first) && !readiness.clearWaiter(second), "an op was left in the queue")
    assert(!readiness.listenedBy(source))
    // With nothing left over, the next op to wait starts a listener as the first did.
    source.onStart = () => ()
    assert(readiness.registerWaiter(first, readiness.tick, tried = true, source) eq source)
    assertEquals(source.started.get(), 2)
    readiness.report(source, ReadinessOp.Unknown)
    assertEquals(first.woken, 1)
  }

  test("a close fails every op in a queue, and a report after it serves none") {
    val handle = TestHandle(queued = true)
    val source = FakeSource()
    val readiness = handle.readiness(read = true)
    val ops = List.fill(3)(FakeOp())
    for op <- ops do assert(readiness.registerWaiter(op, readiness.tick, tried = true, source) eq source)
    assert(handle.requestClose())
    assertEquals(ops.map(_.closed), List(1, 1, 1))
    readiness.report(source, ReadinessOp.Unknown)
    assertEquals(ops.map(_.woken), List(0, 0, 0))
  }

  test("a queued op is served with the handle's work claimed, so a close waits for it") {
    val handle = TestHandle(queued = true)
    val source = FakeSource()
    val readiness = handle.readiness(read = true)
    var claimedDuring = -1L
    var closedInside = true
    val op = new ReadinessOp {
      def settled: Boolean = false
      def claimAtReady(): Boolean = true
      def readyWakeup(): Int = {
        claimedDuring = handle.claimedWork
        closedInside = handle.requestClose() // begins the close, which the work under way holds back
        assertEquals(handle.cleanups.get(), 0)
        1
      }
      def releaseReady(): Boolean = true
      def failReady(failure: Throwable): Unit = ()
      def handleClosing(): Unit = ()
    }
    assert(readiness.registerWaiter(op, readiness.tick, tried = true, source) eq source)
    readiness.report(source, ReadinessOp.Unknown)
    assertEquals(claimedDuring, 1L)
    assert(!closedInside)
    assertEquals(handle.cleanups.get(), 1, "the work's release closed the handle")
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
        def failReady(failure: Throwable): Unit = ()
        def handleClosing(): Unit = ()
      }
      assert(
        handle
          .readiness(read = true)
          .registerWaiter(op, handle.readiness(read = true).tick, tried = true, source) eq source
      )
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

  test("an op takes its event source from its handle's type, and can't be given another") {
    // A read of a handle that `MonitorPoller` listens for is an op whose source is `MonitorPoller`: that compiles.
    assertNoDiff(
      compileErrors("""
        import gears.async.io.shareable.*
        abstract class Monitored extends AbstractBaseHandle(new HandleOwner), AbstractReadinessHandle, BaseReadableHandle { type Source = MonitorPoller }
        abstract class Fits(handle: Monitored, buf: java.nio.ByteBuffer) extends AbstractReadOp[Monitored](handle, buf) {
          def here(source: MonitorPoller)(using gears.async.Async): Unit = performWhenReady(source)
        }
      """),
      ""
    )
    // The same op said to be a kqueue worker's is refused: its source is its handle's, and no other.
    val mismatched = compileErrors("""
      import gears.async.io.shareable.*
      abstract class Monitored extends AbstractBaseHandle(new HandleOwner), AbstractReadinessHandle, BaseReadableHandle { type Source = MonitorPoller }
      abstract class Misfit(handle: Monitored, buf: java.nio.ByteBuffer) extends AbstractReadOp[Monitored](handle, buf) {
        type Source = gears.async.io.kqueue.KqueuePoller
      }
    """)
    assert(mismatched.contains("Source"), mismatched)
    // Nor is it performed on a source that does not listen for its handle.
    val elsewhere = compileErrors("""
      import gears.async.io.shareable.*
      abstract class Monitored extends AbstractBaseHandle(new HandleOwner), AbstractReadinessHandle, BaseReadableHandle { type Source = MonitorPoller }
      abstract class Astray(handle: Monitored, buf: java.nio.ByteBuffer) extends AbstractReadOp[Monitored](handle, buf) {
        def there(source: gears.async.io.kqueue.KqueuePoller)(using gears.async.Async): Unit = performWhenReady(source)
      }
    """)
    assert(elsewhere.nonEmpty, "performed on a source that is not its handle's")
  }

  test("a file is no handle a kqueue listens for, nor one a waiting read is built on") {
    assert(
      compileErrors(
        "def listen(poller: gears.async.io.kqueue.KqueuePoller, file: gears.async.io.kqueue.KqueueFile): Unit = poller.startListening(file, true)"
      ).nonEmpty
    )
    assert(compileErrors("def waits(file: gears.async.io.kqueue.KqueueFile) = file.readiness(true)").nonEmpty)
  }
}
