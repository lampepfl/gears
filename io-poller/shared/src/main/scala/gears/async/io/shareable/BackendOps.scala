package gears.async.io.shareable

import gears.async.Async

/** An op of a backend whose event sources are `S`s: the workers of a pool, or the one event loop of a platform that has
  * no threads. It is of one of the kinds below, which is what says how it is performed (`performOn`): so an op of no
  * kind cannot be made, and what performs an op need not ask it what it is.
  */
trait BackendOp[S <: EventSource] extends TimerHeapOp {
  type Source = S

  /** Performs the op on `performer`, an event source of its backend's, as its kind is performed: when this returns the
    * op is done, and if it throws, that is its failure.
    */
  def performOn(performer: OpPerformer[S] & S)(using Async): Unit
}

/** The kinds of op a backend has. A backend's op is one of them, with the shared class that does its work; a backend
  * whose platform does some work in a way of its own adds a kind for it beside these.
  */
object BackendOp {

  /** An op that waits for its handle to be ready, on the event source that listens for it (see `ReadyWaiting`). */
  trait Readiness[S <: EventSource] extends BackendOp[S] {
    def performWhenReady(self: S)(using Async): Unit
    final def performOn(performer: OpPerformer[S] & S)(using Async): Unit = performWhenReady(performer)
  }

  /** An op done as it is performed, with nothing to wait for (see `ImmediateOp`). */
  trait Immediate[S <: EventSource] extends BackendOp[S] {
    def perform(owner: HandleOwner): Unit
    final def performOn(performer: OpPerformer[S] & S)(using Async): Unit = perform(performer.handleOwner)
  }

  /** An op whose work blocks (see `WorkOffThread`): done on the blocking pool's thread, on the performer's own, or on
    * a worker's with the worker handed over, as its backend chooses (`OpPerformer.performBlocking`).
    */
  trait OffThread[S <: EventSource] extends BackendOp[S] {
    def performOffThread(worker: Worker & S)(using Async): Unit
    def performOnCaller(worker: Worker & S)(using Async): Unit
    def performInPlace(worker: Worker & S)(using Async): Unit
    final def performOn(performer: OpPerformer[S] & S)(using Async): Unit = performer.performBlocking(this)
  }

  /** A connect, which makes the handle it then waits on (see `AbstractConnectOp`). */
  trait Connect[S <: EventSource] extends BackendOp[S] {
    def performConnect(self: S)(using Async): Unit
    final def performOn(performer: OpPerformer[S] & S)(using Async): Unit = performConnect(performer)
  }

  /** A timer or a deadline: an op that is nothing but its time on a heap (`OpPerformer.performTimed`). */
  trait Timed[S <: EventSource] extends BackendOp[S] {
    final def performOn(performer: OpPerformer[S] & S)(using Async): Unit = performer.performTimed(this)
  }

  /** A close, which waits for the handle's work and its listeners to let go (see `AbstractCloseOp`). */
  trait Close[S <: EventSource] extends BackendOp[S] {
    def performClose(self: S)(using Async): Unit
    final def performOn(performer: OpPerformer[S] & S)(using Async): Unit = performClose(performer)
  }
}

/** What performs a backend's ops, each as its kind is performed: an event source of the backend's, a worker of a pool
  * or a platform's one event loop. What it does with an op that blocks is its backend's to say (`performBlocking`), as
  * is anything else it does with a timer than wait for it.
  */
trait OpPerformer[S <: EventSource] { self: S =>

  /** Does the work of an op that blocks, where this backend does it. */
  protected[io] def performBlocking(work: BackendOp.OffThread[S])(using Async): Unit

  /** Waits for a timer or a deadline on this source's heap. */
  protected[io] def performTimed(timed: BackendOp.Timed[S])(using Async): Unit = {
    timed.holdTimerOn(this)
    timed.parkUntilSettled()
  }
}
