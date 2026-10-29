package gears.async.io.internal

import gears.async.io.Slot

import java.io.IOException
import java.util.concurrent.{CountDownLatch, TimeUnit}

class ResourcePublicationSuite extends munit.FunSuite {
  private class Resource {
    var closes = 0
    def close(): Unit = closes += 1
  }

  test("successful publication transfers ownership to the consumer") {
    val resource = new Resource
    val into = Slot[Resource]()
    ResourcePublication(resource)(value => { into.set(value); true })(_.close())
    assert(into.clear() eq resource)
    assertEquals(resource.closes, 0)
  }

  test("declined publication discards the resource exactly once") {
    val resource = new Resource
    ResourcePublication(resource)(_ => false)(_.close())
    assertEquals(resource.closes, 1)
  }

  test("throwing publication discards the resource and preserves the failure") {
    val resource = new Resource
    val failure = new IOException("publication failed")
    val observed = intercept[IOException] {
      ResourcePublication(resource)(_ => throw failure)(_.close())
    }
    assert(observed eq failure)
    assertEquals(resource.closes, 1)
  }

  private def await(latch: CountDownLatch): Unit = {
    val reached = latch.await(5, TimeUnit.SECONDS)
    assert(reached, "the blocking task did not reach the expected publication boundary")
  }

  private class PublishingTask(publishFirst: Boolean, into: Slot[Resource]) extends BlockingRun {
    val resource = new Resource
    val reached = new CountDownLatch(1)
    val proceed = new CountDownLatch(1)
    override protected def interruptible: Boolean = false
    protected def settled(failure: Throwable | Null): Unit = ()
    def block(): Unit = {
      if !publishFirst then { reached.countDown(); proceed.await() }
      ResourcePublication(resource)(value => unlessCancelled(into.set(value)))(_.close())
      if publishFirst then { reached.countDown(); proceed.await() }
    }
  }

  test("cancellation after acquisition wins publication and cleanup completes before acknowledgement") {
    val into = Slot[Resource]()
    val task = new PublishingTask(publishFirst = false, into)
    Blockers.run(task)
    try {
      await(task.reached)
      assert(task.cancelUnlessCommitted())
      assert(task.interrupt())
      task.proceed.countDown()
      task.waitForEnd()
      assert(into.isEmpty)
      assertEquals(task.resource.closes, 1)
    } finally {
      task.proceed.countDown()
      task.waitForEnd()
    }
  }

  test("publication wins a later cancellation and leaves ownership with the consumer") {
    val into = Slot[Resource]()
    val task = new PublishingTask(publishFirst = true, into)
    Blockers.run(task)
    try {
      await(task.reached)
      assert(!task.cancelUnlessCommitted())
      task.proceed.countDown()
      task.waitForEnd()
      assert(into.clear() eq task.resource)
      assertEquals(task.resource.closes, 0)
    } finally {
      task.proceed.countDown()
      task.waitForEnd()
    }
  }
}
