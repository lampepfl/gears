package gears.async.io
package internal

import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong

/** Checks `RunQueue` under contention (see `RunQueueSuite`). One owner thread pushes tasks and takes some itself, while
  * other threads, the takers, move tasks from it into queues of their own and run them. Every task must run exactly
  * once, and whichever thread runs it must see the field the owner wrote before queueing it.
  */
object RunQueueCheck {

  /** How the check went: tasks that ran other than once or saw a stale field, and how many tasks the takers moved. */
  final case class Outcome(failures: Long, moved: Long)

  final class Task(val id: Int, counts: AtomicIntegerArray, failures: AtomicLong) extends Runnable {
    // Written by the owner before it queues the task, read by whichever thread runs it; plain on purpose.
    var payload = 0L

    def run(): Unit = {
      if payload != id * 31L + 7 then failures.incrementAndGet()
      counts.incrementAndGet(id)
    }
  }

  def run(tasks: Int, takers: Int): Outcome = {
    val counts = new AtomicIntegerArray(tasks)
    val failures = new AtomicLong
    val ran = new AtomicLong
    val moved = new AtomicLong
    val owner = new RunQueue
    @volatile var done = false

    val takerThreads = (1 to takers).map { _ =>
      val body: Runnable = () => {
        val mine = new RunQueue
        var took = 0L
        while !done do {
          took += owner.moveHalfTo(mine)
          var task = mine.pop()
          while task != null do {
            task.run()
            ran.incrementAndGet()
            task = mine.pop()
          }
        }
        moved.addAndGet(took)
      }
      new Thread(body)
    }
    takerThreads.foreach(_.start())

    var next = 0
    // The owner queues tasks three at a time and runs one, so the queue stays busy without overflowing for long.
    while next < tasks || !owner.isEmpty do {
      var pushed = 0
      while pushed < 3 && next < tasks do {
        val task = new Task(next, counts, failures)
        task.payload = next * 31L + 7
        if owner.push(task) then {
          next += 1
          pushed += 1
        } else pushed = 3 // full: run one before trying again
      }
      val task = owner.pop()
      if task != null then {
        task.run()
        ran.incrementAndGet()
      }
    }
    while ran.get() < tasks do Thread.onSpinWait() // the takers finish what they took
    done = true
    takerThreads.foreach(_.join())
    var wrong = 0
    var i = 0
    while i < tasks do {
      if counts.get(i) != 1 then wrong += 1
      i += 1
    }
    Outcome(wrong + failures.get(), moved.get())
  }
}
