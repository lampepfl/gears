package gears.async.io
package node

import gears.async.Async
import gears.async.GearsCancellationException
import gears.async.io.node.base.Deadlines
import gears.async.io.node.base.NodeSource
import gears.async.io.node.host.NodeErr
import gears.async.io.node.internal.NodeGlobals
import gears.async.io.shareable.HandleOwner

import scala.scalajs.js
import scala.scalajs.js.wasm.JSPI.allowOrphanJSAwait

/** A run of a program on the Node.js backend, as its `Io`: what the program's ops are performed through, and what the
  * handles they open belong to (`owner`), which are closed as the run ends and are no other run's to use.
  */
private[node] final class NodeRun extends Io[NodeOps.type] {
  val backend: NodeBackend.type = NodeBackend
  val ops: NodeOps.type = NodeOps

  private[node] val owner: HandleOwner = new HandleOwner

  private[io] def performWithin(op: NodeOp, timeoutMillis: Long)(using Async): Unit =
    NodeLoop.perform(op, timeoutMillis, owner)

  /** The run has ended: what it opened and did not close is closed. */
  private[node] def end(): Unit = owner.closeAll()
}

/** Where the Node.js backend's ops run: Node's event loop, one for every run there is.
  *
  * It performs an op for the future performing it, as its kind is performed (`BackendOp.performOn`), and keeps the two
  * things the backend asks of the event loop itself: one timer, for the earliest of every deadline there is (`timers`),
  * and a turn now and then for the callbacks that wait while ops complete without waiting (`spendTurn`).
  */
private[node] object NodeLoop {

  /** Performs `op` for a run, whose handles are `owner`'s. */
  def perform(op: NodeOp, timeoutMillis: Long, owner: HandleOwner)(using async: Async): Unit = {
    if async.group.isCancelled then throw new GearsCancellationException()
    try
      op.performWith {
        op.setTimeout(timeoutMillis)
        try {
          // Until the op first suspends, the event source's owner of handles is this run's: the op takes it then.
          timers.performing(owner)
          op.performOn(timers)
        } catch case js.JavaScriptException(error) => throw NodeErr.io(error)
        finally op.clearTimeout()
      }
    // An op that ended without waiting, completed or refused, has left the event loop no turn: it is counted.
    finally if !op.suspendedPerformer then spendTurn()
  }

  // ---- The one timer

  /** The backend's event source, and with it every deadline of the backend: an op's own, its time limit, a FIFO's next
    * look.
    */
  val timers: NodeSource.type = NodeSource
  timers.rearm = () => rearm()

  /** The longest Node's timers wait, in milliseconds: beyond 2^31 - 1 they fire at once. */
  private inline val LongestWait = 1L << 30

  /** How many timers are fired before the event loop is given a turn. */
  private inline val TimerBudget = 32

  private var timeout: js.UndefOr[js.timers.SetTimeoutHandle] = js.undefined
  private var immediate: js.UndefOr[js.Dynamic] = js.undefined

  /** The deadline Node's timer was set for, if one is set. */
  private var armedFor = 0L

  private def armed: Boolean = timeout.isDefined || immediate.isDefined

  private def disarm(): Unit = {
    timeout.foreach(js.timers.clearTimeout(_))
    immediate.foreach(NodeGlobals.clearImmediate(_))
    timeout = js.undefined
    immediate = js.undefined
  }

  private val fire: js.Function0[Unit] = () => {
    timeout = js.undefined
    immediate = js.undefined
    val now = System.nanoTime()
    var budget = TimerBudget
    while budget > 0 do {
      val timer = timers.nextReadyTimer(now)
      if timer == null then budget = 0
      else {
        timer.timerWakeup(now)
        budget -= 1
      }
    }
    // What a timer's waiter did as it fired may have armed the timer already.
    if !armed && !timers.isEmpty then arm(timers.peekNextDeadline())
  }

  /** Node counts whole milliseconds, on a clock of its own, and may fire a little early by this one: a timer that fires
    * before its time finds nothing due, and is set again. A deadline that has come is fired on the event loop's next
    * turn, never from within the call that found it so. With no deadline in sight, a timer is set all the same, for as
    * long as Node allows: a program that waits for ever is still running, and without a timer of Node's, Node would end
    * the process.
    */
  private def arm(deadline: Long): Unit = {
    armedFor = deadline
    val left = if deadline == Deadlines.Never then Deadlines.Never else deadline - System.nanoTime()
    if left <= 0 then immediate = NodeGlobals.setImmediate(fire)
    else timeout = js.timers.setTimeout(math.min((left + 999_999L) / 1_000_000L, LongestWait).toDouble)(fire())
  }

  /** The heap's earliest deadline has changed, or the heap has emptied. A timer of Node's set for an earlier time than
    * is now needed is left: it finds nothing due, at worst. One is cleared only when nothing waits at all, so that it
    * does not keep the process running.
    */
  private def rearm(): Unit =
    if timers.isEmpty then disarm()
    else {
      val next = timers.peekNextDeadline()
      if !armed then arm(next)
      else if next < armedFor then {
        disarm()
        arm(next)
      }
    }

  // ---- Turns

  private inline val TurnBudget = 256
  private var spent = 0

  /** Lets the event loop turn among ops that end without waiting: a future whose every op is ready at once, a read at
    * the end of its stream say, or refused at once, one on a closed handle, would otherwise never leave the loop a
    * turn, and no timer would fire nor any socket be heard. Every `TurnBudget` such ops, whichever futures perform
    * them, the one performing waits for the loop's next turn.
    *
    * The count is not of one turn of the loop: it would be, were the loop asked to say when each turn begins, but that
    * is a call to Node for every turn in which such an op is performed, which an echo's write is, in every turn. That
    * measured 5000 instructions a round trip over one TCP connection, a sixteenth of all it took, where a turn waited
    * for every 256 ops costs a tenth of that.
    */
  private def spendTurn(): Unit = {
    spent += 1
    if spent >= TurnBudget then {
      spent = 0
      js.await(new js.Promise[Unit]((resolve, _) => NodeGlobals.setImmediate(() => resolve(()): Unit): Unit))
    }
  }
}
