package gears.async.io.internal

import gears.async.{Cancellable, CompletionGroup}

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}

class AsyncOperationReuseSuite extends munit.FunSuite {
  private class Owner extends SerialExecutor {
    private val executor = Executors.newSingleThreadExecutor()
    private val thread = on(Thread.currentThread())
    def isCurrent: Boolean = Thread.currentThread() eq thread
    def execute(task: Runnable): Unit = executor.execute(task)
    def on[A](body: => A): A = executor
      .submit(new Callable[A] {
        def call(): A = body
      })
      .get(5, TimeUnit.SECONDS)
    def close(): Unit = executor.shutdownNow(): Unit
  }

  private class Operation extends AsyncOperation[Int] {
    var cancellations = 0
    def start(owner: SerialExecutor): Unit = begin(owner)
    def finish(): Unit = succeed(42)
    def release(): Unit = end()
    protected def requestCancellation(owner: SerialExecutor): Unit = {
      assert(owner.isCurrent)
      cancellations += 1
      acknowledgeCancellation()
    }
  }

  for sameOwner <- List(true, false) do
    test(s"an old group's snapshot cannot cancel reuse (same worker: $sameOwner)") {
      val first = new Owner
      val second = if sameOwner then first else new Owner
      val oldGroup = new CompletionGroup
      val newGroup = new CompletionGroup
      val entered = new CountDownLatch(1)
      val proceed = new CountDownLatch(1)
      val cancellationFailure = new AtomicReference[Throwable]()
      val blocker = new Cancellable {
        def cancel(): Unit = {
          entered.countDown()
          // MUnit's by-name assert holds its clue monitor; never wait for another test thread inside it.
          val released = proceed.await(5, TimeUnit.SECONDS)
          assert(released)
        }
      }
      blocker.link(oldGroup) // The single member is called before the operation in the spill set.
      val op = new Operation
      val cancelling = new Thread(() => {
        try oldGroup.cancel()
        catch case error: Throwable => cancellationFailure.set(error)
      })
      try {
        first.on { op.start(first); op.link(oldGroup) }
        cancelling.start()
        val snapshotBlocked = entered.await(5, TimeUnit.SECONDS)
        assert(snapshotBlocked) // The group's snapshot is held up before reaching the operation.
        first.on { op.finish(); op.unlink(); op.release() }
        second.on { op.start(second); op.link(newGroup) }
        proceed.countDown()
        cancelling.join(5000)
        assert(!cancelling.isAlive)
        assertEquals(cancellationFailure.get(), null)
        first.on(()) // Drain the captured request on its original owner as well.
        // This barrier also drains any cancellation that was dispatched to the second owner.
        second.on {
          assertEquals(op.cancellations, 0)
          assert(op.poll().isEmpty)
        }
        newGroup.cancel()
        second.on {
          assertEquals(op.cancellations, 1)
          assert(op.poll().get.failed.get.isInstanceOf[java.util.concurrent.CancellationException])
        }
      } finally {
        proceed.countDown()
        cancelling.join(5000)
        try second.on { op.finish(); op.unlink(); op.release() }
        finally {
          first.close()
          if !sameOwner then second.close()
        }
      }
    }

  test("joining an already cancelled group still cancels the current generation") {
    val owner = new Owner
    val group = new CompletionGroup
    group.cancel()
    val op = new Operation
    try
      owner.on {
        op.start(owner)
        op.link(group)
        assertEquals(op.cancellations, 1)
        assert(op.poll().get.failed.get.isInstanceOf[java.util.concurrent.CancellationException])
        op.unlink()
        op.release()
      }
    finally owner.close()
  }
}
