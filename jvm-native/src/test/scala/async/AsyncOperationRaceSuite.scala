package gears.async

import gears.async.io.internal.{AsyncOperation, SerialExecutor}

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.util.Try

class AsyncOperationRaceSuite extends munit.FunSuite {
  private class Operation extends AsyncOperation[Int] {
    def start(owner: SerialExecutor): Unit = begin(owner)
    def release(): Unit = end()
    def finish(): Unit = succeed(42)
    protected def requestCancellation(owner: SerialExecutor): Unit = acknowledgeCancellation()
  }

  test("registration racing completion never loses or duplicates a delivery") {
    val owner = new SerialExecutor {
      def isCurrent = true
      def execute(task: Runnable): Unit = task.run()
    }
    for _ <- 0 until 1000 do {
      val op = new Operation
      op.start(owner)
      val start = new CountDownLatch(1)
      val calls = new AtomicInteger
      val completed = new CountDownLatch(1)
      val listener = Listener[Try[Int]] { (value, _) =>
        if value.get == 42 then calls.incrementAndGet()
        completed.countDown()
      }
      val producer = new Thread(() => { start.await(); op.finish() })
      producer.start()
      start.countDown()
      op.onComplete(listener)
      assert(completed.await(5, TimeUnit.SECONDS))
      producer.join()
      assertEquals(calls.get(), 1)
      op.release()
    }
  }
}
