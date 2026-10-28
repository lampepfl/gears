package gears.async.io
package kqueue

import gears.async.io.internal.Completion
import gears.async.io.internal.NativeBuffer

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import scala.scalanative.posix.fcntl
import scala.scalanative.unsafe.Zone

/** Closing a kqueue handle while another thread uses its descriptor: the close waits for the use in progress, and no
  * use starts after it, so that nothing a thread still writing through the stale handle sends reaches the descriptors
  * opened since, which the kernel gives the lowest numbers free, the closed ones among them.
  */
class HandleCloseSuite extends munit.FunSuite {

  test("a handle closed while another thread writes through it: nothing reaches what reuses its descriptor") {
    Zone.acquire { implicit z =>
      val rounds = 200
      var leaked = 0L
      for _ <- 1 to rounds do {
        val (in, out) = KqueueHandles.pipe()
        val started = new CountDownLatch(1)
        val stopped = new AtomicBoolean(false)
        val writes = new AtomicLong(0)
        val writer = new Thread(() => {
          val one = NativeBuffer.allocate(1)
          started.countDown()
          try
            while !stopped.get() do {
              one.clear()
              KqueueHandles.writeNow(out, one) // the stale handle's write fails once it is closed
              writes.incrementAndGet(): Unit
            }
          catch case _: IOException => () // not an open handle any more
        })
        writer.start()
        started.await()
        while writes.get() < 10 do Thread.onSpinWait() // writing for a while, the pipe filling
        KqueueHandles.close(in)
        KqueueHandles.close(out)
        // What reuses the numbers: opened while the writer may still be trying.
        val fresh = Seq.fill(4)(KqueueHandles.pipe())
        Thread.sleep(1)
        stopped.set(true)
        writer.join()
        val buf = NativeBuffer.allocate(64)
        for (freshIn, freshOut) <- fresh do {
          buf.clear()
          val got = KqueueHandles.readNow(freshIn, buf) // 0 if nothing arrived, as it should be
          if got > 0 then leaked += got
          KqueueHandles.close(freshIn)
          KqueueHandles.close(freshOut)
        }
      }
      assertEquals(leaked, 0L, s"bytes written through closed handles reached reused descriptors in $rounds rounds")
    }
  }

  test("a close op while a use is in progress: closed at once in meaning, the descriptor once the use ends") {
    Zone.acquire { implicit z =>
      KqueueReactor.scoped() { r =>
        val (in, out) = KqueueHandles.pipe()
        val fd = KqueueHandles.descriptor(out)
        val inUse = new CountDownLatch(1)
        val end = new CountDownLatch(1)
        val user = new Thread(() => KqueueHandles.inUse(out) { inUse.countDown(); end.await() })
        user.start()
        inUse.await()
        @volatile var closed = false
        r.submit(
          KqueueOps.close(out),
          new Completion[KqueueOp] {
            def onComplete(op: KqueueOp): Unit = closed = true
            override def onFailure(op: KqueueOp, failure: Throwable): Unit = fail(s"the close failed: $failure")
          }
        )
        for _ <- 1 to 5 do r.runOnce(block = false) // the reactor turns while the close waits for the use
        assert(!closed, "the close op completed while a use of the descriptor was in progress")
        intercept[IOException](KqueueHandles.writeNow(out, NativeBuffer.allocate(1))) // closed in meaning already
        assert(fcntl.fcntl(fd, fcntl.F_GETFD, 0) >= 0, "the descriptor was closed under a use")
        end.countDown()
        user.join() // the use ends, and with it the descriptor
        assert(fcntl.fcntl(fd, fcntl.F_GETFD, 0) < 0, "the last use did not close the descriptor")
        val deadline = System.nanoTime() + 2_000_000_000L
        while !closed && System.nanoTime() < deadline do r.runOnce(block = false)
        assert(closed, "the close op never completed")
        KqueueHandles.close(in)
      }
    }
  }

  test("a close op that the last use closes completes after the failure of the read pending on the handle") {
    Zone.acquire { implicit z =>
      KqueueReactor.scoped() { r =>
        val (in, out) = KqueueHandles.pipe()
        val order = scala.collection.mutable.ArrayBuffer.empty[String]
        r.submit(
          KqueueOps.read(in, NativeBuffer.allocate(16)),
          new Completion[KqueueOp] {
            def onComplete(op: KqueueOp): Unit = order += "read completed"
            override def onFailure(op: KqueueOp, failure: Throwable): Unit = order += "read failed"
          }
        )
        r.runOnce(block = false) // the read waits
        // A use of the descriptor on the loop thread, in a timer's completion, during which the close is submitted: the
        // use's end closes the descriptor, and resolves the close, in the middle of the turn.
        r.submit(
          new KqueueTimer(0),
          _ =>
            KqueueHandles.inUse(in) {
              r.submit(
                KqueueOps.close(in),
                new Completion[KqueueOp] {
                  def onComplete(op: KqueueOp): Unit = order += "closed"
                  override def onFailure(op: KqueueOp, failure: Throwable): Unit = order += s"close failed: $failure"
                }
              )
            }
        )
        val deadline = System.nanoTime() + 2_000_000_000L
        while order.size < 2 && System.nanoTime() < deadline do r.runOnce(block = false)
        assertEquals(order.toList, List("read failed", "closed"))
        KqueueHandles.close(out)
      }
    }
  }
}
