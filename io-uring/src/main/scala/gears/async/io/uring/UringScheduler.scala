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

  // ---- multishot receives, of the connections whose reads this worker performs

  private var buffers: BufferRing | Null = null
  private var receivesOff = !Receive.enabled
  private var nextReceive = Ring.ReceiveBase
  private val armed = scala.collection.mutable.LongMap.empty[Receiver]
  private val receivers = scala.collection.mutable.HashSet.empty[Receiver]
  private[uring] val readsOnReceives = new java.util.concurrent.atomic.AtomicInteger()

  private[uring] def receiverCount: Int = receivers.size

  private[uring] var stopsToRefuse = 0

  private[uring] def freeBuffers: Int = {
    val b = buffers
    if b == null then Receive.Entries else b.free
  }

  private[uring] def receiverFor(h: UringHandle): Receiver | Null =
    if receivesOff || hasGivenUp || ring.isClosed then null
    else {
      if buffers == null then
        try buffers = new BufferRing(ring, Receive.Entries)
        catch case _: (IOException | OutOfMemoryError) => receivesOff = true
      if buffers == null then null
      else {
        val r = new Receiver(this, h, buffers.nn)
        h.receiver = r
        receivers += r
        r
      }
    }

  // - an armed receive is work claimed on its handle, as a command in the kernel is: the descriptor closes once it ends
  private[uring] def arm(r: Receiver): Boolean =
    r.armed || r.idle && !r.handle.isClosing && !ring.isClosed && buffers.nn.free > 0 && claimed(r.handle) && {
      val id = nextReceive
      val sqe =
        try ring.armReceive(r.handle.fd, Receive.Group, id)
        catch {
          case failure: Throwable =>
            r.handle.releaseWork()
            throw failure
        }
      if sqe == null then {
        r.handle.releaseWork()
        false
      } else {
        nextReceive += 1
        r.armedAs(id, sqe.nn)
        armed(id) = r
        true
      }
    }

  private def claimed(h: UringHandle): Boolean =
    try {
      h.claimWork()
      true
    } catch case _: IOException => false

  private def disarmed(r: Receiver): Unit = {
    armed -= r.id
    r.disarmed()
    r.handle.releaseWork()
  }

  // - one the ring has not submitted yet becomes a no-op: a cancel would not find it, and it would stay armed
  // - a cancel that finds the receive running is refused: it is asked again at the next of its completions
  private[uring] def stopReceive(r: Receiver): Unit =
    if r.armed && !r.stopping && !ring.isClosed then
      if ring.pending(r.sqe, r.id) then {
        ring.nop(r.sqe)
        disarmed(r)
      } else if stopsToRefuse > 0 then stopsToRefuse -= 1
      else {
        val res = ring.cancelNow(r.id, wait = false)
        if res == 0 || res == -errno.ENOENT then r.stopAsked()
      }

  private[uring] def collectNow(): Unit = ring.collectNow(dispatch): Unit

  private[uring] def endReceive(r: Receiver): Unit =
    runOnRing { () =>
      stopReceive(r)
      r.drop()
      if !r.armed then receivers -= r
    }

  ring.onReceive = new Ring.OnReceive {
    def receive(id: Long, res: Int, flags: Int): Unit = received(id, res, flags)
  }

  private def received(id: Long, res: Int, flags: Int): Unit = {
    val r = armed.getOrNull(id)
    if r != null then {
      val h = r.handle
      if (flags & IORING_CQE_F_BUFFER) != 0 then {
        val b = buffers.nn
        b.taken()
        val bid = flags >>> IORING_CQE_BUFFER_SHIFT
        if h.isClosing || res <= 0 then b.recycle(bid) else r.add(bid, res)
      }
      if (flags & IORING_CQE_F_MORE) == 0 then disarmed(r)
      r.completed(res)
      if h.isClosing then {
        stopReceive(r)
        r.drop()
        if !r.armed then receivers -= r
      } else {
        // - a read waiting on it that does not arm receives waits for its stop
        val stops = h.parked match {
          case read: Receiving if read.waitsOnReceiver => !read.arms
          case _                                       => r.queued >= Receive.MostQueued
        }
        if stops then stopReceive(r)
      }
      val waiting = h.parked
      if waiting != null && (r.hasQueued || !r.armed || h.isClosing) && waiting.leaveParking() then startTurn(waiting)
    }
  }

  private def loseReceives(outcome: Try[Unit]): Unit = {
    val lost = new IOException("The io_uring worker's ring is gone")
    var letGo = true
    // - a snapshot: a disarm may close its handle, whose last act ends its receive here
    val all = receivers.toArray
    for r <- all do {
      if r.armed then {
        val res = ring.cancelNow(r.id, wait = true)
        if res != 0 && res != -errno.ENOENT then letGo = false
        try disarmed(r)
        catch case e: Throwable => e.printStackTrace()
      }
      r.lose(lost)
    }
    receivers.clear()
    armed.clear()
    val b = buffers
    buffers = null
    receivesOff = true
    // - a receive the kernel has not let go of may still write into them: they are kept, as `UringScheduler.kept` keeps
    //   the commands' memory
    if b != null && letGo then b.close()
    for r <- all do {
      val waiting = r.handle.parked
      if waiting != null && (waiting.owner eq this) && waiting.leaveParking() then
        try settle(waiting, outcome, done = false)
        catch case e: Throwable => e.printStackTrace()
    }
  }

  private val inFlight = scala.collection.mutable.LongMap.empty[Command]
  private val prepared = scala.collection.mutable.ArrayBuffer.empty[Command]
  private val submitted = scala.collection.mutable.ArrayBuffer.empty[Command]
  // - commands that found the submission queue full, submitted again once a turn's submission has made room
  private val backlog = scala.collection.mutable.ArrayBuffer.empty[Command]
  private var nextId = Ring.InternalId + 1

  ring.onSubmit = () =>
    if prepared.nonEmpty then {
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

  ring.afterSubmit = () =>
    if submitted.nonEmpty then {
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
      // - a connection's reads are performed on the worker its receive is on: that worker alone touches what it got
      val performer = c.performerOf(this)
      c.owner = performer
      val turns = c.turns
      if performer ne this then {
        c.exposeNow()
        if c.hasDeadline then c.holdTimerOn(this)
        performer.startTurn(c)
        c.awaitOutcome(performer)
      } else if turns != null && !turns.enter(c) then {
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

  private val hasWork: Ring.Check = new Ring.Check {
    def check(): Boolean = !isEmpty() || !ringTasks.isEmpty() || backlog.nonEmpty
  }

  private var failedWaits = 0

  protected def waitForDeadline(deadlineTask: Long): Unit = {
    runRingTasks()
    if hasGivenUp then {
      val timeout =
        if hasWork.check() then 0L
        else if deadlineTask < 0 then -1L
        else math.max(deadlineTask - System.nanoTime(), 0L)
      waitWithoutQueue(timeout)
    } else
      try {
        ring.await(deadlineTask, hasWork, spin = inFlight.nonEmpty || readsOnReceives.get() > 0)(dispatch)
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
    loseReceives(outcome)
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
