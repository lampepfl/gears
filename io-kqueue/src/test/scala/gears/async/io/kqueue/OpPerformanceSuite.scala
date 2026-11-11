package gears.async.io
package kqueue

import gears.async.Async
import gears.async.AsyncSupport
import gears.async.CompletionGroup
import gears.async.Future
import gears.async.Future.Promise
import gears.async.cancellationScope
import gears.async.io.shareable.AbstractMakeTimerOp
import gears.async.io.shareable.MonitorPoller
import gears.async.io.shareable.WorkpoolScheduler
import gears.async.native.NativeSuspend

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.util.Success
import scala.util.Try

/** A timer whose work, once it is due, keeps its worker for `work` milliseconds, as real work would. */
private[kqueue] final class SlowOp(time: FiniteDuration, work: Long) extends AbstractMakeTimerOp(time) {
  val started = AtomicInteger(0)
  val finished = AtomicInteger(0)
  override def wakeup(resumedAt: Long): Try[Unit] = {
    started.incrementAndGet()
    if work > 0 then Thread.sleep(work)
    finished.incrementAndGet()
    WorkpoolScheduler.SuccessUnit
  }
}

private[kqueue] object SlowBackend extends Backend {
  type Op = SlowOp
  type Handle = AnyRef
  type Readable = AnyRef
  type Writable = AnyRef
  def ops: Kinded[Ops[Op, Handle]] = ???
  def pool(bufferSize: Int, buffers: Int): BufferPool = ???
  private[io] def run[T](program: Io[this.type] ?=> Async.Spawn ?=> T): T = ???
}

private[kqueue] final class SlowScheduler
    extends WorkpoolScheduler[SlowOp, SlowBackend.type](SlowBackend, 2)
    with NativeSuspend {
  override protected def selectWorker() = workerOfCurrentThread()
  def heapOfCurrentWorker(): String = workerOfCurrentThread().debugTimerState()
  override def makeWorker(id: Int) = new WorkpoolScheduler.Worker[SlowOp, SlowBackend.type](id, SlowBackend) with MonitorPoller {
    override def performOp(op: SlowOp)(using Async): Unit = {
      addTimer(op)
      op.parkUntilSettled()
    }
  }
}

/** What a performer may rely on when it is cancelled from another worker than the one doing its op's work. */
class OpPerformanceSuite extends munit.FunSuite {
  override def munitTimeout: Duration = 60.seconds

  private def onPool[T](program: SlowScheduler => Async.Spawn ?=> T): T = {
    val pool = SlowScheduler()
    given poolSupport: (AsyncSupport { type Scheduler = pool.Scheduler }) = pool
    given poolScheduler: pool.Scheduler = pool
    try Async.blocking(Future(program(pool)).await)
    finally pool.shutdown()
  }

  test("a performer cancelled while its op's work is under way waits for it, and is given its outcome") {
    onPool { io =>
      val op = SlowOp(10.millis, 300)
      @volatile var underWayAtReturn = -1
      @volatile var firstOutcome: Try[Unit] | Null = null
      @volatile var secondTook = -1L
      @volatile var finishedAtSecondReturn = -1
      val done = Promise[Unit]()
      val performer = Future {
        firstOutcome = Try(io.perform(op))
        underWayAtReturn = op.started.get() - op.finished.get()
        // Again, where this future's cancel can't reach: the first performance's firing must not be the one to end it.
        val began = System.nanoTime()
        io.perform(op)(using summon[Async].withGroup(CompletionGroup.Unlinked))
        secondTook = (System.nanoTime() - began) / 1_000_000L
        finishedAtSecondReturn = op.finished.get()
        done.complete(Success(()))
      }
      // On another worker than the performer's, which the op's work is keeping busy.
      val midWork = Promise[Unit]()
      summon[Async].scheduler.schedule(100.millis, () => midWork.complete(Success(())))
      midWork.asFuture.await
      performer.cancel()
      done.asFuture.await
      assertEquals(underWayAtReturn, 0, "perform returned while the op's work was under way")
      assertEquals(firstOutcome, Success(()), "the work was done, and its outcome dropped")
      assert(secondTook >= 300, s"the next performance ended after ${secondTook}ms, on the first one's firing")
      assertEquals(finishedAtSecondReturn, 2)
    }
  }

  test("an op performed where its group is already cancelled is not left on the heap") {
    onPool { io =>
      val op = SlowOp(10.seconds, 0)
      val group = CompletionGroup()
      group.cancel()
      val cancelled = summon[Async].withGroup(group)
      for _ <- 1 to 2 do {
        val outcome = Try(io.perform(op)(using cancelled))
        assert(outcome.failed.toOption.exists(_.isInstanceOf[CancellationException]), outcome.toString)
        assertEquals(io.heapOfCurrentWorker(), "[]")
      }
    }
  }

  test("an op cancelled before it is due is off the heap when perform returns, and can be performed again") {
    onPool { io =>
      val op = SlowOp(10.seconds, 0)
      val started = Promise[Unit]()
      @volatile var heapAtReturn: String | Null = null
      val performer = Future {
        started.complete(Success(()))
        try io.perform(op)
        finally heapAtReturn = io.heapOfCurrentWorker()
      }
      started.asFuture.await
      performer.cancel()
      val outcome = performer.awaitResult
      assert(outcome.failed.toOption.exists(_.isInstanceOf[CancellationException]), outcome.toString)
      assertEquals(op.started.get(), 0)
      val again = SlowOp(1.milli, 0)
      io.perform(again)
      io.perform(again)
      assertEquals(again.finished.get(), 2)
    }
  }

  test("a performer whose group is cancelled waits only on its op, which settles as cancelled once the cancel reaches it") {
    onPool { io =>
      val op = SlowOp(10.seconds, 0)
      val group = CompletionGroup()
      val parked = Promise[Unit]()
      @volatile var outcome: Try[Unit] | Null = null
      val performer = Future {
        given Async = summon[Async].withGroup(group)
        op.performWith {
          parked.complete(Success(()))
          outcome = Try(op.parkUntilSettled())
        }
      }
      parked.asFuture.await
      group.cancel()
      performer.await
      assert(outcome.nn.failed.toOption.exists(_.isInstanceOf[CancellationException]), outcome.toString)
      assert(op.settled)
      assert(!op.claimAtDeadline())
      assertEquals(op.started.get(), 0)
    }
  }
}
