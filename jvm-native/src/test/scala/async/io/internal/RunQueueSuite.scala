package gears.async.io
package internal

import munit.FunSuite

class RunQueueSuite extends FunSuite {
  test("sharing a backlog into a full queue loses no continuation") {
    val queue = new RunQueue
    val backlog = new java.util.ArrayDeque[Runnable]()
    val tasks = Array.tabulate[Runnable](RunQueue.Capacity + 3)(i => () => ())
    tasks.take(RunQueue.Capacity - 1).foreach(task => assert(queue.push(task)))
    tasks.drop(RunQueue.Capacity - 1).foreach(backlog.add)
    assertEquals(queue.moveFrom(backlog, 3), 1)
    assertEquals(backlog.size(), 3)
    assertEquals(queue.moveFrom(backlog, 3), 0)
    for task <- tasks.take(RunQueue.Capacity) do assert(queue.pop() eq task)
    assertEquals(queue.moveFrom(backlog, 3), 3)
    for task <- tasks.drop(RunQueue.Capacity) do assert(queue.pop() eq task)
    assert(backlog.isEmpty)
    assert(queue.isEmpty)
  }

  test("the run queue runs every task once while other threads take tasks from it") {
    val outcome = RunQueueCheck.run(tasks = 2_000_000, takers = 3)
    assertEquals(outcome.failures, 0L)
    assert(outcome.moved > 0, "nothing was moved, so the check proves little")
  }
  test("deadline notifications coalesce and an old poller cannot clear the new owner's notification") {
    val timer = new TimerState { protected def nanos: Long = -1L }
    val first = new TimerWaiter { def moved(timer: TimerState): Unit = () }
    val second = new TimerWaiter { def moved(timer: TimerState): Unit = () }
    assert(timer.queueMove(first))
    for _ <- 0 until 1000 do assert(!timer.queueMove(first))
    assert(timer.queueMove(second))
    timer.takeMove(first)
    assert(!timer.queueMove(second))
    timer.takeMove(second)
    assert(timer.queueMove(second))
    timer.takeMove(second)
    assert(timer.queueMove(first))
  }
}
