package gears.async.io
package poller

import gears.async.io.shareable.AbstractBaseHandle
import gears.async.io.shareable.AbstractReadinessHandle
import gears.async.io.shareable.BaseHandle
import gears.async.io.shareable.CloseListener
import gears.async.io.shareable.EventSource
import gears.async.io.shareable.HandleOwner
import gears.async.io.shareable.HandleState
import gears.async.io.shareable.Moved
import gears.async.io.shareable.Readiness
import gears.async.io.shareable.ReadinessHandle
import gears.async.io.shareable.ReadinessOp
import gears.async.io.shareable.TimerHeap.TimerOp

import java.util.concurrent.atomic.AtomicInteger

/** What the suites of a handle's state are run over: a handle whose cleanup is counted, an event source that counts what
  * it is asked, and an op that records what is done with it.
  */
trait HandleStateFixtures { self: munit.FunSuite =>

  protected final class TestHandle(queued: Boolean = false)
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

  protected final class FakeSource extends EventSource {
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

  protected final class FakeOp extends ReadinessOp {
    var moves: Moved = Moved(1)
    var woken = 0
    var released = 0
    def settled: Boolean = false
    def claimAtReady(): Boolean = claimable
    def readyWakeup(): Readiness = { woken += 1; Readiness.of(moves) }
    def releaseReady(): Boolean = { released += 1; true }
    var failed: Throwable | Null = null
    def failReady(failure: Throwable): Unit = failed = failure
    def handleClosing(): Unit = closed += 1
    var closed = 0
    var claimable = true
  }
}
