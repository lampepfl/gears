package gears.async.io
package internal

import java.util.concurrent.TimeoutException

/** What a loop backend's reactor does the same whatever it waits on, kqueue's and NIO's alike, for ops of type `O`
  * (`LoopOp`): the timer heap, with the timers and the ops' time limits in it, and the thread that runs the loop. The
  * backend says how it waits for readiness and polls.
  */
abstract class ReactorCore[O <: LoopOp[O]] extends Reactor with TimerWaiter with LoopPromise.Owner[O] {

  /** Every reactor of the backend runs the same kinds of op, so backend-specific code may build them directly, and an
    * op may go to any of its reactors in turn.
    */
  type Op = O

  /** A promise of this reactor's: one of the backend's ops that is a `LoopPromise`. */
  protected type Promise = O & LoopPromise[O]

  def attachment(op: Op): AnyRef | Null = op.attachment
  def attach(op: Op, value: AnyRef | Null): Unit = op.attachment = value

  // Pending timers, and pending ops with a time limit, by deadline (see `fireDueTimers`).
  protected final val timers = new TimerHeap[O]
  private var nextTimerOrder = 0L
  private var running = false

  // The thread that runs the loop: the one that opens the reactor, which owns it, or the one that calls `run`. A promise
  // resolved on it needs no wake-up, because the loop delivers every resolved promise before it polls again. Not
  // refreshed by every `runOnce`: on macOS `Thread.currentThread` is a call into thread-local storage.
  @volatile protected final var loopThread: Thread = Thread.currentThread()
  @volatile protected final var closed = false

  /** Lets go of `op`'s claim on its handle (see `claim`), as its completion is taken or it finishes in `trySubmit`. */
  private def release(op: O): Unit = {
    val claim = op.claim
    if claim != null then claim.release(op)
  }

  /** Takes a pending op's completion off it, ending the submission, and its time limit with it: the completion may then
    * submit the op again.
    */
  protected final def take(op: O): Completion[O] = {
    val completion = op.completion.nn
    op.completion = null
    release(op)
    if op.heapIndex >= 0 then timers.remove(op)
    op match {
      case timer: LoopTimer[?] => timer.waiter = null // a move no longer concerns this reactor
      case _                   => ()
    }
    completion
  }

  protected final def complete(op: O): Unit = deliverComplete(take(op), op)
  protected final def fail(op: O, failure: Throwable): Unit = deliverFailure(take(op), op, failure)

  /** Common immediate-readiness path. Inlined so a system-call attempt needs no closure or result wrapper. Even a
    * completed attempt is queued when the worker's cooperative budget requires a scheduling boundary.
    */
  protected inline def attemptNow(op: O, queue: Boolean)(inline perform: Boolean): Boolean = {
    val done =
      try perform
      catch {
        case failure: Throwable =>
          op.atOnceFailure = failure
          true
      }
    if done && queue then ready.add(op)
    done
  }

  /** Runs the completion of `op`, which finished: `onFailure` with the failure it kept (`atOnceFailure`), if any, else
    * `onComplete`.
    */
  protected final def finish(op: O): Unit = {
    val failure = op.atOnceFailure
    if failure != null then {
      op.atOnceFailure = null
      fail(op, failure)
    } else complete(op)
  }

  // Commands that finished as soon as they were submitted, and other ops that finished without waiting, whose
  // completions run on the next turn. Loop thread only.
  protected final val ready = new java.util.ArrayDeque[O]()

  /** Runs the completions of the ops that finished when submitted, as many as were queued when it started: a completion
    * that submits a command which finishes at once is left for the next turn, so one busy handle cannot keep the loop
    * from polling. Returns whether there were any.
    */
  protected final def deliverReady(): Boolean = BoundedDrain.snapshot(ready)(op => finish(op.nn))

  // Close ops that finished (`LoopOp.closes`), at once, from another thread or notified, whose completions run at the
  // end of the turn after the one they were queued in: by then that reactor has delivered the failures of the ops they
  // ended there, so that a close completes after them (`Ops.close`). Loop thread only.
  protected final val closing = new java.util.ArrayDeque[O]()

  /** Runs the completions of the first `due` close ops queued, those queued before this turn began. */
  protected final def deliverClosing(due: Int): Unit = BoundedDrain.prefix(closing, due)(op => finish(op.nn))

  // Timers whose deadline moved earlier, told from another thread, for the loop to file again (`moved`).
  private val movedQueue = new java.util.concurrent.ConcurrentLinkedQueue[O & LoopTimer[O]]()

  /** A pending timer's deadline moved earlier than it is filed under: filed again here, or by the loop, woken. */
  def moved(timer: TimerState): Unit = timer match {
    case t: LoopTimer[?] =>
      val own = t.asInstanceOf[O & LoopTimer[O]] // only this reactor's timers name it their waiter
      if Thread.currentThread() eq loopThread then refile(own)
      else if own.queueMove(this) then {
        movedQueue.add(own)
        wakeup()
      }
    case _ => ()
  }

  /** Files `timer` again under its key, if it is still pending here. Loop thread only. */
  private def refile(timer: O & LoopTimer[O]): Unit =
    // A move queued before cancellation may arrive after reuse on another poller. Its heap index then belongs there.
    if (timer.waiter eq this) && timer.heapIndex >= 0 && timer.completion != null then {
      timers.remove(timer)
      file(timer)
    }

  /** Files the timers moved earlier from other threads since the last turn. */
  protected final def deliverMoved(): Unit = {
    var timer = movedQueue.poll()
    while timer != null do {
      timer.takeMove(this)
      refile(timer)
      timer = movedQueue.poll()
    }
  }

  /** Puts `timer` in the heap under its key (see `TimerState`). */
  private def file(timer: O & LoopTimer[O]): Unit = {
    val key = timer.key
    timer.when = key
    timer.filed = key
    timer.order = nextTimerOrder
    nextTimerOrder += 1
    timers.add(timer)
  }

  /** Puts `op` in the timer heap, due in `milliseconds`: a timer, to complete then, or an op with a time limit, to time
    * out then (see `fireDueTimers`).
    */
  private def schedule(op: O, milliseconds: Int): Unit = {
    op.when = System.nanoTime() + milliseconds * 1_000_000L
    op.order = nextTimerOrder
    nextTimerOrder += 1
    timers.add(op)
  }

  // Ops submitted here waiting for their handle's turn (see `Turns`), and those given it, from any thread, for the loop
  // to go on submitting (`deliverTurns`).
  private val queued = scala.collection.mutable.HashSet.empty[O]
  private val turnsCome = new java.util.concurrent.ConcurrentLinkedQueue[O]()

  /** Leaves `op`, pending, to wait for the turn `turns` holds for another op, its time limit running meanwhile. */
  private def queueForTurn(op: O, turns: Turns, pending: Completion[O], timeoutMillis: Int): Unit = {
    op.completion = pending
    if timeoutMillis >= 0 then schedule(op, timeoutMillis)
    queued += op
    turns.enqueue(
      op,
      () => {
        turnsCome.add(op)
        if !(Thread.currentThread() eq loopThread) then wakeup()
      }
    )
  }

  /** Goes on submitting the ops given their turn since the last turn of the loop, still waiting for it here. */
  protected final def deliverTurns(): Boolean = {
    var any = false
    var next = turnsCome.poll()
    while next != null do {
      val op = next.nn // `O` is abstract, so `next` is not narrowed
      val claim = op.claim
      // One withdrawn meanwhile, and perhaps submitted again since, holds no turn it was given before.
      if claim != null && claim.holds(op) && queued.remove(op) then {
        any = true
        val pending = op.completion.nn
        op.completion = null
        submitHolding(op, pending, null, finishNow = false, timeoutMillis = -1): Unit // its time limit is filed
      }
      next = turnsCome.poll()
    }
    any
  }

  final def submit[C <: Op](op: C, completion: Completion[C], timeoutMillis: Int): Unit =
    submitOrFinish(op, completion, finishNow = false, timeoutMillis): Unit

  final def trySubmit[C <: Op](op: C, completion: Completion[C], timeoutMillis: Int): Boolean =
    submitOrFinish(op, completion, finishNow = true, timeoutMillis)

  /** Submits `op`. A command tried at once that finishes queues its completion for the next turn, or, with `finishNow`,
    * takes the completion back off it uncalled, throws a failure, and returns true. An op left waiting, with a time
    * limit, goes into the timer heap to time out when it is due. Checked first, in this order, each failing the op as
    * it is submitted: a slot still holding a value (`slotRefusal`), the op as it stands (`refusal`), and its handle's
    * turn held by another op, which it waits for if it may (`claim`).
    */
  private def submitOrFinish[C <: Op](
      op: C,
      completion: Completion[C],
      finishNow: Boolean,
      timeoutMillis: Int
  ): Boolean = {
    if closed then throw new IllegalStateException(s"The reactor is closed: $op")
    op.markSubmitted()
    require(op.completion == null, s"$op is already pending")
    val pending = completion.asInstanceOf[Completion[O]] // only ever called with `op`, which is a C
    var refusal = op.slotRefusal
    if refusal == null then refusal = op.refusal
    val claim = op.claim
    if refusal == null && claim != null && !claim.take(op) && op.waitsItsTurn then {
      queueForTurn(op, claim, pending, timeoutMillis)
      false
    } else {
      if refusal == null && claim != null && !claim.holds(op) then refusal = Checks.alreadyPending(op)
      submitHolding(op, pending, refusal, finishNow, timeoutMillis)
    }
  }

  /** Submits `op`, holding its handle's turn if it takes one, if it is one of the backend's own kinds, which wait on
    * what the backend polls, or that the backend handles otherwise than here, such as a close: returns
    * `ReactorCore.Finished` if it finished, its failure, if any, in `atOnceFailure`, which only `finishNow` allows;
    * `ReactorCore.Waits` if it is left pending, its time limit to be filed; `ReactorCore.Queued` if its completion was
    * queued to run on a later turn (`ready`, `closing`); or `ReactorCore.NotOwn` for a timer, a promise or an at-once
    * op the backend leaves to this class. Checked first, so that the backend's commands, its hot path, meet no other
    * type test before theirs.
    */
  protected def submitOwn(op: O, pending: Completion[O], finishNow: Boolean): Int

  /** Goes on submitting `op`, which holds its handle's turn if it takes one, failing it with `refusal` if not null. */
  private def submitHolding(
      op: O,
      pending: Completion[O],
      refusal: Throwable | Null,
      finishNow: Boolean,
      timeoutMillis: Int
  ): Boolean = {
    var finished = false
    var waits = false
    if refusal != null then finished = refuseNow(op, pending, refusal, finishNow) == ReactorCore.Finished
    else {
      val own = submitOwn(op, pending, finishNow)
      if own == ReactorCore.Finished then finished = true
      else if own == ReactorCore.Waits then waits = true
      else if own == ReactorCore.NotOwn then
        op match {
          case t: LoopTimer[?] =>
            val timer = t.asInstanceOf[O & LoopTimer[O]]
            require(timer.heapIndex < 0, "This timer is already pending")
            // Due at its time, or failed at its time limit if that comes first (see `TimerState`).
            timer.start(System.nanoTime(), timeoutMillis)
            timer.completion = pending
            timer.waiter = this
            file(timer)
          case p: LoopPromise[?] =>
            val promise = p.asInstanceOf[Promise]
            promise match {
              case blocking: LoopBlocking[?] if blocking.cancelling != null =>
                throw new IllegalStateException(s"$op is still running after being cancelled")
              case _ => ()
            }
            promise.reset() // a `Repeatable` promise's previous outcome
            promise.reactor = this
            promise.completion = pending
            pendingPromises += promise
            promise match {
              case blocking: LoopBlocking[?] =>
                blocking.restart()
                Blockers.run(blocking)
              case _ => ()
            }
            waits = true
          case atOnce: LoopAtOnce[?] =>
            op.completion = pending
            try atOnce.perform()
            catch case t: Throwable => op.atOnceFailure = t
            if op.closes then closing.add(op) // completed after the failures it causes, never in trySubmit
            else if finishNow then finished = true
            else ready.add(op)
          case _ => throw new IllegalStateException(s"Unexpected op $op")
        }
    }
    if waits && timeoutMillis >= 0 then schedule(op, timeoutMillis)
    if finished then {
      op.completion = null // finished here, so the completion is never called
      release(op)
      val failure = op.atOnceFailure
      if failure != null then {
        op.atOnceFailure = null
        throw failure
      }
    }
    finished
  }

  // Promises resolve on other threads; they queue here and wake the loop.
  protected final val resolvedQueue = new java.util.concurrent.ConcurrentLinkedQueue[Promise]()

  // Promises submitted here whose completion has not run, so `close` can cancel them. Loop thread only.
  protected final val pendingPromises = scala.collection.mutable.HashSet.empty[Promise]

  // Cancelled blockers whose `block` was still running, so their `onCancel` waits for it to return. Loop thread only.
  private val cancellingBlockers = scala.collection.mutable.HashSet.empty[O & LoopBlocking[O]]

  /** Called from any thread when a promise has its result: queue it and wake the loop. On the loop thread itself the
    * loop is not polling, so queueing is enough.
    */
  private[io] final def resolved(promise: Promise): Unit =
    if !closed then { // otherwise its completion was dropped by `close`
      resolvedQueue.add(promise)
      if !(Thread.currentThread() eq loopThread) then wakeup()
    }

  /** Runs the completions of the promises resolved when it starts, on the loop thread. One resolved meanwhile, such as
    * by a completion, waits for the next turn, so completions that keep resolving promises cannot keep the loop from
    * polling. Returns whether there were any.
    */
  protected final def deliverResolved(): Boolean = {
    var remaining = resolvedQueue.size() // traverses the queue, which stays short
    var next = if remaining > 0 then resolvedQueue.poll() else null
    val any = next != null
    while next != null do {
      val promise = next.nn // `O` is abstract, so `next` is not narrowed
      val completion = promise.completion
      promise.completion = null
      if promise.heapIndex >= 0 then timers.remove(promise) // its time limit ends with it
      pendingPromises -= promise
      promise match {
        case blocking: LoopBlocking[O] @unchecked if blocking.cancelling != null =>
          // A cancelled blocker's `block` has returned, so its op's buffers and slots are the caller's again. Its
          // outcome is dropped: `cancel` detached it before deferring, and `submit` refuses it until now.
          assert(completion == null, "a blocker queued its cancel was resubmitted")
          val cancelled = blocking.cancelling.nn
          val failure = blocking.cancelledBy
          blocking.cancelling = null
          blocking.cancelledBy = null
          cancellingBlockers -= blocking
          if failure == null then deliverCancel(cancelled, blocking) else deliverFailure(cancelled, blocking, failure)
        case _ =>
          if completion != null then {
            val failure = promise.failure
            if promise.closes then { // completed after the failures it causes, at the end of the next turn
              promise.completion = completion
              promise.atOnceFailure = failure
              closing.add(promise)
            } else if failure != null then deliverFailure(completion, promise, failure)
            else deliverComplete(completion, promise)
          }
      }
      remaining -= 1
      next = if remaining > 0 then resolvedQueue.poll() else null
    }
    any
  }

  /** Detaches a pending promise so its outcome is never delivered. The caller skips or interrupts a blocking task. */
  private def detach(promise: Promise): Completion[O] | Null = {
    val completion = promise.completion
    promise.completion = null
    if promise.heapIndex >= 0 then timers.remove(promise) // its time limit ends with it
    pendingPromises -= promise
    completion
  }

  /** A command that finished when submitted counts as finished: its completion still runs, and this returns false. A
    * pending command stops waiting (`withdrawOwn`); a pending timer is deleted; a promise is detached, so completing it
    * later does nothing, and a blocking task is skipped or interrupted. The completion's `onCancel` then runs before
    * this returns, except for a blocking task that was running: its `onCancel` runs from the loop once `block` has
    * returned.
    */
  final def cancel(op: Op): Boolean = {
    val completion = withdraw(op)
    if completion == null then false
    else {
      settleWithdrawn(op, completion, null)
      true
    }
  }

  /** Fails `op`, whose time limit ran out, with `TimeoutException`: withdrawn as a cancelled op is, then `onFailure`
    * where that would be `onCancel`. Nothing if it is no longer pending.
    */
  private def timedOut(op: O): Unit = {
    val completion = withdraw(op)
    if completion != null then settleWithdrawn(op, completion, new TimeoutException(s"$op timed out"))
  }

  /** Takes `op` out of what the backend waits on, if it is one of its own kinds (see `submitOwn`), and returns its
    * completion (`take`): null if it is not pending here, or `ReactorCore.NotOwnCompletion` if it is a timer, a promise
    * or an at-once op, which this class withdraws.
    */
  protected def withdrawOwn(op: O): Completion[O] | Null

  /** Closes what `op`, withdrawn before its completion ran, opened, which never reaches the caller (`LoopOpens`):
    * nothing for an op that opens nothing. A backend whose ops open otherwise adds them.
    */
  protected def abandon(op: O): Unit = op match {
    case opens: LoopOpens[?] => opens.abandon()
    case _                   => ()
  }

  /** Starts `connect` as it is submitted (`LoopConnects.start`), opening its socket, and returns true; or, if that
    * fails, delivers the failure as an at-once op's is, queued for the next turn or, with `finishNow`, thrown by
    * `submitOrFinish`, and returns false.
    */
  protected final def started(connect: O & LoopConnects[O, ?], pending: Completion[O], finishNow: Boolean): Boolean =
    try {
      connect.start()
      true
    } catch {
      case t: Throwable =>
        refuseNow(connect, pending, t, finishNow): Unit
        false
    }

  /** Fails `op` as it is submitted, with `failure`, before it does anything: its completion queued for the next turn,
    * or, with `finishNow`, left for `submitOrFinish` to take back and throw `failure`. Returns what `submitOwn` returns
    * for it, `ReactorCore.Finished` or `ReactorCore.Queued`.
    */
  protected final def refuseNow(op: O, pending: Completion[O], failure: Throwable, finishNow: Boolean): Int = {
    op.completion = pending
    op.atOnceFailure = failure
    if finishNow then ReactorCore.Finished
    else {
      ready.add(op)
      ReactorCore.Queued
    }
  }

  /** Takes a pending op out of the reactor, as `cancel` describes, and returns its completion: null if it is not
    * pending here.
    */
  private def withdraw(op: O): Completion[O] | Null =
    if queued.remove(op) then {
      // Waiting for its turn, or given it and not yet submitted again: out of the queue, or the turn passed on.
      val claim = op.claim
      if claim != null then claim.withdraw(op): Unit
      take(op)
    } else {
      val own = withdrawOwn(op)
      if !(own eq ReactorCore.NotOwnCompletion) then own
      else
        op match {
          case timer: LoopTimer[?] => if timer.heapIndex < 0 then null else take(op)
          case blocking: LoopBlocking[?] if !blocking.cancelUnlessCommitted() => null // it wrote its result: finishing
          case p: LoopPromise[?] => // one no longer pending is a close that finished, queued in `closing`
            if pendingPromises.contains(p.asInstanceOf[Promise]) then detach(p.asInstanceOf[Promise]) else null
          case _ => null // an at-once op, performed when submitted, so finished
        }
    }

  /** Runs the completion of an op `withdraw` took out: `onCancel`, or `onFailure` with `failure` for one that timed
    * out; for a blocking task that was running, from the loop once `block` has returned.
    */
  private def settleWithdrawn(op: O, completion: Completion[O], failure: Throwable | Null): Unit =
    op match {
      case b: LoopBlocking[?] if b.interrupt() =>
        val blocking = b.asInstanceOf[O & LoopBlocking[O]]
        blocking.cancelling = completion
        blocking.cancelledBy = failure
        cancellingBlockers += blocking
      case _ =>
        abandon(op)
        if failure == null then deliverCancel(completion, op) else deliverFailure(completion, op, failure)
    }

  /** Completes the timers that are due, earliest first, and times out the ops whose time limit is. Only entries
    * scheduled before it started fire, so a completion that submits a zero-length timer leaves it for the next turn.
    */
  protected final def fireDueTimers(): Unit =
    if !timers.isEmpty then {
      val now = System.nanoTime()
      val submittedBefore = nextTimerOrder
      while !timers.isEmpty && timers.peek.when <= now && timers.peek.order < submittedBefore do
        timers.poll() match {
          case t: LoopTimer[?] =>
            val timer = t.asInstanceOf[O & LoopTimer[O]]
            timer.due(now) match {
              case TimerState.Complete => complete(timer)
              case TimerState.Expire   => fail(timer, new TimeoutException(s"$timer timed out"))
              case _                   => file(timer) // its deadline moved later
            }
          case op => timedOut(op)
        }
    }

  /** Takes every op pending on what the backend waits on, its commands, and hands each to `drop`, with `take`'s
    * completion, as `close` begins; and forgets them.
    */
  protected def dropOwn(drop: O => Unit): Unit

  /** Releases the backend's own resources as `close` ends, once no pending op is left: sets `closed`, under what guards
    * waking the loop, after which no wake-up may touch them.
    */
  protected def closeOwn(): Unit

  /** Cancels every pending op, interrupting blockers, then releases the backend's resources (`closeOwn`). A blocker
    * still running is waited for before its `onCancel`. An op that had already finished, whose completion had not run
    * yet, gets its outcome, not a cancel: what it read or opened is the caller's.
    */
  final def close(): Unit =
    if !closed then {
      // Detach every pending op first, so nothing can be delivered while the reactor shuts down.
      val dropped = scala.collection.mutable.ArrayBuffer.empty[(O, Completion[O])]
      val finished = scala.collection.mutable.ArrayBuffer.empty[(O, Completion[O], Throwable | Null)]
      def drop(op: O): Unit = dropped += ((op, take(op)))
      dropOwn(drop)
      for op <- queued do {
        val claim = op.claim
        if claim != null then claim.withdraw(op): Unit
        drop(op)
      }
      queued.clear()
      turnsCome.clear()
      while !ready.isEmpty do { // finished, but their completions have not run
        val op = ready.poll().nn
        val failure = op.atOnceFailure
        op.atOnceFailure = null
        finished += ((op, take(op), failure))
      }
      while !closing.isEmpty do { // closed their handles, but their completions have not run
        val op = closing.poll().nn
        val failure = op.atOnceFailure
        op.atOnceFailure = null
        finished += ((op, take(op), failure))
      }
      var resolved = resolvedQueue.poll()
      while resolved != null do { // finished on another thread; a cancelled blocker among them is settled below
        val promise = resolved.nn
        promise match {
          case blocking: LoopBlocking[?] if blocking.cancelling != null => ()
          case _                                                        =>
            val completion = detach(promise)
            if completion != null then finished += ((promise, completion, promise.failure))
        }
        resolved = resolvedQueue.poll()
      }
      val running = scala.collection.mutable.ArrayBuffer.empty[BlockingRun]
      // Blockers that wrote their result: as good as finished, so they get their outcome once they return.
      val written = scala.collection.mutable.ArrayBuffer.empty[(O & LoopBlocking[O], Completion[O])]
      for promise <- pendingPromises.toList do {
        val completion = detach(promise)
        if completion != null then
          promise match {
            case blocking: LoopBlocking[O] @unchecked if !blocking.cancelUnlessCommitted() =>
              written += ((blocking, completion))
              running += blocking
            case _ =>
              dropped += ((promise, completion))
              promise match {
                case blocking: LoopBlocking[?] if blocking.interrupt() => running += blocking
                case _                                                 => ()
              }
          }
      }
      for blocking <- cancellingBlockers do {
        dropped += ((blocking, blocking.cancelling.nn))
        blocking.cancelling = null
        blocking.cancelledBy = null
        running += blocking
      }
      cancellingBlockers.clear()
      // What is left in the heap are timers: the other ops in it left it as they were taken or detached above.
      while !timers.isEmpty do drop(timers.poll())
      resolvedQueue.clear()
      closeOwn()
      running.foreach(_.waitForEnd()) // Until then they may still write to their ops' buffers and slots.
      for (blocking, completion) <- written do finished += ((blocking, completion, blocking.thrownBy))
      // Only now tell the completions, so one that throws cannot leak the reactor's resources.
      finished.foreach { (op, completion, failure) =>
        if failure == null then deliverComplete(completion, op) else deliverFailure(completion, op, failure)
      }
      dropped.foreach { (op, completion) =>
        abandon(op) // what it opened never reaches the caller
        deliverCancel(completion, op)
      }
    }

  // Turns in a row that delivered completions without polling. A turn that delivered something skips the poll, since
  // the completions it ran will likely submit more work; every `ReactorCore.PollEvery` turns it polls anyway, so
  // handles that only a poll can report are not starved. Loop thread only.
  private var turnsWithoutPoll = 0

  /** One turn of the loop, the same on every loop backend: timers whose deadlines moved are refiled; the ops pending on
    * handles closed since the last turn fail, before anything else, so that a close's failures come first whatever else
    * the turn delivers; then the ops whose turn on a handle came, those that finished when submitted, resolved promises
    * and what the backend delivers of its own; then it polls once, blocking only if asked to and nothing was delivered
    * first, and skipping the poll after delivering something, except every `ReactorCore.PollEvery` turns
    * (`turnsWithoutPoll`); then due timers fire, promises resolved meanwhile are delivered, and the closes queued
    * before the turn complete, after the failures they caused.
    */
  final def runOnce(block: Boolean): Unit = {
    deliverMoved()
    val closingDue = closing.size
    val delivered = // no short-circuiting
      deliverClosed() | deliverTurns() | deliverReady() | deliverResolved() | deliverOwn() | closingDue > 0
    if pollsThisTurn(delivered) then poll(block && !delivered)
    fireDueTimers()
    deliverResolved()
    deliverClosing(closingDue)
  }

  /** Fails, with the backend's failure for a closed handle, the ops pending on the handles closed since the last turn,
    * from any thread (`Ops.close`), and returns whether any were.
    */
  protected def deliverClosed(): Boolean

  /** Delivers what the backend has of its own to deliver each turn, before it polls, and returns whether it delivered
    * anything.
    */
  protected def deliverOwn(): Boolean

  /** Polls once and performs or completes what the poll found, waiting for something to happen if `block`, else not.
    */
  protected def poll(block: Boolean): Unit

  // Set by the first `wakeup` since the loop last polled, so later ones skip the backend's wake-up; cleared after each
  // poll, before anything it found is looked at.
  private val wakePending = new java.util.concurrent.atomic.AtomicBoolean()

  // Whether the loop is blocked in its poll, or about to be, and whether a wake-up was asked for since its last poll.
  // `wakeup` sets the second, then wakes the poll only if the first is set; the loop sets the first, then blocks only
  // if the second is not (`mayBlock`), so one of them always sees the other: a wake-up while the loop is awake, running
  // completions or a caller's tasks, costs no system call, neither the backend's wake-up nor the event it makes.
  @volatile private var parked = false
  @volatile private var wakeRequested = false

  /** Guards the backend's wake-up against its close, after which what it wakes may be gone, or its number reused. */
  protected final val wakeLock = new Object

  /** Wakes the loop from another thread, if it is blocked in its poll or about to be; else the turn it is in, or the
    * next, delivers what the wake-up was for.
    */
  final def wakeup(): Unit = {
    wakeRequested = true
    if (parked || signalsWhileAwake) && wakePending.compareAndSet(false, true) then
      wakeLock.synchronized {
        if !closed then wakeNow()
      }
  }

  /** Makes the poll the loop is blocked in, or its next one, return: kqueue's user event, or `Selector.wakeup`. */
  protected def wakeNow(): Unit

  /** Whether `wakeup` signals the loop while it is awake too, once until its next poll, rather than only while it is
    * parked: a backend's choice, by measurement. Not signalling saves a system call per wake-up, but the signal's call
    * keeps the waking thread awake for its length, and on macOS a blocker thread that goes back to sleep at once costs
    * the next hand-off a thread wake-up: kqueue's `blocking` went from 5.5 to 25 µs without it (docs/io/kqueue.md).
    */
  protected def signalsWhileAwake: Boolean = false

  /** Marks the loop parked, about to block in its poll, and returns whether it may: false, and unmarked, if a wake-up
    * was asked for since its last poll, so the poll must not block. A blocking poll calls `unpark` once it returns.
    */
  protected final def mayBlock(): Boolean = {
    parked = true
    if wakeRequested then {
      parked = false
      false
    } else true
  }

  protected final def unpark(): Unit = parked = false

  /** Whether a wake-up was asked for since the last poll: what the wake-up was for is waiting to be delivered, so a
    * backend spinning before it blocks stops, as it would on the event the wake-up made if the loop were parked.
    */
  protected final def wakeAsked: Boolean = wakeRequested

  /** Takes the wake-ups asked for until now as answered: called after each poll, before anything it found is looked at,
    * since the turn then delivers what they were for.
    */
  protected final def tookWakeUps(): Unit = {
    wakeRequested = false
    wakePending.set(false)
  }

  /** Whether this turn of `runOnce` polls, having `delivered` completions before it or not (see `turnsWithoutPoll`).
    */
  private def pollsThisTurn(delivered: Boolean): Boolean =
    if !delivered then {
      turnsWithoutPoll = 0
      true
    } else if turnsWithoutPoll < ReactorCore.PollEvery then {
      turnsWithoutPoll += 1
      false
    } else {
      turnsWithoutPoll = 0
      true
    }

  def stop(): Unit = running = false

  def run(): Unit = {
    loopThread = Thread.currentThread()
    running = true
    while running do runOnce(block = true)
  }
}

object ReactorCore {

  /** A loop that keeps delivering completions polls at least once in this many turns. */
  inline val PollEvery = 16

  // What `submitOwn` did with an op (see there).
  inline val Queued = 0
  inline val Finished = 1
  inline val Waits = 2
  inline val NotOwn = -1

  /** What `withdrawOwn` returns for an op it leaves to `ReactorCore`. */
  object NotOwnCompletion extends Completion[Any] {
    def onComplete(op: Any): Unit = ()
  }
}
