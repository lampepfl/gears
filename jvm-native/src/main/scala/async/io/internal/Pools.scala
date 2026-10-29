package gears.async.io
package internal

import gears.async.Async

import java.util.concurrent.CancellationException

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
    val operation = op
    try {
      if ac.group.isCancelled then throw new CancellationException()
      operation.beginAwait(worker)
    } catch {
      case t: Throwable =>
        pinned.unpin()
        throw t
    }
    val completion = operation
    try {
      // The fast path: an op the reactor finishes at once returns here without suspending, which skips the whole await
      // and resume. Taken within a small budget per turn of the caller, so a future whose ops rarely wait still gives
      // the turn to those queued beside it, and beyond that only while no other task is queued, within a budget per
      // round of the scheduler, so the reactor still polls for futures waiting on events.
      // A refused op, such as a one-shot op submitted twice, throws from either call.
      val finished =
        try
          if worker.budget.canCompleteImmediately(!worker.isIdle) then {
            val now =
              try r.trySubmit(op, completion, timeoutMillis)
              catch {
                case failure: Throwable =>
                  worker.budget.completedImmediately()
                  throw failure
              }
            if now then worker.budget.completedImmediately()
            now
          } else {
            r.submit(op, completion, timeoutMillis)
            false
          }
        finally pinned.unpin() // before the await parks, which pinned would park the worker's thread
      if !finished then operation.awaitCompletion()
    } finally operation.endAwait()
  }
}
