package gears.async.io
package uring

import gears.async.Async
import gears.async.io.shareable.BackendOp
import gears.async.io.shareable.HandleOwner
import gears.async.io.shareable.PoolOpWorker
import gears.async.io.shareable.PoolSource
import gears.async.io.shareable.Starvation
import gears.async.io.shareable.WorkpoolScheduler
import gears.async.native.NativeSuspend

import java.io.IOException
import scala.scalanative.posix.errno
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.util.Failure
import scala.util.Try

import uring.*
import uringStructOps.*

/** The io_uring backend's pool: io-poller's workers, each with a ring for its poller. */
final class UringScheduler private[uring] (rings: Array[Ring], onStarvation: Starvation.Handler)
    extends WorkpoolScheduler[UringOps.type](UringBackend, UringOps, rings.length, onStarvation),
      NativeSuspend {
  override protected def selectWorker() = workerOfCurrentThread()

  override def makeWorker(id: Int, owner: HandleOwner): UringWorker = new UringWorker(id, owner, rings(id), this)

  private[uring] def workerOfThread(thread: Thread): AnyRef | Null = workerOf(thread)

  private[uring] def failRing(cause: IOException): Unit = workerOfCurrentThread().asInstanceOf[UringWorker].fail(cause)
}

object UringScheduler {

  /** A pool of `workers` workers, each with its ring, made here, so that one that cannot be set up, past a limit on
    * locked memory or on rings, fails this call rather than its worker's thread.
    */
  def apply(workers: Int, onStarvation: Starvation.Handler = Starvation.printWarning): UringScheduler = {
    val rings = new Array[Ring](workers)
    var made = 0
    try
      while made < workers do {
        rings(made) = new Ring(Entries, Completions)
        made += 1
      }
    catch {
      case e: Throwable =>
        for i <- 0 until made do rings(i).close()
        throw e
    }
    try new UringScheduler(rings, onStarvation)
    catch {
      case e: Throwable =>
        rings.foreach(_.close())
        throw e
    }
  }

  inline val Entries = 256

  // - rings are locked memory (8 MiB by default), and the kernel frees a closed one late
  inline val Completions = 2048

  private[uring] val kept = new java.util.concurrent.ConcurrentLinkedQueue[Command]()
}

/** A worker whose poller is its ring: it prepares its commands' entries and reaps their completions on its own thread,
  * which is whichever thread drives it.
  */
final class UringWorker private[uring] (id: Int, owner: HandleOwner, ring: Ring, pool: UringScheduler)
    extends PoolOpWorker[UringWorker](id, owner),
      PoolSource {
  type Op = UringOp
  type Handle = Nothing

  def startListening(handle: Nothing, read: Boolean): Unit = ()

  protected[io] def performBlocking(work: BackendOp.OffThread[UringWorker])(using Async): Unit =
    work.performInPlace(this)

  // ---- the heap's guard: a flag, as `EventPoller` has it

  private val heapClaimed = new java.util.concurrent.atomic.AtomicBoolean(false)

  protected def claimHeap(): Unit =
    if !heapClaimed.compareAndSet(false, true) then
      var tries = 0
      while !heapClaimed.compareAndSet(false, true) do
        tries += 1
        if tries < 64 then Thread.onSpinWait() else Thread.`yield`()

  protected def releaseHeap(): Unit = heapClaimed.set(false)

  // ---- the ring, its own thread's alone

  // - the thread that drives this worker now: one that stepped out of it to block is no longer
  private def isCurrent: Boolean = pool.workerOfThread(Thread.currentThread()) eq this

  // - ring work runs only on this worker: inbox tasks may be stolen, or moved by the starvation monitor
  private val ringTasks = new java.util.concurrent.ConcurrentLinkedQueue[Runnable]()

  private[uring] def runOnRing(task: Runnable): Unit =
    if isCurrent then task.run()
    else {
      ringTasks.add(task)
      wake()
    }

  private def runLater(task: Runnable): Unit = {
    ringTasks.add(task)
    wake()
  }

  private def runRingTasks(): Unit = {
    var task = ringTasks.poll()
    while task != null do {
      try task.run()
      catch case e: Throwable => e.printStackTrace()
      task = ringTasks.poll()
    }
  }

  private val inFlight = scala.collection.mutable.LongMap.empty[Command]
  private val prepared = scala.collection.mutable.ArrayBuffer.empty[Command]
  private val submitted = scala.collection.mutable.ArrayBuffer.empty[Command]
  // - commands that found the submission queue full, submitted again once a turn's submission has made room
  private val backlog = scala.collection.mutable.ArrayBuffer.empty[Command]
  private var nextId = Ring.InternalId + 1

  ring.onSubmit = () => {
    var closed: scala.collection.mutable.ArrayBuffer[Command] | Null = null
    prepared.foreach { c =>
      if c.state == Command.Stage.Prepared then {
        if c.handle.isClosing then {
          ring.nop(c.sqe)
          inFlight.remove(c.id)
          c.outOfRing()
          if closed == null then closed = scala.collection.mutable.ArrayBuffer.empty[Command]
          closed.nn += c
        } else {
          c.submitted()
          submitted += c
        }
      }
    }
    prepared.clear()
    if closed != null then
      closed.nn.foreach { c =>
        c.discardSubmission()
        settle(c, Failure(c.handle.closedFailure), done = false)
      }
  }

  ring.afterSubmit = () => {
    submitted.foreach(c =>
      if c.state == Command.Stage.Submitted && c.handle.isClosing then
        if ring.pending(c.sqe, c.id) then {
          ring.nop(c.sqe)
          withdrawn(c)
          c.deliver(Failure(c.handle.closedFailure), done = false)
        } else ring.cancelNow(c.id, wait = false): Unit
    )
    submitted.clear()
  }

  // ---- performing a command, on the performer's thread, which is this worker's

  private[uring] def performCommand(c: Command)(using Async): Unit = {
    c.check()
    c.begin(this)
    try {
      val handle = c.handle
      handleOwner.check(handle)
      handle.claimWork()
      if !c.claimDirection() then {
        handle.releaseWork()
        throw Command.alreadyPending(c)
      }
      c.owner = this
      val turns = c.turns
      if turns != null && !turns.enter(c) then {
        c.exposeNow()
        if c.hasDeadline then c.holdTimerOn(this)
        c.awaitOutcome(this)
      } else {
        var parked = false
        val finished =
          try c.doneAtOnce() || { parked = !c.readyToSubmit(); false }
          catch {
            case failure: Throwable =>
              endUse(c)
              throw failure
          }
        if finished then endUse(c)
        else {
          c.exposeNow()
          if !parked then {
            c.onRing(ring)
            try submit(c)
            catch {
              case failure: Throwable =>
                c.discardSubmission()
                endUse(c)
                throw failure
            }
          }
          if c.hasDeadline then c.holdTimerOn(this)
          c.awaitOutcome(this)
        }
      }
    } finally c.ended()
  }

  private def submit(c: Command): Unit = {
    if hasGivenUp || ring.isClosed then throw new IOException("The io_uring worker's ring failed")
    val found = ring.nextSqe()
    if found == null then {
      c.backlogged()
      backlog += c
      return
    }
    val sqe = found.nn
    try c.prepareSubmission(sqe)
    catch {
      case failure: Throwable =>
        ring.nop(sqe)
        throw failure
    }
    val id = nextId
    nextId += 1
    sqe.user_data = id.toULong
    c.prepared(id, sqe)
    inFlight(id) = c
    prepared += c
  }

  // ---- completions

  private val dispatch: (Long, Int) => Unit = (id, res) => {
    val c = inFlight.getOrNull(id)
    if c != null then {
      inFlight -= id
      c.outOfRing()
      val handle = c.handle
      var done = false
      val outcome: Try[Unit] | Null =
        if handle.isClosing then {
          c.dropCompletion(res)
          c.discardSubmission()
          Failure(handle.closedFailure)
        } else if res == -errno.ECANCELED && c.wantOut then {
          c.discardSubmission()
          Failure(new gears.async.GearsCancellationException())
        } else
          try
            if c.finishSubmission(res) then {
              done = true
              WorkpoolScheduler.SuccessUnit
            } else if handle.isClosing then {
              c.discardSubmission()
              Failure(handle.closedFailure)
            } else if c.wantOut then {
              c.discardSubmission()
              Failure(new gears.async.GearsCancellationException())
            } else
              try {
                if c.readyToSubmit() then submit(c)
                null
              } catch {
                case failure: Throwable =>
                  c.discardSubmission()
                  Failure(failure)
              }
          catch case failure: Throwable => Failure(failure)
      if outcome != null then
        try settle(c, outcome, done)
        catch case e: Throwable => e.printStackTrace()
    }
  }

  private def settle(c: Command, outcome: Try[Unit], done: Boolean): Unit = {
    endUse(c)
    c.deliver(outcome, done)
  }

  private def endUse(c: Command): Unit = {
    c.release()
    c.handle.releaseWork()
    passTurn(c)
  }

  private def passTurn(c: Command): Unit = {
    val turns = c.turns
    if turns != null then {
      val next = turns.pass(c)
      if next != null then next.owner.nn.startTurn(next)
    }
  }

  /** Starts `c`, whose turn it is now, which was parked, or which a close ended the wait of, on this worker. */
  // - never at once: it may be asked as the ring submits, or as it is torn down
  private[uring] def startTurn(c: Command): Unit =
    runLater { () =>
      c.resume()
      val handle = c.handle
      if c.wantOut || c.settled || handle.isClosing then
        settle(
          c,
          Failure(if handle.isClosing then handle.closedFailure else new gears.async.GearsCancellationException()),
          done = false
        )
      else
        try {
          if c.doneAtOnce() then settle(c, WorkpoolScheduler.SuccessUnit, done = true)
          else if c.readyToSubmit() then {
            c.onRing(ring)
            submit(c)
          }
        } catch case failure: Throwable => settle(c, Failure(failure), done = false)
    }

  // ---- taking a command back, its performer cancelled or out of time

  private[uring] def takeBack(c: Command, performance: Long): Unit =
    runOnRing(() => if c.performance == performance && (c.owner eq this) && !c.hasLetGo then withdraw(c))

  private def withdraw(c: Command): Unit = {
    val gone = c.state match {
      case _ if c.turns != null && c.turns.nn.leaveWaiting(c) =>
        c.release()
        c.handle.releaseWork()
        true
      case Command.Stage.Parked =>
        if c.leaveParking() then {
          c.resume()
          c.discardSubmission()
          endUse(c)
          true
        } else false // started again already: that start sees `wantOut`
      case Command.Stage.Prepared =>
        ring.nop(c.sqe)
        withdrawn(c)
        true
      case Command.Stage.Submitted if ring.pending(c.sqe, c.id) =>
        ring.nop(c.sqe)
        withdrawn(c)
        true
      case Command.Stage.Backlogged =>
        backlog -= c
        c.outOfRing()
        c.discardSubmission()
        endUse(c)
        true
      case Command.Stage.Submitted =>
        // - without waiting: one a kernel worker runs lets go with its completion, which `dispatch` takes
        ring.cancelNow(c.id, wait = false) == 0 && {
          withdrawn(c)
          true
        }
      case _ => false
    }
    if gone then c.markLetGo()
  }

  private def withdrawn(c: Command): Unit = {
    inFlight.remove(c.id)
    c.outOfRing()
    c.discardSubmission()
    endUse(c)
  }

  // ---- waiting, waking, failing and closing

  protected def wake(): Unit =
    if !isCurrent then
      if hasGivenUp then wakeWaiting()
      else ring.wake()

  private val hasWork: () => Boolean = () => !isEmpty() || !ringTasks.isEmpty() || backlog.nonEmpty

  private var failedWaits = 0

  protected def waitForDeadline(deadlineTask: Long): Unit = {
    runRingTasks()
    if hasGivenUp then {
      val timeout =
        if hasWork() then 0L
        else if deadlineTask < 0 then -1L
        else math.max(deadlineTask - System.nanoTime(), 0L)
      waitWithoutQueue(timeout)
    } else
      try {
        ring.await(deadlineTask, hasWork, spin = inFlight.nonEmpty)(dispatch)
        failedWaits = 0
      } catch {
        case failure: IOException =>
          // - a few tries, a little apart, as `EventPoller` makes, in case the failure passes
          failedWaits += 1
          if failedWaits < UringWorker.WaitAttempts then Thread.sleep(failedWaits.toLong) else fail(failure)
      }
    submitBacklog()
    runRingTasks()
  }

  private def submitBacklog(): Unit =
    if backlog.nonEmpty && !hasGivenUp then {
      val waiting = backlog.toArray
      backlog.clear()
      for c <- waiting do {
        c.outOfRing()
        if c.handle.isClosing then settle(c, Failure(c.handle.closedFailure), done = false)
        else
          try submit(c)
          catch case failure: Throwable => settle(c, Failure(failure), done = false)
      }
    }

  /** The ring failed: what the kernel holds is cancelled, waiting, before the ring is torn down, and its ops fail. */
  private[uring] def fail(cause: IOException): Unit = {
    letGoOfAll(Failure(new IOException("The io_uring worker's ring failed", cause)))
    giveUp(cause)
  }

  // - tearing a ring down does not wait for a kernel worker still running an entry: each is cancelled, waiting, first;
  //   one the kernel does not say it has let go of keeps what its command staged
  private def letGoOfAll(outcome: Try[Unit]): Unit = {
    val held = inFlight.values.toList
    val letGo = held.map(c =>
      c.state != Command.Stage.Submitted || ring.pending(c.sqe, c.id) || {
        val res = ring.cancelNow(c.id, wait = true)
        res == 0 || res == -errno.ENOENT
      }
    )
    ring.close()
    inFlight.clear()
    prepared.clear()
    submitted.clear()
    val waiting = backlog.toList
    backlog.clear()
    for c <- waiting do {
      c.outOfRing()
      c.discardSubmission()
      settle(c, outcome, done = false)
    }
    for (c, free) <- held.zip(letGo) do {
      c.outOfRing()
      if free then c.discardSubmission() else UringScheduler.kept.add(c)
      try settle(c, outcome, done = false)
      catch case e: Throwable => e.printStackTrace()
    }
  }

  override def close(): Unit = {
    runRingTasks()
    if !hasGivenUp then letGoOfAll(Failure(new gears.async.GearsCancellationException()))
    else ring.close()
  }
}

private[uring] object UringWorker {
  inline val WaitAttempts = 3
}
