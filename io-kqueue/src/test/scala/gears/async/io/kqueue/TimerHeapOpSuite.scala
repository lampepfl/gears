package gears.async.io
package kqueue

import gears.async.Listener
import gears.async.io.shareable.AbstractMakeTimerOp

import java.util.concurrent.CancellationException
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** A firing does an op's work only for a consumer that is guaranteed to receive its outcome. */
class TimerHeapOpSuite extends munit.FunSuite {
  private class CountingTimer extends AbstractMakeTimerOp(Duration.Zero) {
    var worked = 0
    override def wakeup(resumedAt: Long): Try[Unit] = {
      worked += 1
      super.wakeup(resumedAt)
    }
  }

  private def receiver(received: Try[Unit] => Unit): Listener[Try[Unit]] =
    Listener((result, _) => received(result))

  /** A listener whose performer has been cancelled, but which its source still holds. */
  private val cancelled: Listener[Try[Unit]] = new Listener[Try[Unit]] {
    val lock: Listener.ListenerLock | Null = new Listener.ListenerLock {
      val selfNumber = 0L
      def acquire() = false
      def release() = ()
    }
    def complete(data: Try[Unit], source: gears.async.Async.Source[Try[Unit]]) = fail("completed a cancelled listener")
  }

  test("a consumer gets the outcome of the op's work") {
    val timer = CountingTimer()
    timer.requestEntry()
    var outcome: Option[Try[Unit]] = None
    timer.onComplete(receiver(result => outcome = Some(result)))
    timer.timerWakeup(System.nanoTime())
    assertEquals(timer.worked, 1)
    assertEquals(outcome, Some(Success(())))
  }

  test("work that throws is the consumer's outcome, so that its lock is released and it is resumed") {
    val boom = new IllegalStateException("boom")
    val timer = new CountingTimer {
      override def wakeup(resumedAt: Long): Try[Unit] = throw boom
    }
    timer.requestEntry()
    var outcome: Option[Try[Unit]] = None
    timer.onComplete(receiver(result => outcome = Some(result)))
    timer.timerWakeup(System.nanoTime())
    assertEquals(outcome, Some(Failure(boom)))
    assert(timer.settled)
  }

  test("a listener that refuses its lock leaves the work undone, and the op settled") {
    val timer = CountingTimer()
    timer.requestEntry()
    timer.onComplete(cancelled)
    timer.timerWakeup(System.nanoTime())
    assertEquals(timer.worked, 0)
    assert(timer.settled)
    assert(timer.settling.poll().isDefined)
  }

  test("no listener leaves the work undone, and the op settled as cancelled") {
    val timer = CountingTimer()
    timer.requestEntry()
    timer.timerWakeup(System.nanoTime())
    assertEquals(timer.worked, 0)
    var outcome: Option[Try[Unit]] = None
    timer.onComplete(receiver(result => outcome = Some(result)))
    assert(outcome.exists { case Failure(_: CancellationException) => true; case _ => false }, outcome.toString)
  }
}
