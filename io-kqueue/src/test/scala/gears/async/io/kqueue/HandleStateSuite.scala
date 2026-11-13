package gears.async.io
package kqueue

import gears.async.io.shareable.AbstractBaseHandle
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
    assert(handle.requestClose())
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
    handle.releaseWork()
    assertEquals(handle.getState, HandleState.ClosingState)
    assertEquals(handle.cleanups.get(), 0)
    handle.releaseWork()
    assertEquals(handle.getState, HandleState.ClosedState)
    assertEquals(handle.cleanups.get(), 1)
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
