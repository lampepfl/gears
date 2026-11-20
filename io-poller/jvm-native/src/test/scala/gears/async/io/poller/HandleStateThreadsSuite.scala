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

/** A handle's state against threads, its close and the work claimed on it raced, and its ops' typing against a poller
  * of a pool's: where there are threads.
  */
class HandleStateThreadsSuite extends munit.FunSuite, HandleStateFixtures {

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
        def readyWakeup(): Readiness = {
          working.incrementAndGet()
          if handle.cleanups.get() > 0 then afterCleanup.incrementAndGet()
          Thread.onSpinWait()
          if handle.cleanups.get() > 0 then afterCleanup.incrementAndGet()
          Readiness.Blocked // so that it stays the waiter, report after report
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
    // The same op said to be another source's is refused: its source is its handle's, and no other.
    val mismatched = compileErrors("""
      import gears.async.io.shareable.*
      abstract class Monitored extends AbstractBaseHandle(new HandleOwner), AbstractReadinessHandle, BaseReadableHandle { type Source = MonitorPoller }
      abstract class Misfit(handle: Monitored, buf: java.nio.ByteBuffer) extends AbstractReadOp[Monitored](handle, buf) {
        type Source = gears.async.io.shareable.PoolSource
      }
    """)
    assert(mismatched.contains("Source"), mismatched)
    // Nor is it performed on a source that does not listen for its handle.
    val elsewhere = compileErrors("""
      import gears.async.io.shareable.*
      abstract class Monitored extends AbstractBaseHandle(new HandleOwner), AbstractReadinessHandle, BaseReadableHandle { type Source = MonitorPoller }
      abstract class Astray(handle: Monitored, buf: java.nio.ByteBuffer) extends AbstractReadOp[Monitored](handle, buf) {
        def there(source: gears.async.io.shareable.PoolSource)(using gears.async.Async): Unit = performWhenReady(source)
      }
    """)
    assert(elsewhere.nonEmpty, "performed on a source that is not its handle's")
  }
}
