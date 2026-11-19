package gears.async.io
package kqueue

import gears.async.Async
import gears.async.Future
import gears.async.Listener
import gears.async.default.given
import gears.async.io.shareable.AbstractMakeTimerOp
import gears.async.io.shareable.MonitorPoller
import gears.async.io.shareable.RepeatableAbstractTimerHeapOp
import gears.async.io.shareable.TimerHeap.TimerOp
import gears.async.io.shareable.TimerHeapImpl
import gears.async.io.shareable.TimerHeapOp.CancelledSentinel
import gears.async.io.shareable.TimerHeapOp.Outcome
import gears.async.io.shareable.WorkpoolScheduler

import java.util.concurrent.CancellationException
import scala.concurrent.TimeoutException
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** A firing and a cancel race for an op: whichever claims it decides its outcome, and a listener gets that outcome. */
class TimerHeapOpSuite extends munit.FunSuite {
  private class CountingTimer extends AbstractMakeTimerOp(Duration.Zero), HeldOnOwnKind[CountingTimer] {
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

  /** An op entered as its performer would. */
  private def entered[T <: CountingTimer](timer: T)(body: T => Async ?=> Unit): Unit =
    Async.blocking(timer.performWith(body(timer)))

  /** Cancels the op as its performer does when it is cancelled as it waits: nothing else cancels an op, which is linked
    * to no group.
    */
  private def cancelAsPerformer(timer: CountingTimer): Unit = timer.cancelByPerformer(): Unit

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
      cancelAsPerformer(timer)
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
      cancelAsPerformer(timer)
      assertEquals(outcome, None)
      timer.timerWakeup(System.nanoTime())
      assert(isCancelled(outcome), outcome.toString)
    }
  }

  test("a cancel after the op settled changes nothing") {
    entered(CountingTimer()) { timer =>
      assert(fire(timer))
      cancelAsPerformer(timer)
      assertEquals(timer.poll(), Some(Success(())))
    }
  }

  test("a performer cancelled as it waits for its op cancels the op, and is cancelled itself") {
    val timer = CountingTimer()
    val failure = Async.blocking {
      val performing = Future(timer.performWith(timer.parkUntilSettled()))
      performing.cancel() // before it waits or as it does: either way its wait ends cancelled
      performing.awaitResult.failed.toOption
    }
    assert(failure.exists(_.isInstanceOf[CancellationException]), failure.toString)
    assert(isCancelled(timer.poll()), timer.poll().toString)
    assert(!timer.claimAtDeadline())
    assertEquals(timer.worked, 0)
  }

  test("a repeatable op forgets a cancel when it is entered again") {
    val timer = CountingTimer()
    entered(timer)(cancelAsPerformer(_))
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

  /** An op like a read: only a time limit puts it on the heap. */
  private class LimitedOnly extends RepeatableAbstractTimerHeapOp, HeldOnOwnKind[LimitedOnly] {
    var worked = 0
    def wakeup(resumedAt: Long): Try[Unit] = {
      worked += 1
      WorkpoolScheduler.SuccessUnit
    }
  }

  test("a firing for a time limit is a timeout, though the performer has taken the op off the heap meanwhile") {
    val heap = new TimerHeapImpl with MonitorPoller {
      val handleOwner = new gears.async.io.shareable.HandleOwner
      type Timer = TimerOp
      protected def makeSchedule(nanos: Long, body: Runnable) = throw new UnsupportedOperationException
      protected def id: Int = 0
      override protected def isEmpty(): Boolean = true
    }
    val op = LimitedOnly()
    Async.blocking {
      op.performWith {
        op.setTimeout(0)
        op.holdTimerOn(heap)
        val due = System.nanoTime() + 1_000_000L
        assert(heap.nextReadyTimer(due) eq op) // a worker claims it
        op.clearTimeout() // the performer, cancelled, is on its way out
        op.timerWakeup(due)
        assert(
          op.poll().exists {
            case Failure(_: TimeoutException) => true
            case _                            => false
          },
          op.poll().toString
        )
        assertEquals(op.worked, 0)
      }
    }
  }

  test("an op performed again with no time limit forgets the last one") {
    val heap = new TimerHeapImpl with MonitorPoller {
      val handleOwner = new gears.async.io.shareable.HandleOwner
      type Timer = TimerOp
      protected def makeSchedule(nanos: Long, body: Runnable) = throw new UnsupportedOperationException
      protected def id: Int = 0
      override protected def isEmpty(): Boolean = true
    }
    val op = LimitedOnly()
    Async.blocking {
      op.performWith {
        op.setTimeout(0)
        assert(op.hasDeadline)
        op.clearTimeout()
      }
      op.performWith {
        op.setTimeout(Backend.NoTimeout)
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
      cancelAsPerformer(timer)
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
