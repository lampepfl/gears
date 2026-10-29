package gears.async.io
package kqueue

import gears.async.io.internal.Completion
import gears.async.io.internal.DescriptorTable
import gears.async.io.internal.Interest
import gears.async.io.internal.Reactor
import gears.async.io.internal.ReactorCore
import gears.async.io.kqueue.KqueueLoop
import gears.async.io.kqueue.KqueueLoop.Event

import java.io.IOException
import scala.scalanative.posix.string
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** The kqueue implementation of `Reactor`, for ops built by `KqueueOps`. A command is performed as soon as it is
  * submitted when its descriptor is known to be ready, or when readiness is not known yet and the command allows it;
  * otherwise, or when it would block, it waits for readiness and is performed on the loop thread. The reactor's own
  * reads (`Command.edgeTriggered`) wait on a persistent edge-triggered filter, added once per descriptor, so waiting
  * again costs no syscall; other reads, and writes, which rarely have to wait, use a one-shot filter. Handles must be
  * closed through the backend, by the `close` op or `KqueueHandles.close`, from any thread, which tells every reactor
  * (`closed`): the commands pending on the descriptor fail, and its slot is forgotten before the number is reused;
  * timers, and the ops with a time limit, are kept in a heap on the loop thread, by deadline, and bound how long a poll
  * blocks, so setting or cancelling one costs no syscall, and an op that times out is withdrawn as a cancelled one is,
  * with `onFailure` instead of `onCancel`; `wakeup`, and blocking tasks finishing on other threads, wake the loop with
  * a user event. Each submission is its own kevent call, and each poll returns up to `maxEvents` events.
  */
final class KqueueReactor private (
    val kq: Int,
    maxEvents: Int,
    spinPolls: Int
) extends gears.async.io.internal.ReactorCore[KqueueOp] {

  type Handle = KqueueBackend.Handle

  def ops: Ops[KqueueOp, Handle] = KqueueOps

  private val WakeEvent = 0.toUSize
  KqueueLoop.createAndRegisterEvents(kq, 1) { events =>
    KqueueLoop.addUser(events(0), WakeEvent) // edge-triggered, registered once
  }

  private final class Slot {
    var read: KqueueOp | Null = null
    var write: KqueueOp | Null = null

    /** Whether the descriptor's persistent read filter is registered. */
    var readRegistered = false

    /** What is known of the descriptor's read readiness: `Unknown`, `Ready` or `NotReady` (see `KqueueReactor`). */
    var readReadiness = KqueueReactor.Unknown

    /** Whether kqueue reported the end of the stream. It lasts, so every later read returns at once, but an
      * edge-triggered filter reports it only once; after it, the descriptor stays `Ready`.
      */
    var readEof = false

    /** What kqueue last reported waiting to be read, bytes or, on a listening socket, connections, less what reads have
      * taken since; -1 when not known.
      */
    var readAvailable = -1L

    /** The descriptor's generation in `DescriptorTable` when this slot last checked; a different one means its number
      * was closed and reused since.
      */
    var generation = 0

    /** Whether reads must always be tried (`DescriptorTable.alwaysTry`): a short read leaves readiness unknown, not
      * `NotReady`, since the read filter of a regular file or a FIFO may never report the end that follows it.
      */
    var alwaysTry = false

    def settleReadiness(readiness: Int): Unit = readReadiness = if readEof then KqueueReactor.Ready else readiness

    /** Forgets what is known of reading the descriptor: it was closed, or another reactor reads it now. */
    def forget(): Unit = {
      readRegistered = false
      readReadiness = KqueueReactor.Unknown
      readEof = false
      readAvailable = -1
    }
  }

  /** For measurement: descriptors this reactor took over from another, and read filters it dropped because another had
    * taken the descriptor over. On the reactor's thread only; read them once it has stopped.
    */
  var takenOver = 0L
  var filtersDropped = 0L

  /** The counters, for a runner printing its reactors' statistics. */
  override def toString: String = s"kqueue(taken over $takenOver, filters dropped $filtersDropped)"

  /** Distinguishes this reactor from the others sharing descriptors with it, in `DescriptorTable`. */
  private val id = KqueueReactor.nextId.incrementAndGet()

  // Descriptors close on any thread (`KqueueHandles.closeDescriptor`): each close queues here, as the number with the
  // generation that ended, and wakes a blocked poll, so that the commands pending on it fail (see `closed`).
  private val closedQueue = new java.util.concurrent.ConcurrentLinkedQueue[java.lang.Long]()

  // Descriptors `FifoWatcher` found readable, packed as `closedQueue`'s are, for the loop (`deliverSelected`).
  private val selectedQueue = new java.util.concurrent.ConcurrentLinkedQueue[java.lang.Long]()

  private var slots = new Array[Slot | Null](64)

  /** Submits a command, a connect, or a close (see `ReactorCore.submitOwn`). */
  protected def submitOwn(op: KqueueOp, pending: Completion[KqueueOp], finishNow: Boolean): Int =
    op match {
      case connect: ConnectTo if !started(connect, pending, finishNow) =>
        if finishNow then ReactorCore.Finished else ReactorCore.Queued
      case command: Command => submitCommand(command, pending, finishNow)
      case close: Close     =>
        // Closed here, as an at-once op is, unless a use of the descriptor is in progress: then pending, as a promise,
        // until the last use ends and closes it.
        close.reset()
        close.reactor = this
        close.completion = pending
        if close.start() then {
          closing.add(close) // completed after the failures it causes, never in trySubmit
          ReactorCore.Queued
        } else {
          pendingPromises += close
          ReactorCore.Waits
        }
      case _ => ReactorCore.NotOwn // a timer, a blocker or an at-once op
    }

  /** Submits a command: tried at once if its descriptor may be ready, else, or if that would block, registered to wait
    * for readiness (see `ReactorCore.submitOwn`). Its refusal was checked by `ReactorCore` as it was submitted.
    */
  private def submitCommand(command: Command, pending: Completion[KqueueOp], finishNow: Boolean): Int = {
    // The command's first try and its registration are a use of its handle's descriptor (see `Command.handle`).
    val handle = command.handle
    if handle != null && !handle.acquire() then refuseNow(command, pending, KqueueHandles.notOpen(handle), finishNow)
    else
      try {
        val read = command.interest == Interest.Read
        val slot = currentSlot(command.fd, read)
        // Checked before trying, so that a new command cannot take data or buffer space ahead of a waiting one.
        if read then require(slot.read == null, s"A read command is already pending on descriptor ${command.fd}")
        else require(slot.write == null, s"A write command is already pending on descriptor ${command.fd}")
        command.completion = pending
        if read then {
          val attempt =
            slot.readReadiness == KqueueReactor.Ready ||
              (slot.readReadiness == KqueueReactor.Unknown && command.tryFirst)
          if attempt && performedAtOnce(command, slot, queue = !finishNow) then
            if finishNow then ReactorCore.Finished else ReactorCore.Queued
          else {
            slot.read = command
            val failed = registrationFailure(command, slot, read = true)(waitToRead(command, slot))
            if failed == null then ReactorCore.Waits else refuseNow(command, pending, failed, finishNow)
          }
        } else if command.tryFirst && performedAtOnce(command, null, queue = !finishNow) then
          if finishNow then ReactorCore.Finished else ReactorCore.Queued
        else {
          slot.write = command
          val failed = registrationFailure(command, slot, read = false)(
            if command.registersReads && !slot.readRegistered then armWithReads(command.fd, slot)
            else arm(command.fd, read = false)
          )
          if failed == null then ReactorCore.Waits else refuseNow(command, pending, failed, finishNow)
        }
      } finally if handle != null then handle.release()
  }

  /** Performs `command` before waiting for readiness. If it finishes, or fails, its completion is queued for the next
    * turn if `queue`, rather than run here, and this returns true; if it would block, this returns false. For a read,
    * `slot` records what that taught about the descriptor's readiness.
    */
  private def performedAtOnce(command: Command, slot: Slot | Null, queue: Boolean): Boolean = {
    val done = attemptNow(command, queue)(command.perform())
    if slot != null then settleAfterRead(command, slot, done, command.atOnceFailure != null, slot.readAvailable)
    done
  }

  /** Runs `register`, which registers the filter `command` waits on, from its `slot`, and returns its failure, or null.
    * A registration that fails, such as with `EBADF` for a descriptor closed behind the reactor's back, takes the
    * command out of its slot, so that the command fails alone, through its completion, and the loop goes on.
    */
  private inline def registrationFailure(command: Command, slot: Slot, read: Boolean)(
      inline register: Unit
  ): Throwable | Null =
    try {
      register
      null
    } catch {
      case t: Throwable =>
        if read then {
          slot.read = null
          slot.readRegistered = false
        } else slot.write = null
        t
    }

  /** Records what a read command's perform taught about its descriptor's read readiness. After one that finished, that
    * comes from what it took against what was `available`, when both are known, or else from the command's own guess.
    * Knowing a descriptor drained saves the next read a try that would block: once the count runs out it waits for an
    * edge, which anything arriving later triggers.
    */
  private def settleAfterRead(command: Command, slot: Slot, done: Boolean, failed: Boolean, available: Long): Unit =
    if failed || !command.edgeTriggered then {
      slot.readAvailable = -1
      slot.settleReadiness(KqueueReactor.Unknown)
    } else if !done then {
      slot.readAvailable = -1
      slot.settleReadiness(KqueueReactor.NotReady)
    } else {
      val taken = command.transferred
      val left = if available >= 0 && taken >= 0 then math.max(available - taken, 0L) else -1L
      slot.readAvailable = if left > 0 then left else -1
      slot.settleReadiness(
        if left > 0 || (left < 0 && command.mayStillBeReady) then KqueueReactor.Ready
        else if slot.alwaysTry then KqueueReactor.Unknown
        else KqueueReactor.NotReady
      )
    }

  /** Makes a pending read wait for readiness. An edge-triggered command uses the descriptor's persistent filter,
    * registered once; if data is already waiting, kqueue reports it on the next poll. Any other command arms a one-shot
    * filter, which replaces a persistent one, and is gone once it fires.
    */
  private def waitToRead(command: Command, slot: Slot): Unit = {
    if slot.alwaysTry then watchSelected(command.fd, slot.generation)
    if !command.edgeTriggered then {
      slot.readRegistered = false
      DescriptorTable.claimRead(command.fd, id) // so that another reactor's persistent filter is dropped
      arm(command.fd, read = true)
    } else if !slot.readRegistered then {
      DescriptorTable.claimRead(command.fd, id)
      KqueueLoop.createAndRegisterEvents(kq, 1) { events =>
        KqueueLoop.addFile(events(0), command.fd, read = true, clear = true)
      }
      slot.readRegistered = true
    }
  }

  /** Has `FifoWatcher` watch `fd`, an always-tried descriptor (a FIFO or a regular file) with a read waiting, whose
    * filter may never fire for its end: the watcher tells this reactor once `select` reports it readable.
    */
  private def watchSelected(fd: Int, generation: Int): Unit = {
    val packed = (generation.toLong << 32) | (fd.toLong & 0xffffffffL)
    gears.async.io.internal.FifoWatcher.watch(fd, this, () => selected(packed))
  }

  /** From `FifoWatcher`'s thread: the descriptor packed in `packed` is readable. */
  private def selected(packed: Long): Unit =
    if !closed then {
      selectedQueue.add(packed)
      wakeup()
    }

  /** Attempts the reads waiting on the descriptors `FifoWatcher` found readable since the last turn, as a read filter
    * firing would, where the slot still knows that descriptor and a read waits. Returns whether there were any.
    */
  private def deliverSelected(): Boolean = {
    var any = false
    var packed = selectedQueue.poll()
    while packed != null do {
      val fd = (packed.longValue & 0xffffffffL).toInt
      val generation = (packed.longValue >>> 32).toInt
      if fd >= 0 && fd < slots.length then {
        val slot = slots(fd)
        if slot != null && slot.nn.generation == generation && slot.nn.read != null then {
          any = true
          dispatchFile(fd, write = false, eof = false, data = -1L)
        }
      }
      packed = selectedQueue.poll()
    }
    any
  }

  /** Called from any thread when descriptor `fd`, whose generation in `DescriptorTable` was `generation`, is closed:
    * the commands pending on it here fail, from the loop, as `Ops.close` says, and its slot forgets it. A slot that
    * knows a later generation knows a reused number, and is left alone.
    */
  private[kqueue] def closed(fd: Int, generation: Int): Unit =
    if !closed then {
      closedQueue.add((generation.toLong << 32) | (fd.toLong & 0xffffffffL))
      if !(Thread.currentThread() eq loopThread) then wakeup()
    }

  private def failClosedCommand(command: KqueueOp, fd: Int, now: Boolean): Unit = {
    val failure = gears.async.io.internal.HandleClosedException.pending(s"Descriptor $fd")
    if now then fail(command, failure)
    else {
      command.atOnceFailure = failure
      ready.add(command)
    }
  }

  /** Fails the commands pending on the descriptors closed since the last turn, where the slot still knows that
    * descriptor and not a reused number. Returns whether any were.
    */
  protected def deliverClosed(): Boolean = {
    var any = false
    var packed = closedQueue.poll()
    while packed != null do {
      val fd = (packed.longValue & 0xffffffffL).toInt
      val generation = (packed.longValue >>> 32).toInt
      if fd >= 0 && fd < slots.length then {
        val slot = slots(fd)
        if slot != null && slot.nn.generation == generation then any |= failClosed(slot.nn, fd, now = true)
      }
      packed = closedQueue.poll()
    }
    any
  }

  /** Fails the commands pending in `slot`, whose descriptor was closed, and forgets the descriptor: `now`, from the
    * loop's turn (`deliverClosed`); else queued as commands that failed when submitted, so that their completions run
    * from the next turn and never inside a submit. Returns whether there were any.
    */
  private def failClosed(slot: Slot, fd: Int, now: Boolean): Boolean = {
    val read = slot.read
    val write = slot.write
    slot.read = null
    slot.write = null
    slot.forget()
    if read != null then failClosedCommand(read, fd, now)
    if write != null then failClosedCommand(write, fd, now)
    read != null || write != null
  }

  /** Signals while the loop is awake too (see `ReactorCore.signalsWhileAwake`). */
  override protected def signalsWhileAwake: Boolean = true

  /** Triggers the wake event (see `ReactorCore.wakeup`). */
  protected def wakeNow(): Unit =
    KqueueLoop.createAndRegisterEvents(kq, 1)(events => KqueueLoop.triggerUser(events(0), WakeEvent))

  /** Drops the commands in the slots (see `ReactorCore.dropOwn`). */
  protected def dropOwn(drop: KqueueOp => Unit): Unit = {
    for slot <- slots if slot != null do {
      if slot.nn.read != null then drop(slot.nn.read.nn)
      if slot.nn.write != null then drop(slot.nn.write.nn)
    }
    java.util.Arrays.fill(slots.asInstanceOf[Array[AnyRef]], null)
  }

  /** Closes the kqueue: its filters and wake event go with it (see `ReactorCore.closeOwn`). */
  protected def closeOwn(): Unit = {
    closedQueue.clear()
    selectedQueue.clear()
    wakeLock.synchronized {
      closed = true
      KqueueLoop.close(kq) // Its filters and wake event go with it.
      eventsZone.close()
      KqueueReactor.all.remove(this)
    }
  }

  // Polled events, only meaningful while the reactor dispatches them.

  private def isWriteEvent(event: Event): Boolean = KqueueLoop.isWriteEvent(event)
  private def fileIdent(event: Event): Int = KqueueLoop.fileIdent(event)

  /** Takes a pending command out of its slot, deleting its one-shot filter (see `ReactorCore.withdrawOwn`). */
  protected def withdrawOwn(op: KqueueOp): Completion[KqueueOp] | Null =
    op match {
      case command: Command =>
        val slot = if command.fd >= 0 && command.fd < slots.length then slots(command.fd) else null
        if slot == null then null
        else {
          val read = command.interest == Interest.Read
          val p = if read then slot.read else slot.write
          if p == null || !(p eq command) then null
          else {
            // A persistent read filter stays; a one-shot filter is deleted so that it cannot fire for nothing.
            if read then slot.read = null else slot.write = null
            if !(read && command.edgeTriggered) then {
              if read then slot.readRegistered = false
              deleteFilter(command.fd, read)
            }
            take(command)
          }
        }
      case _ => gears.async.io.internal.ReactorCore.NotOwnCompletion
    }

  /** Deletes a pending command's one-shot filter so it cannot wake the loop for nothing. If the caller already closed
    * the descriptor, kqueue removed the filter with it and reports `ENOENT` or `EBADF`; the op is cancelled all the
    * same, as it is if the deletion fails otherwise: the filter left over fires once, finding no command, and
    * `dispatch` drops its event.
    */
  private def deleteFilter(fd: Int, read: Boolean): Unit = KqueueLoop.deleteFileIfOpen(kq, fd, read): Unit

  /** `fd`'s slot, brought up to date for a submission, a read if `reading`. What it knew no longer holds if the
    * descriptor's number was closed and reused since, on any thread: it then starts over, as for a new descriptor. Nor
    * does what it knew of reading hold if another reactor reads the descriptor now, having registered a read filter
    * since this one did; a read then starts over not ready, so that it registers a filter here before trying anything,
    * since kqueue reports data already waiting as soon as a filter is added, and trying a read first would only find
    * out what the filter will. Writes leave what it knew of reading alone. A filter of this reactor's that is still
    * registered is harmless: a new registration replaces it, and an event it reports first is dropped by `dispatch`.
    */
  private def currentSlot(fd: Int, reading: Boolean): Slot = {
    val slot = slotFor(fd)
    val entry = DescriptorTable.get(fd)
    val generation = DescriptorTable.generation(entry)
    if generation != slot.generation then {
      // Its number was closed, and reused, since: whatever is still pending belonged to the closed descriptor.
      failClosed(slot, fd, now = false)
      slot.generation = generation
    }
    slot.alwaysTry = DescriptorTable.alwaysTry(entry)
    val owner = DescriptorTable.readOwner(entry)
    if reading && owner != 0 && owner != id then {
      slot.forget()
      slot.settleReadiness(KqueueReactor.NotReady)
      takenOver += 1
    }
    slot
  }

  private def slotFor(fd: Int): Slot = {
    require(fd >= 0, "Descriptor must be non-negative")
    if fd >= slots.length then slots = java.util.Arrays.copyOf(slots, math.max(slots.length * 2, fd + 1))
    var slot = slots(fd)
    if slot == null then {
      slot = new Slot
      slots(fd) = slot
    }
    slot.nn
  }

  /** One kevent call per command: the filter is one-shot, so nothing fires without a command waiting for it. */
  private def arm(fd: Int, read: Boolean): Unit =
    KqueueLoop.createAndRegisterEvents(kq, 1) { events =>
      KqueueLoop.addFile(events(0), fd, read = read, clear = false, oneShot = true)
    }

  /** Arms a one-shot write filter, for a command such as a connect, and adds the descriptor's persistent read filter in
    * the same call. Nothing can then arrive unreported, so the descriptor starts out not ready to read, and its first
    * read waits for an edge instead of trying a read that would block; data already waiting is reported as soon as the
    * filter is added.
    */
  private def armWithReads(fd: Int, slot: Slot): Unit = {
    DescriptorTable.claimRead(fd, id)
    KqueueLoop.createAndRegisterEvents(kq, 2) { events =>
      KqueueLoop.addFile(events(0), fd, read = false, clear = false, oneShot = true)
      KqueueLoop.addFile(events(1), fd, read = true, clear = true)
    }
    slot.readRegistered = true
    slot.readAvailable = -1
    slot.settleReadiness(KqueueReactor.NotReady)
  }

  /** Finds the command an event is for, performs it, and hands its completion the result. The slot is cleared first so
    * the completion may submit the next command.
    */
  private def dispatch(event: Event): Unit =
    if KqueueLoop.isUserEvent(event) then {
      // Resolved promises are delivered by `runOnce` itself; a wake-up was taken as answered once the poll returned
      // (`tookWakeUps`).
    } else dispatchFile(fileIdent(event), isWriteEvent(event), KqueueLoop.isEOF(event), KqueueLoop.data(event).toLong)

  /** What `dispatch` does for a descriptor's filter firing, a write's if `write`, at the end of the stream if `eof`,
    * with `data` bytes available, -1 if not known; and for `FifoWatcher` reporting a descriptor readable
    * (`deliverSelected`).
    */
  private def dispatchFile(ident: Int, write: Boolean, eof: Boolean, data: Long): Unit = {
    val slot = if ident < slots.length then slots(ident) else null
    if slot != null then {
      val pending = if write then slot.write else slot.read
      if pending == null then {
        if !write then {
          val entry = DescriptorTable.get(ident)
          if DescriptorTable.readOwner(entry) != id || DescriptorTable.generation(entry) != slot.generation then {
            // Another reactor reads the descriptor now, or its number was reused: this filter is left over. Drop it,
            // so this reactor no longer wakes for it; it is registered again if a read here needs it.
            KqueueLoop.deleteFileIfOpen(kq, ident, read = true): Unit // if it stays, it is dropped again next time
            slot.forget()
            filtersDropped += 1
          } else { // kept for the next read submitted
            if eof then slot.readEof = true
            slot.readReadiness = KqueueReactor.Ready
            slot.readAvailable = data
          }
        }
      } else {
        if !write && eof then slot.readEof = true
        if write then slot.write = null else slot.read = null
        pending.nn match {
          case command: Command =>
            // A throwing command fails its own op; an exception thrown by the completion itself is reported as a
            // `CompletionFailed` (see `Reactor`), not as the op's failure. The system call, and a registration again
            // if it was not ready, are a use of the command's handle (see `Command.handle`), ended before the
            // completion runs, which may close the handle.
            val handle = command.handle
            var failure: Throwable | Null = null
            var done = true
            // Readiness is settled before the completion runs, since it may submit the next read at once.
            if handle != null && !handle.acquire() then {
              failure = KqueueHandles.notOpen(handle)
              if !write then settleAfterRead(command, slot, done = true, failed = true, -1L)
            } else
              try {
                done =
                  try command.perform()
                  catch {
                    case t: Throwable =>
                      failure = t
                      true
                  }
                if !write then settleAfterRead(command, slot, done, failure != null, data)
                if !done then {
                  // Not ready after all: wait again, without the completion ever seeing it, unless waiting fails.
                  val failed =
                    if write then {
                      slot.write = command
                      registrationFailure(command, slot, read = false)(arm(ident, read = false))
                    } else {
                      slot.read = command
                      registrationFailure(command, slot, read = true)(waitToRead(command, slot))
                    }
                  if failed != null then failure = failed
                }
              } finally if handle != null then handle.release()
            if failure != null then fail(command, failure.nn)
            else if done then complete(command)
          case _ => () // Only commands live in descriptor slots.
        }
      }
    }
  }

  /** An event with `EV_ERROR`, whose data is the error number: the command pending on the descriptor's filter fails,
    * and no other; the slot forgets what it knew of reading, so a later read registers its filter again. On the wake
    * event, which the reactor registers itself, there is nothing to fail; a wake-up is still taken as one.
    */
  private def dispatchError(event: Event): Unit =
    if KqueueLoop.isUserEvent(event) then ()
    else if KqueueLoop.isReadEvent(event) || isWriteEvent(event) then {
      val ident = fileIdent(event)
      val slot = if ident >= 0 && ident < slots.length then slots(ident) else null
      if slot != null then {
        val write = isWriteEvent(event)
        val pending = if write then slot.write else slot.read
        if write then slot.write = null
        else {
          slot.read = null
          slot.forget()
        }
        if pending != null then {
          val code = KqueueLoop.errno(event)
          fail(
            pending,
            new IOException(s"kqueue reported an error on descriptor $ident: ${fromCString(string.strerror(code))}")
          )
        }
      }
    }

  // The buffer polls fill, allocated once in a zone that `close` frees, rather than on the stack every turn.
  private val eventsZone = Zone.open()
  private val events = KqueueLoop.allocateQueue(maxEvents)(using eventsZone)

  /** The FIFOs `FifoWatcher` found readable. */
  protected def deliverOwn(): Boolean = deliverSelected()

  /** Polls kqueue once and dispatches what it polled: an error event fails the command it is for (`dispatchError`); a
    * failing poll, of the kqueue itself, throws.
    */
  protected def poll(block: Boolean): Unit = {
    val polled = if block then pollBeforeBlocking(events) else KqueueLoop.pollEventsNow(kq, events, maxEvents)
    tookWakeUps()
    var i = 0
    while i < polled do {
      val event = events(i)
      if KqueueLoop.isError(event) then dispatchError(event) else dispatch(event)
      i += 1
    }
  }

  /** Polls without blocking up to `spinPolls` times, then blocks if nothing turned up. In request/response traffic the
    * awaited reply often arrives within microseconds, and catching it this way saves the thread a sleep and a wake-up.
    * A timer already due needs no spinning, since the turn has it to complete anyway; and the polls cost most on a
    * thread with little else to do, as an otherwise idle worker waiting on a timer is.
    */
  private def pollBeforeBlocking(events: KqueueLoop.EventQueue): Int = {
    if !timers.isEmpty && timers.peek.when <= System.nanoTime() then
      return KqueueLoop.pollEventsNow(kq, events, maxEvents)
    var polled = 0
    var spins = 0
    // Ended early by a wake-up asked for meanwhile, which signals nothing while the loop is awake (`wakeAsked`).
    while polled == 0 && spins < spinPolls && !wakeAsked do {
      polled = KqueueLoop.pollEventsNow(kq, events, maxEvents)
      spins += 1
    }
    if polled != 0 then polled
    else if !mayBlock() then KqueueLoop.pollEventsNow(kq, events, maxEvents) // a wake-up came meanwhile
    else
      try
        if timers.isEmpty then KqueueLoop.pollEventsForever(kq, events, maxEvents)
        else {
          // Block no longer than until the earliest timer is due, or not at all if it already is.
          val wait = timers.peek.when - System.nanoTime()
          if wait <= 0 then KqueueLoop.pollEventsNow(kq, events, maxEvents)
          else
            KqueueLoop.pollEventsTimeout(
              kq,
              events,
              maxEvents,
              (wait / 1_000_000_000L).toInt,
              (wait % 1_000_000_000L).toInt
            )
        }
      finally unpark()
  }

}

object KqueueReactor {

  /** The last id given to a reactor (see `DescriptorTable`); ids start at 1, since 0 means no owner. */
  private val nextId = new java.util.concurrent.atomic.AtomicInteger(0)

  /** The open reactors of the process, told of every descriptor closed (`KqueueHandles.closeDescriptor`). */
  private val all = java.util.concurrent.ConcurrentHashMap.newKeySet[KqueueReactor]()

  /** Tells every open reactor that `fd`, whose generation was `generation`, has been closed (see `closed`). */
  private[kqueue] def descriptorClosed(fd: Int, generation: Int): Unit = all.forEach(_.closed(fd, generation))

  // What a slot knows of its descriptor's read readiness. `Ready`: kqueue reported it readable, or the last read
  // probably left data, so a read is performed at once. `NotReady`: a read would block, so the next waits for an edge.
  // `Unknown`: a new or reused descriptor, which a read may try first.
  private inline val Unknown = 0
  private inline val Ready = 1
  private inline val NotReady = 2

  /** Opens a reactor on a new kqueue; each poll returns up to `maxEvents` events. Before blocking for events it polls
    * without blocking up to `spinPolls` times, trading a little CPU for not sleeping when an event is about to arrive;
    * 0 blocks at once. Close it when done.
    */
  def open(maxEvents: Int = 255, spinPolls: Int = DefaultSpinPolls): KqueueReactor = {
    require(spinPolls >= 0, "spinPolls must not be negative")
    val reactor = new KqueueReactor(KqueueLoop.open(), maxEvents, spinPolls)
    all.add(reactor)
    reactor
  }

  /** Non-blocking polls before blocking, by default: the benchmark's request/response scenarios gained most of what
    * spinning offers by 4.
    */
  inline val DefaultSpinPolls = 4

  /** Opens a reactor for `body` and closes it afterwards. */
  def scoped(maxEvents: Int = 255, spinPolls: Int = DefaultSpinPolls)(body: KqueueReactor => Unit): Unit = {
    val reactor = open(maxEvents, spinPolls)
    try body(reactor)
    finally reactor.close()
  }
}
