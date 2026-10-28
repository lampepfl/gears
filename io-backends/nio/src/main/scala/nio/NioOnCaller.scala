package gears.async.io
package nio

import gears.async.Async
import gears.async.cancellationScope
import gears.async.io.internal.CallerBackend
import gears.async.io.internal.Interest

import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** NIO with no reactor: the backend for a program whose threads may block, such as gears' own virtual threads, the
  * JVM's default. It has the ops and handles of `NioBackend`, and does each op on the calling thread. A command is
  * tried at once, since a non-blocking channel may be used from any thread; one that would block is tried again after a
  * yield, up to `Retries` times, because in request-response traffic the reply often arrives within microseconds, and a
  * yield puts the caller behind every other thread ready to run, so this stays fair; then it waits on its handle's own
  * selector (`SelectorWait`), which the JDK's poller parks a virtual thread in, and is tried again when woken. A timer
  * parks until its time (`TimerWait`); a blocking op runs on the caller (`BlockingHere`), interrupted from a timer
  * thread if its time limit is up; an op done at once, such as a close, is done. Cancelling the caller ends the wait,
  * and the handle stays open. Nothing spins: busy waiting gave the best latency and starved the other futures, so it
  * was dropped (see "Tried and dropped" in docs/io/jvm.md). A time limit runs from the call: an op whose time runs out
  * has done nothing and throws. Its `Io`, which `Io.run` hands the program, performs on the calling thread, from any
  * `Async` context. Nothing of the JDK beyond its public interface.
  */
object NioOnCaller extends CallerBackend with NioCapabilities {

  /** How many times a command that would block is tried again after a yield, before it waits on its selector
    * (`-Dgears.io.nio.retries`).
    */
  val Retries: Int = sys.props.get("gears.io.nio.retries").map(_.toInt).getOrElse(8)

  /** Submissions of one command in a row that finished at once before the caller yields, so that a run of them gives up
    * the thread now and then, as a pool's fast path gives up its turn.
    */
  inline val HereBudget = 32

  private[io] def performHere(op: Op, timeoutMillis: Int)(using ac: Async): Unit = {
    if ac.group.isCancelled then throw new CancellationException()
    op.markSubmitted()
    // In a reactor's order (`ReactorCore.submitOrFinish`): before anything is opened, so nothing is abandoned into the
    // caller's slot, and before the handle's turn is looked at.
    val slotRefusal = op.slotRefusal
    if slotRefusal != null then throw slotRefusal
    val refusal = op.refusal
    if refusal != null then throw refusal
    val deadline = if timeoutMillis < 0 then -1L else System.nanoTime() + timeoutMillis * 1_000_000L
    op match {
      case connect: ConnectTo =>
        connect.start()
        try performCommand(connect, deadline)
        catch {
          case t: Throwable =>
            connect.abandon() // what it opened never reaches the caller
            throw t
        }
      case command: Command =>
        // Its handle's turn to read or write, held while it is performed, as on a reactor (`Command.claim`): waited for,
        // by an op that may wait for it, else refused.
        val claim = command.claim
        if claim != null && !claim.take(command) then
          if command.waitsItsTurn then waitForTurn(claim, command, deadline)
          else throw gears.async.io.internal.Checks.alreadyPending(command)
        try performCommand(command, deadline)
        finally if claim != null then claim.release(command)
      case timer: NioTimer =>
        // A limit no shorter than the timer leaves it to complete, as a reactor does, rather than racing it.
        performTimer(timer, timeoutMillis)
      case blocking: NioBlocking => performBlocking(blocking, deadline)
      case atOnce: NioAtOnce     =>
        atOnce.perform()
    }
  }

  /** Waits on the caller for `command`'s turn on its handle, held by another op, until `deadline`, failing with a
    * `TimeoutException` after it, or a `CancellationException` once the caller is cancelled, leaving the queue either
    * way.
    */
  private def waitForTurn(turns: gears.async.io.internal.Turns, command: Command, deadline: Long)(using Async): Unit = {
    val wait = new TurnWait(turns, command)
    turns.enqueue(command, () => wait.wake())
    var holds = false
    try
      cancellationScope(wait) {
        while !holds do {
          holds = wait.park(remaining(deadline))
          if !holds then {
            checkCancelled()
            if expired(deadline) then throw new TimeoutException(s"$command timed out")
          }
        }
      }
    finally if !holds && !turns.withdraw(command) then turns.release(command) // given it as it gave up: passed on
  }

  private def expired(deadline: Long): Boolean = deadline >= 0 && System.nanoTime() >= deadline

  private def remaining(deadline: Long): Long = if deadline < 0 then -1L else math.max(deadline - System.nanoTime(), 0L)

  private def checkCancelled()(using ac: Async): Unit =
    if ac.group.isCancelled then throw new CancellationException()

  private def performCommand(command: Command, deadline: Long)(using ac: Async): Unit = {
    require(command.completion == null, s"$command is already pending")
    val entry = NioHandles.entry(command.handle) // a closed handle fails the op, as it would on a reactor
    command.entry = entry
    // A run of commands finishing at once gives up the thread now and then, so the futures beside the caller run.
    if command.finishedHere >= HereBudget then {
      command.finishedHere = 0
      Thread.`yield`()
    }
    var done = command.perform()
    if done then command.finishedHere += 1
    else {
      command.finishedHere = 0
      var tries = Retries
      while !done && tries > 0 do {
        tries -= 1
        Thread.`yield`()
        checkCancelled()
        done = command.perform()
        if !done && expired(deadline) then throw new TimeoutException(s"$command timed out")
      }
    }
    if !done then
      entry match {
        case s: Selectable =>
          val wait = new SelectorWait(s, command.interest == Interest.Write, command.selectOps(s.channel))
          cancellationScope(wait) {
            while !done do {
              wait.park(remaining(deadline)): Unit
              checkCancelled()
              done = command.perform()
              if !done && expired(deadline) then throw new TimeoutException(s"$command timed out")
            }
          }
        case _ => // A regular file is always ready: tried again after each yield, as a reactor does each turn.
          while !done do {
            Thread.`yield`()
            checkCancelled()
            done = command.perform()
            if !done && expired(deadline) then throw new TimeoutException(s"$command timed out")
          }
      }
  }

  private def performTimer(timer: NioTimer, timeoutMillis: Int)(using ac: Async): Unit = {
    require(timer.completion == null, s"$timer is already pending")
    timer.start(System.nanoTime(), timeoutMillis)
    // A timer already due yields first, so that, as on a reactor, where its completion runs a turn later, the other
    // threads ready to run get their turn: a loop of them stays fair.
    if timer.due(System.nanoTime()) == gears.async.io.internal.TimerState.Complete then Thread.`yield`()
    else {
      val wait = new TimerWait(timer)
      var outcome = gears.async.io.internal.TimerState.Again
      cancellationScope(wait) {
        while outcome == gears.async.io.internal.TimerState.Again do {
          wait.park(-1L): Unit
          checkCancelled()
          outcome = timer.due(System.nanoTime())
        }
      }
      if outcome == gears.async.io.internal.TimerState.Expire then throw new TimeoutException(s"$timer timed out")
    }
  }

  private def performBlocking(blocking: NioBlocking, deadline: Long)(using ac: Async): Unit = {
    val here = blocking.here()
    val expiry =
      if deadline < 0 then null
      else WaitsHere.timeouts.schedule((() => here.cancel()): Runnable, remaining(deadline), TimeUnit.NANOSECONDS)
    try cancellationScope(here)(here.run())
    catch {
      case e: CancellationException if !ac.group.isCancelled && expired(deadline) =>
        throw new TimeoutException(s"$blocking timed out")
    } finally if expiry != null then expiry.cancel(false): Unit
  }
}
