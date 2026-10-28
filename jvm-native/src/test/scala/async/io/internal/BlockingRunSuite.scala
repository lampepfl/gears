package gears.async.io
package internal

import java.util.concurrent.CountDownLatch

import munit.FunSuite

/** A blocking task's cancel waits for its `block` to return, unless the task is detachable. */
class BlockingRunSuite extends FunSuite {

  /** A task whose `block` waits for `release`, ignoring interrupts, as a native call does, then publishes into `into`.
    */
  private final class Stuck(override protected val detachable: Boolean, into: Slot[String]) extends BlockingRun {
    val started = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    @volatile var settledWith: Option[Throwable | Null] = None
    def block(): Unit = {
      started.countDown()
      var done = false
      while !done do
        try {
          release.await()
          done = true
        } catch case _: InterruptedException => ()
      publish(into, "result")
    }
    protected def settled(failure: Throwable | Null): Unit = settledWith = Some(failure)
  }

  private def running(detachable: Boolean, into: Slot[String]): Stuck = {
    val task = new Stuck(detachable, into)
    Blockers.run(task)
    task.started.await()
    task
  }

  test("a cancel of a running task is held until block returns") {
    val into = Slot[String]()
    val task = running(detachable = false, into)
    assert(task.cancelUnlessCommitted())
    assert(task.interrupt(), "still running, so the cancel waits for it")
    task.release.countDown()
    task.waitForEnd()
    intercept[IllegalStateException](into.clear()) // nothing published after the cancel
  }

  test("a detachable task's cancel is delivered at once, and what it publishes later is dropped") {
    val into = Slot[String]()
    val task = running(detachable = true, into)
    assert(task.cancelUnlessCommitted())
    assert(!task.interrupt(), "detachable, so the cancel need not wait")
    task.release.countDown()
    task.waitForEnd()
    intercept[IllegalStateException](into.clear()) // its result was not written
  }
}
