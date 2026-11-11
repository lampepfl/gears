package gears.async.io
package kqueue

import gears.async.Async
import gears.async.Cancellable
import gears.async.CompletionGroup
import gears.async.Listener
import gears.async.cancellationScope
import gears.async.default.given
import gears.async.io.shareable.AbstractMakeTimerOp
import gears.async.io.shareable.RepeatableAbstractTimerHeapOp
import gears.async.io.shareable.TimerHeap
import gears.async.io.shareable.TimerHeap.TimerOp
import gears.async.io.shareable.TimerHeapOp.CancelledSentinel
import gears.async.io.shareable.TimerHeapOp.Outcome
import gears.async.io.shareable.MonitorPoller
import gears.async.io.shareable.WorkpoolScheduler

import java.util.concurrent.CancellationException
import scala.concurrent.TimeoutException
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** A firing and a cancel race for an op: whichever claims it decides its outcome, and a listener gets that outcome. */
class TimerHeapOpSuite extends munit.FunSuite {
  private class CountingTimer extends AbstractMakeTimerOp(Duration.Zero) {
    var worked = 0
    override def wakeup(resumedAt: Long): Try[Unit] | CancelledSentinel.type = {
      worked += 1
      super.wakeup(resumedAt)
    }
  }

  private def receiver(received: Outcome => Unit): Listener[Outcome] =
    Listener((result, _) => received(result))

  /** A listener whose performer has been cancelled, but which its source still holds. */
  private val cancelled: Listener[Outcome] = new Listener[Outcome] {
    val lock: Listener.ListenerLock | Null = new Listener.ListenerLock {
      val selfNumber = 0L
      def acquire() = false
      def release() = ()
    }
    def complete(data: Outcome, source: gears.async.Async.Source[Outcome]) = fail("completed a cancelled listener")
  }

  private def isCancelled(outcome: Option[Outcome]): Boolean = outcome match {
    case Some(CancelledSentinel) => true
    case _                       => false
  }

  /** An op entered as its performer would, which links it to a group of its own. */
  private def entered[T <: CountingTimer](timer: T)(body: T => Async ?=> Unit): Unit =
    Async.blocking {
      Async.group {
        timer.performWith(body(timer))
      }
    }

  /** Cancels the op as a performer's cancel does: through the group it is linked to. */
  private def cancelGroup()(using async: Async): Unit = async.group.cancel()

  /** Fires as the heap does: the op is claimed first, which a cancel that came before refuses. */
  private def fire(timer: CountingTimer): Boolean = {
    val claimed = timer.claimAtDeadline()
    if claimed then timer.timerWakeup(System.nanoTime())
    claimed
  }

  test("a consumer gets the outcome of the op's work") {
    entered(CountingTimer()) { timer =>
      var outcome: Option[Outcome] = None
      timer.onComplete(receiver(result => outcome = Some(result)))
      assert(fire(timer))
      assertEquals(timer.worked, 1)
      assertEquals(outcome, Some(Success(())))
    }
  }

  test("work that throws is the op's outcome") {
    val boom = new IllegalStateException("boom")
    val throwing = new CountingTimer {
      override def wakeup(resumedAt: Long): Try[Unit] = throw boom
    }
    entered(throwing) { timer =>
      var outcome: Option[Outcome] = None
      timer.onComplete(receiver(result => outcome = Some(result)))
      assert(fire(timer))
      assertEquals(outcome, Some(Failure(boom)))
      assert(timer.settled)
    }
  }

  test("a claimed op isn't settled until it has fired, and can be claimed only once") {
    entered(CountingTimer()) { timer =>
      assert(timer.claimAtDeadline())
      assert(!timer.settled)
      assertEquals(timer.poll(), None)
      assert(!timer.claimAtDeadline())
      timer.timerWakeup(System.nanoTime())
      assert(timer.settled)
      assert(!timer.claimAtDeadline())
    }
  }

  test("firing an op that wasn't claimed is refused") {
    entered(CountingTimer()) { timer =>
      intercept[IllegalStateException](timer.timerWakeup(System.nanoTime()))
      assert(!timer.settled)
    }
  }

  test("a listener that refuses its lock is not completed, and the op still settles") {
    entered(CountingTimer()) { timer =>
      timer.onComplete(cancelled)
      assert(fire(timer))
      assert(timer.settled)
      assertEquals(timer.poll(), Some(Success(())))
    }
  }

  test("an op fired with no listener keeps its outcome for the one that comes") {
    entered(CountingTimer()) { timer =>
      assert(fire(timer))
      assertEquals(timer.worked, 1)
      var outcome: Option[Outcome] = None
      timer.onComplete(receiver(result => outcome = Some(result)))
      assertEquals(outcome, Some(Success(())))
    }
  }

  test("a cancel before the deadline settles the op as cancelled, and it can no longer be claimed") {
    entered(CountingTimer()) { timer =>
      var outcome: Option[Outcome] = None
      timer.onComplete(receiver(result => outcome = Some(result)))
      cancelGroup()
      assert(isCancelled(outcome), outcome.toString)
      assert(timer.settled)
      assert(isCancelled(timer.poll()), timer.poll().toString)
      assert(!fire(timer))
      assertEquals(timer.worked, 0)
    }
  }

  test("a cancel after the op was claimed is seen by its work, which fails as cancelled") {
    entered(CountingTimer()) { timer =>
      var outcome: Option[Outcome] = None
      timer.onComplete(receiver(result => outcome = Some(result)))
      assert(timer.claimAtDeadline())
      cancelGroup()
      assertEquals(outcome, None)
      timer.timerWakeup(System.nanoTime())
      assert(isCancelled(outcome), outcome.toString)
    }
  }

  test("a cancel after the op settled changes nothing") {
    entered(CountingTimer()) { timer =>
      assert(fire(timer))
      cancelGroup()
      assertEquals(timer.poll(), Some(Success(())))
    }
  }

  test("cancelling the group an op entered under cancels the op") {
    var outcome: Option[Outcome] = None
    val timer = CountingTimer()
    Async.blocking {
      Async.group {
        timer.performWith {
          timer.onComplete(receiver(result => outcome = Some(result)))
          summon[Async].group.cancel()
        }
      }
    }
    assert(isCancelled(outcome), outcome.toString)
    assert(!timer.claimAtDeadline())
  }

  test("a repeatable op forgets a cancel when it is entered again") {
    val timer = CountingTimer()
    entered(timer)(_ => cancelGroup())
    assert(timer.settled)
    entered(timer) { timer =>
      assert(!timer.settled)
      assert(fire(timer))
      assertEquals(timer.poll(), Some(Success(())))
    }
  }

  test("an op can't be entered twice at once, nor left when not entered") {
    val timer = CountingTimer()
    entered(timer) { timer =>
      Async.blocking(intercept[IllegalStateException](timer.requestEntry()))
    }
    Async.blocking(intercept[IllegalStateException](timer.requestExit()))
  }

  test(
    "a cancel that doesn't come from the group the op is linked to does nothing: it was meant for an earlier performance"
  ) {
    val earlier = CompletionGroup()
    val timer = CountingTimer()
    Async.blocking {
      val earlierAsync = summon[Async].withGroup(earlier)
      timer.requestEntry()(using earlierAsync)
      timer.link()(using earlierAsync)
      timer.unlink()
      timer.requestExit()
    }
    entered(timer) { timer =>
      var outcome: Option[Outcome] = None
      timer.onComplete(receiver(result => outcome = Some(result)))
      earlier.cancel() // no longer holds the op
      timer.cancel() // as that group would, had it begun its cancel while it still held it
      assertEquals(outcome, None)
      assert(!timer.settled)
      assert(timer.claimAtDeadline())
      timer.cancel()
      timer.timerWakeup(System.nanoTime())
      assertEquals(outcome, Some(Success(())))
    }
  }

  test("a cancel of an op that is not being performed does nothing") {
    val timer = CountingTimer()
    timer.cancel()
    assert(!timer.settled)
  }

  /** An op like a read: only a time limit puts it on the heap. */
  private class LimitedOnly extends RepeatableAbstractTimerHeapOp {
    var worked = 0
    def wakeup(resumedAt: Long): Try[Unit] = {
      worked += 1
      WorkpoolScheduler.SuccessUnit
    }
  }

  test("a firing for a time limit is a timeout, though the performer has taken the op off the heap meanwhile") {
    val heap = new TimerHeap[TimerOp] with MonitorPoller {
      protected def id: Int = 0
      override protected def isEmpty(): Boolean = true
    }
    val op = LimitedOnly()
    Async.blocking {
      op.performWith {
        op.setTimeout(0, heap)
        heap.addTimer(op)
        val due = System.nanoTime() + 1_000_000L
        assert(heap.nextReadyTimer(due) eq op) // a worker claims it
        op.clearTimeout() // the performer, cancelled, is on its way out
        op.timerWakeup(due)
        assert(op.poll().exists {
          case Failure(_: TimeoutException) => true
          case _                            => false
        }, op.poll().toString)
        assertEquals(op.worked, 0)
      }
    }
  }

  test("an op performed again with no time limit forgets the last one") {
    val heap = new TimerHeap[TimerOp] with MonitorPoller {
      protected def id: Int = 0
      override protected def isEmpty(): Boolean = true
    }
    val op = LimitedOnly()
    Async.blocking {
      op.performWith {
        op.setTimeout(0, heap)
        assert(op.hasDeadline)
        op.clearTimeout()
      }
      op.performWith {
        op.setTimeout(Backend.NoTimeout, heap)
        assert(!op.hasDeadline)
      }
    }
  }

  test("parking on a settled op gives its outcome, be it a failure") {
    entered(CountingTimer()) { timer =>
      assert(fire(timer))
      timer.parkUntilSettled()
    }
    val boom = new IllegalStateException("boom")
    val throwing = new CountingTimer {
      override def wakeup(resumedAt: Long): Try[Unit] = throw boom
    }
    entered(throwing) { timer =>
      assert(fire(timer))
      assertEquals(intercept[IllegalStateException](timer.parkUntilSettled()), boom)
    }
  }

  test("parking on an op that was cancelled throws the cancellation") {
    entered(CountingTimer()) { timer =>
      cancelGroup()
      intercept[CancellationException](timer.parkUntilSettled())
      assert(!timer.claimAtDeadline())
    }
  }

  test("work that throws a fatal error still settles the op, with that error, which is thrown on to the worker") {
    val fatal = new LinkageError("fatal")
    val throwing = new CountingTimer {
      override def wakeup(resumedAt: Long): Try[Unit] = throw fatal
    }
    entered(throwing) { timer =>
      var outcome: Option[Outcome] = None
      timer.onComplete(receiver(result => outcome = Some(result)))
      assert(timer.claimAtDeadline())
      // Not `intercept`, which leaves a fatal error alone.
      val thrown =
        try { timer.timerWakeup(System.nanoTime()); None }
        catch case error: LinkageError => Some(error)
      assertEquals(thrown, Some(fatal))
      assert(timer.settled)
      assertEquals(outcome, Some(Failure(fatal)))
    }
  }

  test("an op whose entry fails is not entered, nor held by the group, and can be entered again") {
    var failing = true
    val timer = new CountingTimer {
      override def onEntry(): Unit =
        if failing then throw new IllegalArgumentException("boom")
        else super.onEntry()
    }
    Async.blocking {
      // A group waits for its members as it ends: this would not return if the op had been linked to it.
      Async.group(intercept[IllegalArgumentException](timer.requestEntry()))
      intercept[IllegalStateException](timer.requestExit())
    }
    failing = false
    entered(timer) { timer =>
      assert(fire(timer))
      assertEquals(timer.poll(), Some(Success(())))
    }
  }
}
