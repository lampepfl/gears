package gears.async.io
package internal

import gears.async.Async
import gears.async.Cancellable
import gears.async.CompletionGroup
import gears.async.Listener

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** How an `OnPool` performs its ops on the pool: the same on every platform. `bound` is the `OnPool` a program is
  * given, over a scheduler of the pool's.
  */
private[io] object Pools {

  /** The `OnPool` over `b` on `scheduler`'s pool, whose workers drive `b`'s reactors: the program's `Io`. */
  def bound(b: ReactorBackend, scheduler: ReactorScheduler): OnPool[b.type] = new OnPool[b.type] {
    require(scheduler.backend eq b, s"$scheduler drives ${scheduler.backend}'s reactors, not $b's")
    val backend: b.type = b

    private[io] def performWithin(op: Op, timeoutMillis: Int)(using Async): Unit =
      performOnReactor(b, op, timeoutMillis, scheduler)

    /** Suspends the caller and queues its resume behind the tasks already queued; throws `CancellationException` if the
      * caller has been cancelled by the time it resumes. Cheaper than gears' own, which spawns a task.
      */
    override def `yield`()(using ac: Async): Unit = {
      ac.withGroup(CompletionGroup.Unlinked).await(Yield)
      if ac.group.isCancelled then throw new CancellationException()
    }
  }

  /** Submits `op`, which must have been built by `b.ops`, to the reactor of the worker running the caller, on
    * `scheduler`, the pool's, and suspends the caller until the reactor finishes with it: it returns when the op
    * completes, throws its failure, throws `CancellationException` when it is cancelled, or throws `TimeoutException`
    * when its time limit, `timeoutMillis` unless negative, ran out first (see `Submitter.submit`). A repeatable op may
    * go to a different reactor each time.
    *
    * Cancelling the caller asks the reactor to cancel the op, but this keeps waiting until the reactor confirms, so
    * once it returns or throws, the op's buffers and slots belong to the caller again.
    */
  private[io] def performOnReactor(b: ReactorBackend, op: b.Op, timeoutMillis: Int, scheduler: ReactorScheduler)(using
      ac: Async
  ): Unit = {
    // Pinned to its thread from finding the worker to submitting on it (see `ReactorScheduler.pin`): `performHere`
    // unpins once the op is submitted, before it waits.
    scheduler.pin()
    val worker =
      try scheduler.currentWorker
      catch {
        case t: Throwable =>
          scheduler.unpin()
          throw t
      }
    performHere(b, op, worker, scheduler, timeoutMillis)
  }

  /** `perform` on the worker running the caller, which `pinned` keeps there until the op is submitted: an op the
    * reactor finishes at once returns without suspending. The reactor keeps the time limit.
    */
  private def performHere(
      b: ReactorBackend,
      op: b.Op,
      worker: ReactorWorker,
      pinned: ReactorScheduler,
      timeoutMillis: Int
  )(using ac: Async): Unit = {
    val r = worker.reactor.asInstanceOf[Reactor.Of[b.Op, b.Handle]] // its reactor is one of `b`'s
    // An op performed again reuses its waiter, kept as its attachment (`Submitter.attachment`, which belongs to the op,
    // so any reactor of the backend reads it), so that performing it allocates nothing here, even when it last ran on
    // another worker. A waiter still in use (the op performed twice at once, which the reactor refuses) is left alone.
    val waiter =
      try {
        if ac.group.isCancelled then throw new CancellationException()
        r.attachment(op) match {
          case reused: OpWaiter if !reused.inUse =>
            reused.reset(worker)
            reused
          case _ =>
            val fresh = new OpWaiter(worker, op)
            r.attach(op, fresh)
            fresh
        }
      } catch {
        case t: Throwable =>
          pinned.unpin()
          throw t
      }
    waiter.inUse = true
    try {
      // The fast path: an op the reactor finishes at once returns here without suspending, which skips the whole await
      // and resume. Taken within a small budget per turn of the caller, so a future whose ops rarely wait still gives
      // the turn to those queued beside it, and beyond that only while no other task is queued, within a budget per
      // round of the scheduler, so the reactor still polls for futures waiting on events.
      // A refused op, such as a one-shot op submitted twice, throws from either call.
      val finished =
        try
          if worker.turnFastPathsLeft > 0 then {
            val now = r.trySubmit(op, waiter.completion, timeoutMillis)
            if now then worker.turnFastPathsLeft -= 1
            now
          } else if worker.fastPathsLeft > 0 && worker.isIdle then {
            val now = r.trySubmit(op, waiter.completion, timeoutMillis)
            if now then worker.fastPathsLeft -= 1
            now
          } else {
            r.submit(op, waiter.completion, timeoutMillis)
            false
          }
        finally pinned.unpin() // before the await parks, which pinned would park the worker's thread
      if !finished then {
        waiter.link() // Cancelling the caller now cancels the op; if it already was, this cancels it at once.
        // Awaited outside the caller's group, so cancellation cannot resume it before the reactor has confirmed.
        try ac.withGroup(CompletionGroup.Unlinked).await(waiter).get
        finally waiter.unlink()
      }
    } finally waiter.inUse = false
  }

  /** Cancels `op` on `worker`'s reactor, on its thread; the op was built by the backend whose reactor it is. */
  private def cancelOn(worker: ReactorWorker, op: Any): Unit = {
    val r = worker.reactor
    r.cancel(op.asInstanceOf[r.Op]): Unit
  }

  /** Never ready when polled, and completes a listener as soon as it is added, so that awaiting it resumes the caller
    * from the back of its scheduler's queue (see `OnPool.yield`).
    */
  private object Yield extends Async.Source[Unit] {
    override def poll(): Option[Unit] = None
    def poll(k: Listener[Unit]): Boolean = false
    def onComplete(k: Listener[Unit]): Unit = k.completeNow((), this)
    def dropListener(k: Listener[Unit]): Unit = ()
  }

  private val done = Success(())

  /** The failure of an op its caller cancelled, without a stack trace: one would show the reactor's internals, not the
    * caller's code, and capturing it is nearly all a cancel costs, which a program cancelling many ops at once, as a
    * scope's end or a batch of timers does, pays for each (the bench's `timers`, half of whose 200,000 timers are
    * cancelled, spent 95% of its time unwinding stacks).
    */
  private def cancelled(): CancellationException = new Cancelled

  private final class Cancelled extends CancellationException("The op was cancelled") {
    override def fillInStackTrace(): Throwable = this
  }

  /** An op's submissions, one at a time, each as a source that its completion completes once and as a member of the
    * caller's cancellation group. It has at most one listener, the suspended caller. Kept as the op's attachment and
    * reset for each submission, so it, its state and its completion are allocated once per op. Each submission may be
    * on a different worker; `worker` is the current one's.
    */
  private final class OpWaiter(first: ReactorWorker, op: Any) extends Async.Source[Try[Unit]] with Cancellable {

    /** Whether a `perform` is using it. On the thread performing the op only. */
    var inUse = false

    /** The worker, and so the reactor, of the current submission. Read by `cancel` from any thread. */
    @volatile private var worker = first

    // Counts submissions, so that a cancel requested from another thread for one cannot reach the next.
    @volatile private var generation = 0

    /** Readies it for the op's next submission, on `next`'s reactor, after the previous one has finished. The worker
      * changes before the generation, so a `cancel` that reads the new generation also finds the new worker.
      */
    def reset(next: ReactorWorker): Unit = {
      if worker ne next then worker = next // usually the same, and a volatile write costs more than a read
      generation += 1
      state.set(OpWaiter.Empty)
    }

    // Empty, then either the suspended caller's listener or the outcome, and finally the outcome, so exactly one side
    // delivers the outcome: `finish` to a listener it finds, or `onComplete` to itself when it finds the outcome
    // already there. The completion runs on the reactor's thread; the caller registers its listener on the thread
    // it is on by then, which on the JVM may be another worker's, since a virtual thread is pinned only until its op
    // is submitted (see `ReactorScheduler.pin`), so the two race, and the field is atomic. On Scala Native nothing
    // moves the caller, and a plain field would do, but measured against this it is within noise (kqueue ping-pong
    // 8.74 against 8.67 µs), so one shared field it is.
    private val state = new AtomicReference[AnyRef](OpWaiter.Empty)

    // Completions are contravariant, so this one fits the op of any reactor.
    val completion: Completion[Any] = new Completion[Any] {
      def onComplete(op: Any): Unit = finish(done)
      override def onFailure(op: Any, failure: Throwable): Unit = finish(Failure(failure))
      override def onCancel(op: Any): Unit = finish(Failure(cancelled()))
    }

    private def finish(outcome: Try[Unit]): Unit =
      state.getAndSet(outcome) match {
        case k: Listener[Try[Unit]] @unchecked => k.completeNow(outcome, this)
        case _                                 => ()
      }

    // gears' `await` calls this first; the default builds a listener and an `Option` to find out.
    override def poll(): Option[Try[Unit]] =
      state.get() match {
        case outcome: Try[Unit] @unchecked => Some(outcome)
        case _                             => None
      }

    def poll(k: Listener[Try[Unit]]): Boolean =
      state.get() match {
        case outcome: Try[Unit] @unchecked =>
          k.completeNow(outcome, this)
          true
        case _ => false
      }

    def onComplete(k: Listener[Try[Unit]]): Unit = {
      var done = false
      while !done do
        state.get() match {
          case outcome: Try[Unit] @unchecked =>
            k.completeNow(outcome, this)
            done = true
          case OpWaiter.Empty => done = state.compareAndSet(OpWaiter.Empty, k) // or the outcome arrived: look again
          case _              => done = true // Only the one caller ever waits.
        }
    }

    def dropListener(k: Listener[Try[Unit]]): Unit = state.compareAndSet(k, OpWaiter.Empty): Unit

    // Cancellation can come from any thread, so it is carried out on the thread of the reactor the op was submitted to.
    // By then the op may have finished, and a repeatable op may even have been submitted again, there or elsewhere,
    // which must not be cancelled.
    def cancel(): Unit = {
      val submission = generation
      val target = worker
      target.sync(
        if submission == generation && !state.get().isInstanceOf[Try[?]] then cancelOn(target, op)
      )
    }
  }

  private object OpWaiter {
    val Empty = new Object
  }
}
