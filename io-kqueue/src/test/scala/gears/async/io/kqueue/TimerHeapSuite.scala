package gears.async.io
package kqueue

import gears.async.io.shareable.TimerHeap
import gears.async.io.shareable.TimerHeap.TimerOp

import scala.collection.mutable

class TimerHeapSuite extends munit.FunSuite {
  private class FakeTimer(var deadline: Long) extends TimerOp {
    def deadlineNow: Long = deadline
    def timerWakeup(actAfter: Long): Unit = ()
  }

  private class Heap extends TimerHeap[FakeTimer] {
    override protected def isEmpty(): Boolean = true
  }

  private def drain(heap: Heap, now: Long): List[FakeTimer] =
    Iterator.continually(heap.nextReadyTimer(now)).takeWhile(_ != null).map(_.asInstanceOf[FakeTimer]).toList

  test("an empty heap has no deadline, and nothing ready") {
    val heap = Heap()
    assertEquals(heap.peekNextDeadline(), -1L)
    assert(heap.nextReadyTimer(Long.MaxValue) == null)
  }

  test("only what is due leaves the heap, earliest first, past its first capacity") {
    val heap = Heap()
    val timers = (1 to 100).reverse.map(i => FakeTimer(i.toLong))
    timers.foreach(heap.addTimer)
    assertEquals(heap.peekNextDeadline(), 1L)
    assertEquals(drain(heap, 50).map(_.deadline), (1L to 50L).toList)
    assertEquals(heap.peekNextDeadline(), 51L)
  }

  test("a timer can be removed once") {
    val heap = Heap()
    val timer = FakeTimer(1)
    heap.addTimer(timer)
    assert(heap.removeTimer(timer))
    assert(!heap.removeTimer(timer))
    assert(!heap.removeTimer(FakeTimer(1)))
  }

  test("a timer that fired can't be removed, and can be added again") {
    val heap = Heap()
    val timer = FakeTimer(1)
    heap.addTimer(timer)
    assertEquals(drain(heap, 1), List(timer))
    assert(!heap.removeTimer(timer))
    heap.addTimer(timer)
    assert(heap.removeTimer(timer))
  }

  test("a timer moved later, with no call to the heap, waits for its new deadline") {
    val heap = Heap()
    val moved = FakeTimer(10)
    val other = FakeTimer(20)
    heap.addTimer(moved)
    heap.addTimer(other)
    moved.deadline = 30
    assertEquals(heap.peekNextDeadline(), 10L) // the worker wakes for the old deadline, and finds nothing due
    assert(heap.nextReadyTimer(15) == null)
    assertEquals(heap.peekNextDeadline(), 20L)
    assertEquals(drain(heap, 40), List(other, moved))
  }

  test("a timer moved earlier comes forward") {
    val heap = Heap()
    val other = FakeTimer(10)
    val moved = FakeTimer(20)
    heap.addTimer(other)
    heap.addTimer(moved)
    moved.deadline = 5
    heap.notifyDeadlineChange(moved)
    assertEquals(heap.peekNextDeadline(), 5L)
    assertEquals(drain(heap, 40), List(moved, other))
  }

  test("a change to a timer no longer held is ignored") {
    val heap = Heap()
    val held = FakeTimer(10)
    heap.addTimer(held)
    heap.notifyDeadlineChange(FakeTimer(1))
    assertEquals(heap.peekNextDeadline(), 10L)
  }

  test("random adds, removals and moves match a plain model") {
    for seed <- 1 to 50 do {
      val random = new scala.util.Random(seed)
      val heap = Heap()
      val model = mutable.ArrayBuffer.empty[FakeTimer]
      var now = 0L
      for _ <- 1 to 2000 do
        random.nextInt(6) match {
          case 0 | 1 =>
            val timer = FakeTimer(now + random.nextInt(1000))
            heap.addTimer(timer)
            model += timer
          case 2 if model.nonEmpty =>
            val timer = model.remove(random.nextInt(model.size))
            assert(heap.removeTimer(timer))
          case 3 if model.nonEmpty =>
            val timer = model(random.nextInt(model.size))
            timer.deadline += random.nextInt(1000) // later: a store, and nothing else
          case 4 if model.nonEmpty =>
            val timer = model(random.nextInt(model.size))
            timer.deadline = math.max(now, timer.deadline - random.nextInt(1000))
            heap.notifyDeadlineChange(timer)
          case _ =>
            now += random.nextInt(100)
            val fired = drain(heap, now)
            val due = model.filter(_.deadline <= now)
            assertEquals(fired.toSet, due.toSet, s"seed $seed")
            assertEquals(fired.size, due.size, s"seed $seed")
            model --= due
        }
      assertEquals(drain(heap, Long.MaxValue).toSet, model.toSet, s"seed $seed")
    }
  }

  test("a timer with no deadline is refused, as it would fire at once") {
    val heap = Heap()
    val timer = new FakeTimer(-1) {
      override def hasDeadline: Boolean = false
    }
    intercept[IllegalArgumentException](heap.addTimer(timer))
    assertEquals(heap.peekNextDeadline(), -1L)
  }

  test("a deadline past the end of the clock stays there") {
    assertEquals(TimerHeap.deadlineAfter(Long.MaxValue), Long.MaxValue)
    val soon = TimerHeap.deadlineAfter(1000L)
    assert(soon > 0 && soon < Long.MaxValue)
  }
}
