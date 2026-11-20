package gears.async.io.node.base

import gears.async.Async
import gears.async.Cancellable
import gears.async.io.shareable
import gears.async.io.shareable.HandleOwner

/** What a heap of timers holds: the shared layer's. */
type TimerOp = shareable.TimerHeap.TimerOp

object Deadlines {

  /** A deadline that never comes. */
  inline val Never = Long.MaxValue

  /** `nanos` after `from`, or `Never` if that is beyond what the clock counts. */
  def deadlineAfter(from: Long, nanos: Long): Long =
    if nanos >= Never - from then Never else from + nanos
}

/** The backend's one event source, as the shared layer has them (`EventSource`): where a worker of a pool has a poller
  * that reports its handles ready and a heap of its timers, Node's event loop has this. It is the listener of every
  * handle that is waited on, which the handle's own events report to it; and it is the one heap of timers, holding
  * every deadline of the backend, an op's own and its time limit as one entry, so that the event loop is asked for one
  * timer, the earliest. With one thread there is nothing to guard.
  *
  * `rearm` is the event loop's, called when the earliest deadline changes, when a timer becomes the earliest or the
  * heap empties, and at no other time: a time limit that comes and goes with its op, as most do, costs the loop
  * nothing.
  */
object NodeSource extends shareable.BinaryTimerHeap, shareable.EventSource, shareable.OpPerformer[NodeSource.type] {
  type Timer = TimerOp
  type Handle = WaitableHandle

  /** The owner of the handles of the run whose op is being performed. There is one event source for every run, Node
    * having one event loop, so this is set as each op begins to be performed (`performing`), and is that run's for as
    * long as the op runs without suspending: an op asks for it as it begins, and keeps what it was given.
    */
  def handleOwner: HandleOwner = current

  private var current: HandleOwner = new HandleOwner

  /** An op of the run that owns `owner` begins to be performed. */
  def performing(owner: HandleOwner): Unit = current = owner

  /** No op of this backend's blocks: Node has no thread to block. */
  protected[io] def performBlocking(work: shareable.BackendOp.OffThread[NodeSource.type])(using Async): Unit =
    throw new UnsupportedOperationException("The Node.js backend has no op that blocks")

  /** A timer already due is done: there is nothing to put on the heap and nothing to wait for. */
  override protected[io] def performTimed(timed: shareable.BackendOp.Timed[NodeSource.type])(using Async): Unit =
    timed match {
      case timer: shareable.AbstractMakeTimerOp if timer.isDue                                      => ()
      case deadline: shareable.AbstractMakeDeadlineOp if deadline.deadline - System.nanoTime() <= 0 => ()
      case _ => super.performTimed(timed)
    }

  /** Set by the event loop as it starts. */
  var rearm: () => Unit = () => ()

  protected def claimHeap(): Unit = ()
  protected def releaseHeap(): Unit = ()

  protected def earliestChanged(): Unit = rearm()

  /** The event loop is told, so that a timer of its own does not keep the process running with nothing to wait for. */
  override protected def emptied(): Unit = rearm()

  def isEmpty: Boolean = !hasTimers

  /** Nothing: a handle is told of each op that waits for it (`WaitableHandle.waiterWaits`), and Node tells it when it
    * is ready, with nothing for this source to register.
    */
  def startListening(handle: WaitableHandle, read: Boolean): Unit = ()

  /** The handle was reported ready with no op waiting: it stops finding out, until one waits again. */
  override def stopListening(handle: WaitableHandle, read: Boolean): Unit = handle.stopListening(read)

  protected def makeSchedule(nanos: Long, body: Runnable): DoAfter =
    new DoAfter(Deadlines.deadlineAfter(System.nanoTime(), nanos), () => body.run())
}

/** A call to make once a deadline has come, as a timer on the heap. */
final class DoAfter(val deadlineNow: Long, body: () => Unit) extends shareable.TimerHeap.TimerOp, Cancellable {
  private var cancelled = false

  def claimAtDeadline(): Boolean = !cancelled

  def timerWakeup(now: Long): Unit = body()

  /** The call is not made, wherever the timer is: taking it off the heap is the canceller's. */
  def cancel(): Unit = cancelled = true
}
