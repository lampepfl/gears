package gears.async.io
package kqueue

import gears.async.Async
import gears.async.AsyncSupport
import gears.async.Future
import gears.async.Future.Promise
import gears.async.io.shareable.RepeatableAbstractTimerHeapOp
import gears.async.io.shareable.WorkpoolScheduler
import gears.async.native.NativeSuspend

import java.util.concurrent.CancellationException
import scala.concurrent.TimeoutException
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** An op like a read: it has no deadline of its own, and only a time limit puts it on the heap. */
private[kqueue] final class WaitingOp extends RepeatableAbstractTimerHeapOp {
  def wakeup(resumedAt: Long): Try[Unit] = WorkpoolScheduler.SuccessUnit
}

private[kqueue] object WaitingBackend extends Backend {
  type Op = WaitingOp
  type Handle = AnyRef
  type Readable = AnyRef
  type Writable = AnyRef
  def ops: Kinded[Ops[Op, Handle]] = ???
  def pool(bufferSize: Int, buffers: Int): BufferPool = ???
  private[io] def run[T](program: Io[this.type] ?=> Async.Spawn ?=> T): T = ???
}

/** One worker, so that a task runs until it suspends, and in the order it was submitted. */
private[kqueue] final class WaitingScheduler
    extends WorkpoolScheduler[WaitingOp, WaitingBackend.type](WaitingBackend, 1)
    with NativeSuspend {
  override protected def selectWorker() = workerOfCurrentThread()
  override def makeWorker(id: Int) = new WorkpoolScheduler.Worker[WaitingOp, WaitingBackend.type](id, WaitingBackend) {
    override def tryPerform(op: WaitingOp): Boolean = {
      if op.hasDeadline then addTimer(op)
      false
    }
  }
}

class NoDeadlineOpSuite extends munit.FunSuite {
  override def munitTimeout: Duration = 60.seconds

  private def onPool[T](program: Io[WaitingBackend.type] ?=> Async.Spawn ?=> T): T = {
    val pool = WaitingScheduler()
    given poolSupport: (AsyncSupport { type Scheduler = pool.Scheduler }) = pool
    given poolScheduler: pool.Scheduler = pool
    try Async.blocking(Future(program(using pool)).await)
    finally pool.shutdown()
  }

  private def isCancelled(outcome: Try[Unit]): Boolean = outcome match {
    case Failure(_: CancellationException) => true
    case _                                 => false
  }

  test("cancelling the performer of an op with no deadline doesn't wait for it to settle") {
    onPool { io ?=>
      val op = WaitingOp()
      val started = Promise[Unit]()
      val performer = Future {
        started.complete(Success(()))
        io.perform(op)
      }
      started.asFuture.await
      performer.cancel()
      val outcome = performer.awaitResult
      assert(isCancelled(outcome), outcome.toString)
      assert(!op.settled)
    }
  }

  test("a group ending with an op with no deadline pending finishes") {
    onPool { io ?=>
      val started = Promise[Unit]()
      Async.group {
        Future {
          started.complete(Success(()))
          io.perform(WaitingOp())
        }
        started.asFuture.await
      }
    }
  }

  test("an op with no deadline can be performed again after a cancel, and completed by what completes it") {
    onPool { io ?=>
      val op = WaitingOp()
      val started = Promise[Unit]()
      val cancelled = Future {
        started.complete(Success(()))
        io.perform(op)
      }
      started.asFuture.await
      cancelled.cancel()
      cancelled.awaitResult: Unit

      val performer = Future(io.perform(op))
      summon[Async].scheduler.execute(() => op.timerWakeup(System.nanoTime()))
      assertEquals(performer.awaitResult, Success(()))
    }
  }

  test("a time limit fails an op with no deadline of its own") {
    onPool { io ?=>
      val outcome = Future(io.perform(WaitingOp(), 20.millis)).awaitResult
      assert(outcome.failed.toOption.exists(_.isInstanceOf[TimeoutException]), outcome.toString)
    }
  }

  test("a cancel takes an op off the heap, where a time limit put it") {
    onPool { io ?=>
      val op = WaitingOp()
      val started = Promise[Unit]()
      val performer = Future {
        started.complete(Success(()))
        io.perform(op, 60.seconds)
      }
      started.asFuture.await
      val cancelledAt = System.nanoTime()
      performer.cancel()
      val outcome = performer.awaitResult
      assert(isCancelled(outcome), outcome.toString)
      assert(System.nanoTime() - cancelledAt < 10_000_000_000L, "it waited for the time limit")
    }
  }

  test("a deadline already passed, even a negative one, is on the heap and fires") {
    given KqueueBackend.type = KqueueBackend
    Io.run { io ?=>
      io.perform(io.backend.ops.deadlineTimer(-5L))
    }
  }
}
