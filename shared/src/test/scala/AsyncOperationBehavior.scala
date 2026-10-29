import gears.async.Listener
import gears.async.io.internal.{AsyncOperation, CooperativeBudget, SerialExecutor}

import scala.collection.mutable
import scala.util.{Success, Try}

class AsyncOperationBehavior extends munit.FunSuite {
  private class Owner extends SerialExecutor {
    var isCurrent = true
    val queued = mutable.Queue.empty[Runnable]
    def execute(task: Runnable): Unit = queued.enqueue(task)
    def drain(): Unit = {
      isCurrent = true
      while queued.nonEmpty do queued.dequeue().run()
    }
  }

  private class Operation extends AsyncOperation[Int] {
    var cancellations = 0
    def start(owner: SerialExecutor): Unit = begin(owner)
    def release(): Unit = end()
    def finish(value: Int): Unit = succeed(value)
    def cancelled(): Unit = acknowledgeCancellation()
    protected def requestCancellation(owner: SerialExecutor): Unit = cancellations += 1
  }

  test("a future preserves its producer's cancellation acknowledgement") {
    import gears.async.default.given
    import gears.async.{Async, Future}
    val cancelled = new java.util.concurrent.CancellationException("producer cleanup finished")
    Async.fromSync {
      val result = Future[Int](throw cancelled).awaitResult
      assert(result.failed.get eq cancelled)
    }
  }

  test("completion before registration and after registration each delivers exactly once") {
    val owner = new Owner
    val op = new Operation
    for before <- List(true, false) do {
      op.start(owner)
      var results = List.empty[Try[Int]]
      val listener = Listener[Try[Int]]((value, _) => results = value :: results)
      if before then op.finish(42)
      op.onComplete(listener)
      if !before then op.finish(42)
      op.finish(99)
      assertEquals(results, List(Success(42)))
      op.release()
    }
  }

  test("cancellation requests do not publish completion until the producer acknowledges") {
    val owner = new Owner
    val op = new Operation
    op.start(owner)
    var completed = false
    op.onComplete(Listener((_, _) => completed = true))
    op.cancel()
    assertEquals(op.cancellations, 1)
    assert(!completed)
    assertEquals(op.poll(), None)
    op.cancelled()
    assert(completed)
    assert(op.poll().get.failed.get.isInstanceOf[java.util.concurrent.CancellationException])
    op.release()
  }

  test("a queued cancellation cannot cross reuse on another owner") {
    val first = new Owner
    val second = new Owner
    val op = new Operation
    op.start(first)
    first.isCurrent = false
    op.cancel()
    op.finish(1)
    op.release()
    op.start(second)
    first.drain()
    assertEquals(op.cancellations, 0)
    assertEquals(op.poll(), None)
    op.finish(2)
    op.release()
  }

  test("overlapping consumers are refused without resetting the current result") {
    val owner = new Owner
    val op = new Operation
    op.start(owner)
    intercept[IllegalStateException](op.start(owner))
    op.finish(7)
    assertEquals(op.poll(), Some(Success(7)))
    op.release()
  }

  test("a second listener is refused and removing it cannot erase the first") {
    val op = new Operation
    op.start(new Owner)
    var result = 0
    val first = Listener[Try[Int]]((value, _) => result = value.get)
    val second = Listener[Try[Int]]((_, _) => fail("second listener ran"))
    op.onComplete(first)
    intercept[IllegalStateException](op.onComplete(second))
    op.dropListener(second)
    op.finish(3)
    assertEquals(result, 3)
    op.release()
  }

  test("immediate readiness yields to peers and new tasks cannot replenish the poll budget") {
    val budget = new CooperativeBudget(perDrain = 4, perTask = 1)
    budget.startDrain()
    for _ <- 0 until 4 do {
      budget.startTask()
      assert(budget.canCompleteImmediately(true))
      budget.completedImmediately()
      assert(!budget.canCompleteImmediately(true))
    }
    budget.startTask()
    assert(!budget.canCompleteImmediately(false))
    budget.startDrain()
    assert(budget.canCompleteImmediately(true))
  }

  test("a lone task can batch immediate work, but must eventually poll") {
    val budget = new CooperativeBudget(perDrain = 4, perTask = 1)
    budget.startDrain()
    budget.startTask()
    for _ <- 0 until 4 do {
      assert(budget.canCompleteImmediately(false))
      budget.completedImmediately()
    }
    assert(!budget.canCompleteImmediately(false))
  }
  test("lone-task bursts are bounded independently of the shared drain allowance") {
    val budget = new CooperativeBudget(perDrain = 8, perTask = 1, perIdleDrain = 2)
    budget.startDrain()
    budget.startTask()
    for _ <- 0 until 3 do {
      assert(budget.canCompleteImmediately(false))
      budget.completedImmediately()
    }
    assert(!budget.canCompleteImmediately(false))
    budget.startTask()
    assert(budget.canCompleteImmediately(true))
    budget.completedImmediately()
    assert(!budget.canCompleteImmediately(false))
    budget.startDrain()
    budget.startTask()
    assert(budget.canCompleteImmediately(false))
  }
}
