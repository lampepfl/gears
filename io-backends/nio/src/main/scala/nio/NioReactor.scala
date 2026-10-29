package gears.async.io
package nio

import gears.async.io.Ops
import gears.async.io.internal.Completion
import gears.async.io.internal.Interest
import gears.async.io.internal.Reactor

import java.nio.channels.CancelledKeyException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector

/** The NIO implementation of `Reactor`, for ops built by `NioOps`, over a `Selector`. A command is performed as soon as
  * it is submitted when it allows that; otherwise, or when it would block, it waits for readiness and is performed on
  * the loop thread. A selectable channel waits on its key in this reactor's selector, whose interest ops follow the
  * commands pending on it, brought up to date once per turn, before the select, so a read finishing and the next one
  * submitted from its completion change nothing. A regular file is always ready, so a command on it that is not done is
  * performed again next turn. Timers, and the ops with a time limit, are kept in a heap on the loop thread and bound
  * how long a select blocks; an op that times out is withdrawn as a cancelled one is, with `onFailure` instead of
  * `onCancel`. `wakeup`, and blocking tasks finishing, wake the selector.
  *
  * The selector's keys belong to the channels: closing a handle cancels its keys, and the commands pending on it here
  * fail with a `ClosedChannelException` from the loop (`closed`), as `Ops.close` says and as on the kqueue backend.
  */
final class NioReactor private (selector: Selector) extends gears.async.io.internal.ReactorCore[NioOp] {

  type Handle = NioCapabilities.Handle

  def ops: Ops[Op, Handle] = NioOps

  /** What waits on one channel's key in this selector: a command for each direction, and the interest ops each wants.
    */
  private final class Waiting {
    var read: Command | Null = null
    var write: Command | Null = null
    var readOps = 0
    var writeOps = 0
    var dirty = false // queued in `changed`
  }

  // Commands on regular files that were not done: a file is always ready, so they are performed again each turn.
  private val again = new java.util.ArrayDeque[Command]()

  // Keys whose interest ops must be brought up to date before the next select. Loop thread only.
  private val changed = new java.util.ArrayDeque[SelectionKey]()

  // The keys the last select found ready, collected so that commands run after it returns, not inside it.
  private var selected = new Array[SelectionKey](64)
  private var selectedCount = 0
  private val collect: java.util.function.Consumer[SelectionKey] = key => {
    if selectedCount == selected.length then selected = java.util.Arrays.copyOf(selected, selectedCount * 2)
    selected(selectedCount) = key
    selectedCount += 1
  }

  // Handles close on other threads; their entries queue here and wake the selector, so that the commands pending on
  // them fail (see `closed`). The waiting commands are found through the reactor's own map, not the selector's key:
  // the select the wake-up ends deregisters a closed channel's key before the loop gets here. Loop thread only.
  private val closedQueue = new java.util.concurrent.ConcurrentLinkedQueue[Selectable]()
  private val waitingByEntry = new java.util.IdentityHashMap[Selectable, Waiting]()

  /** Submits a command or a connect (see `ReactorCore.submitOwn`). */
  protected def submitOwn(op: NioOp, pending: Completion[NioOp], finishNow: Boolean): Int = {
    var finished = false
    var waits = false
    var own = true
    op match {
      case connect: ConnectTo if !started(connect, pending, finishNow) => finished = finishNow
      case command: Command                                            =>
        // A handle closed, or a channel this selector can no longer take, fails the op as a perform would.
        var failure: java.io.IOException | Null = null
        val entry =
          try NioHandles.entry(command.handle)
          catch {
            case e: java.io.IOException =>
              failure = e
              null
          }
        command.entry = entry
        val read = command.interest == Interest.Read
        val key = entry match {
          case s: Selectable =>
            try keyFor(command, s)
            catch {
              case e: java.io.IOException =>
                failure = e
                null
            }
          case _ => null
        }
        (entry, key) match {
          case _ if failure != null =>
            finished =
              refuseNow(command, pending, failure.nn, finishNow) == gears.async.io.internal.ReactorCore.Finished
          case (s: Selectable, key: SelectionKey) =>
            val waiting = key.attachment().asInstanceOf[Waiting]
            // Checked before trying, so that a new command cannot take data or buffer space ahead of a waiting one.
            if read then require(waiting.read == null, s"A read command is already pending on handle ${command.handle}")
            else require(waiting.write == null, s"A write command is already pending on handle ${command.handle}")
            command.completion = pending
            if attemptNow(command, queue = !finishNow)(command.perform()) then finished = finishNow
            else {
              val ops = command.selectOps(s.channel)
              if read then {
                waiting.read = command
                waiting.readOps = ops
              } else {
                waiting.write = command
                waiting.writeOps = ops
              }
              markChanged(key, waiting)
              waits = true
            }
          case (_: RegularFile, _) =>
            command.completion = pending
            if attemptNow(command, queue = !finishNow)(command.perform()) then finished = finishNow // always ready
            else {
              again.add(command)
              waits = true
            }
          case _ => throw new IllegalStateException(s"Unexpected handle entry $entry")
        }
      case _ => own = false // a timer, a blocker or an at-once op, a close among them (`LoopOp.closes`)
    }
    if !own then gears.async.io.internal.ReactorCore.NotOwn
    else if finished then gears.async.io.internal.ReactorCore.Finished
    else if waits then gears.async.io.internal.ReactorCore.Waits
    else gears.async.io.internal.ReactorCore.Queued
  }

  /** `command`'s key in this selector for `s`'s channel, registering the channel with no interest ops the first time.
    */
  private def keyFor(command: Command, s: Selectable): SelectionKey = {
    val cached = command.key
    if cached != null && (cached.selector() eq selector) && (cached.channel() eq s.channel) && cached.isValid then
      cached
    else {
      var key = s.channel.keyFor(selector)
      if key == null then {
        val waiting = new Waiting
        key = s.channel.register(selector, 0, waiting)
        waitingByEntry.put(s, waiting)
        s.registered(this)
      }
      command.key = key
      key
    }
  }

  private def markChanged(key: SelectionKey, waiting: Waiting): Unit =
    if !waiting.dirty then {
      waiting.dirty = true
      changed.add(key)
    }

  /** Brings the interest ops of the keys whose commands changed up to date, before a select. */
  private def updateInterest(): Unit =
    while !changed.isEmpty do {
      val key = changed.poll()
      val waiting = key.attachment().asInstanceOf[Waiting]
      waiting.dirty = false
      val wanted = (if waiting.read != null then waiting.readOps else 0) |
        (if waiting.write != null then waiting.writeOps else 0)
      if key.isValid then
        try if key.interestOps() != wanted then key.interestOps(wanted): Unit
        catch case _: CancelledKeyException => () // its channel was closed meanwhile
    }

  /** Performs a command whose handle is ready, and hands its completion the result; returns whether it is done. */
  private def performReady(command: Command): Boolean = {
    var failure: Throwable | Null = null
    val done =
      try command.perform()
      catch {
        case t: Throwable =>
          failure = t
          true
      }
    if failure != null then fail(command, failure)
    else if done then complete(command)
    done
  }

  /** Performs again the commands on regular files that were not done, as many as were waiting when it started. */
  private def deliverAgain(): Boolean = {
    var count = again.size
    val any = count > 0
    while count > 0 do {
      val command = again.poll()
      count -= 1
      if !performReady(command) then again.add(command)
    }
    any
  }

  /** Called from any thread when a handle this reactor registered is closed: the commands pending on it fail, from the
    * loop, with `ClosedChannelException`, as `Ops.close` says.
    */
  private[nio] def closed(entry: Selectable): Unit =
    if !closed then {
      closedQueue.add(entry)
      if !(Thread.currentThread() eq loopThread) then wakeup()
    }

  /** Drops the cancelled keys of channels closed since this reactor last selected, from the thread closing one: the JDK
    * releases a registered channel's descriptor only once each selector it is registered with has selected, which an
    * idle reactor, or one not being run, may not do for a long time, while the close must have released it by the time
    * it returns. The selector serialises selections, so this waits for one the loop is in: woken, and with
    * `dropsWanted` raised, so that it does not block in another before this has had its turn; the loop's next waits for
    * this one. Readiness is level-triggered, so nothing this selection sees is lost to the loop.
    */
  private[nio] def dropCancelledKeys(): Unit = {
    dropsWanted.incrementAndGet()
    try {
      selector.wakeup() // a selection after the loop checked `dropsWanted` returns at once
      selector.selectNow((_: java.nio.channels.SelectionKey) => ()): Unit
    } catch case _: java.nio.channels.ClosedSelectorException => () // closed, so it let every channel go
    finally dropsWanted.decrementAndGet(): Unit
  }

  // Threads waiting to select on this reactor's selector (`dropCancelledKeys`), for which the loop does not block.
  private val dropsWanted = new java.util.concurrent.atomic.AtomicInteger(0)

  /** Fails the commands pending on the handles closed since the last turn. Their keys, cancelled by the close, are
    * dropped by the next select.
    */
  protected def deliverClosed(): Boolean = {
    var any = false
    var entry = closedQueue.poll()
    while entry != null do {
      val waiting = waitingByEntry.remove(entry)
      if waiting != null then {
        val read = waiting.read
        val write = waiting.write
        waiting.read = null
        waiting.write = null
        if read != null then {
          any = true
          fail(read, gears.async.io.internal.HandleClosedException.pending(read.handle))
        }
        if write != null then {
          any = true
          fail(write, gears.async.io.internal.HandleClosedException.pending(write.handle))
        }
      }
      entry = closedQueue.poll()
    }
    any
  }

  /** Wakes the selector (see `ReactorCore.wakeup`), which writes to its pipe, and the select that returns drains it. */
  protected def wakeNow(): Unit = selector.wakeup(): Unit

  /** Drops the commands waiting on the selector's keys, and on regular files (see `ReactorCore.dropOwn`). */
  protected def dropOwn(drop: NioOp => Unit): Unit = {
    selector.keys().forEach { key =>
      val waiting = key.attachment().asInstanceOf[Waiting]
      if waiting.read != null then drop(waiting.read.nn)
      if waiting.write != null then drop(waiting.write.nn)
      waiting.read = null
      waiting.write = null
    }
    while !again.isEmpty do drop(again.poll())
  }

  /** Closes the selector, which deregisters every channel; the channels stay open (see `ReactorCore.closeOwn`). */
  protected def closeOwn(): Unit = {
    waitingByEntry.clear()
    wakeLock.synchronized {
      closed = true
      selector.close()
    }
    closedQueue.clear()
  }

  /** Takes a pending command off its key, or out of `again` (see `ReactorCore.withdrawOwn`). */
  protected def withdrawOwn(op: NioOp): Completion[NioOp] | Null =
    op match {
      case command: Command =>
        if command.completion == null then null
        else
          command.entry match {
            case _: Selectable =>
              val key = command.key
              if key == null || !(key.selector() eq selector) then null
              else {
                val waiting = key.attachment().asInstanceOf[Waiting]
                val pending = (waiting.read eq command) || (waiting.write eq command)
                if !pending then null // finished when submitted, or not submitted here
                else {
                  if waiting.read eq command then waiting.read = null else waiting.write = null
                  markChanged(key, waiting)
                  take(command)
                }
              }
            case _: RegularFile => if again.remove(command) then take(command) else null
            case _              => null // never submitted
          }
      case _ => gears.async.io.internal.ReactorCore.NotOwnCompletion
    }

  /** Performs the commands a ready key is waiting for. Each is taken off the key first, so its completion may submit
    * the next; one not ready after all waits again, without its completion ever seeing it.
    */
  private def dispatch(key: SelectionKey): Unit = {
    val waiting = key.attachment().asInstanceOf[Waiting]
    val readyOps =
      try key.readyOps()
      catch case _: CancelledKeyException => 0
    val read = waiting.read
    if read != null && (readyOps & waiting.readOps) != 0 then {
      waiting.read = null
      if !performReady(read) then waiting.read = read
    }
    val write = waiting.write
    if write != null && (readyOps & waiting.writeOps) != 0 then {
      waiting.write = null
      if !performReady(write) then waiting.write = write
    }
    markChanged(key, waiting)
  }

  /** The regular files tried again each turn, then the interest changes of the turn, made before the select. */
  protected def deliverOwn(): Boolean = {
    val any = deliverAgain()
    updateInterest()
    any
  }

  /** Selects once and performs what it found ready, waiting for something to happen if `block` and nothing is left to
    * try again or deliver.
    */
  protected def poll(block: Boolean): Unit = {
    selectedCount = 0
    val waitFor = block && again.isEmpty && ready.isEmpty
    if !waitFor || !mayBlock() then selector.selectNow(collect)
    else
      try
        if !resolvedQueue.isEmpty || !closedQueue.isEmpty || dropsWanted.get() > 0 then selector.selectNow(collect)
        else if timers.isEmpty then selector.select(collect)
        else {
          // Block no longer than until the earliest timer is due, rounding up, so it is due when the select returns.
          val wait = timers.peek.when - System.nanoTime()
          if wait <= 0 then selector.selectNow(collect)
          else selector.select(collect, (wait + 999_999L) / 1_000_000L)
        }
      finally unpark()
    // Before anything a wake-up was for is looked at: the caller's tasks after this turn, and promises below.
    tookWakeUps()
    var i = 0
    while i < selectedCount do {
      dispatch(selected(i))
      selected(i) = null
      i += 1
    }
  }

}

object NioReactor {

  /** Opens a reactor on a new selector. Close it when done. */
  def open(): NioReactor = new NioReactor(Selector.open())
}
