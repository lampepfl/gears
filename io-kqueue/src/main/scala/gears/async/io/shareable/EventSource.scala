package gears.async.io.shareable

import gears.async.Cancellable
import gears.async.io.shareable.TimerHeap.TimerSource

/** What reports that handles are ready, a worker's kqueue for one, as the handles and their ops know it. It is a source
  * of timers too: an op that waits for a handle keeps its time limit where the handle is listened for, so that one
  * thread both fires the limit and finds the handle ready.
  */
trait EventSource extends TimerSource {

  /** This source has become `handle`'s listener for reads, or for writes: from now on it reports each time the handle
    * becomes ready, not for as long as it is, and at once if it is ready now. Called once for as long as it stays the
    * listener, by the handle, from `HandleReadiness.registerWaiter`, on this source's own thread.
    */
  def startListening(handle: BaseHandle, read: Boolean): Unit

  /** This source is no longer `handle`'s listener for reads, or for writes: it stops reporting them. Called on this
    * source's own thread, by the handle, from `HandleReadiness.report`.
    */
  def stopListening(handle: BaseHandle, read: Boolean): Unit = ()

  /** From any thread: schedules to run `body` on this source's own thread once `nanos` have passed.
    *
    * Cancellation before the scheduled time prevents the body from running.
    */
  final def runAfter(nanos: Long, body: Runnable): Cancellable =
    val timer = makeSchedule(nanos, body)
    addTimer(timer)
    timer

  /** Creates a cancellable timer (unscheduled) that upon firing will run `body` unless cancelled.
    */
  protected def makeSchedule(nanos: Long, body: Runnable): Timer & Cancellable

  /** The run this source's pool is of: what the handles its ops open belong to, and what a handle is checked against as
    * an op is performed here.
    */
  def handleOwner: HandleOwner

  /** `handle` is closing: whatever this source keeps to find it by is dropped, before its descriptor is released. */
  def forgetHandle(handle: BaseHandle): Unit = ()
}

/** An op waiting for its handle to be ready, as the handle holds it and as an event source wakes it. */
trait ReadinessOp:

  /** Whether the op has its outcome, and so no longer waits, whether or not its performer has cleared it yet. */
  def settled: Boolean

  /** Claims the op for the worker that found its handle ready. Only the handle calls it, from `HandleReadiness.report`,
    * while the op is still its waiter and under the lock a performer clears it under: a performer can't leave, and the
    * op be performed again, between the two.
    */
  def claimAtReady(): Boolean

  /** After `claimAtReady`, on the thread of the event source reporting the handle ready: tries the op's work again. It
    * takes no claim on the handle's work for it: whoever calls it keeps a close from the handle meanwhile, a handle
    * with one waiter by its lock for this direction, which a close waits on, and one with a queue of them by claiming
    * the handle's work itself (see `HandleReadiness`). Returns how many bytes the work moved if that settled the op;
    * `ReadinessOp.Blocked` if the handle was not ready after all, which leaves the op claimed, still the handle's
    * waiter, to be released with `releaseReady`; or `ReadinessOp.SettledOtherwise` if the op was settled without doing
    * the work, as cancelled or failed.
    */
  def readyWakeup(): Int

  /** Gives the claim back: whether the op still waits. */
  def releaseReady(): Boolean

  /** After `claimAtReady`, in place of `readyWakeup`: the op fails with `failure`, its work not tried. */
  def failReady(failure: Throwable): Unit

  /** The handle is closing: the op fails as a closed channel's does. */
  def handleClosing(): Unit

object ReadinessOp:

  /** For `HandleReadiness.report`: the source can't say how much the handle is ready for, or what it reported was not
    * only that, the end of a stream or an error among it, which no later report follows.
    */
  inline val Unknown = Long.MaxValue

  /** What `readyWakeup` returns when the handle was not ready after all. */
  inline val Blocked = -1

  /** What `readyWakeup` returns when the op was settled without its work being done. */
  inline val SettledOtherwise = -2

/** What a handle keeps, for reading or for writing, for the ops that wait for it to be ready and for the event source
  * that says when it is. Its own monitor guards it, and a handle has one for each direction, so that a reader and a
  * writer of one handle never wait on each other.
  *
  * There are two kinds, as a handle's ops are of two kinds (see `ByteStreams`). A stream is read by one op at a time,
  * and written by one: its readiness holds one waiter, and refuses a second (`SingleWaiter`). A listener's accepts and
  * a datagram socket's receives and sends each take something whole, so any number may be performed at once, and they
  * take turns: its readiness holds a queue of them, served in the order they came (`WaiterQueue`). A handle says which
  * it has for each direction (`AbstractBaseHandle.queuesWaiters`).
  */
sealed abstract class HandleReadiness[S <: EventSource] private[shareable] (
    protected val handle: AbstractBaseHandle,
    protected val read: Boolean
) {

  /** The event source listening for the handle: the first an op waited on, and the same one for as long as ops go on
    * waiting (see `AbstractBaseHandle.idleReportsLimit`), so that only it is woken for the handle.
    */
  protected var listener: S | Null = null

  /** Counts the listener's reports. */
  @volatile protected var reports = 0

  /** The tick at which the handle was last known drained, which is so now if it is still the tick: any other value, as
    * it is to begin with and when a listener goes, means it is not known.
    */
  @volatile protected var drainedTick = -1

  /** Reports with no op waiting since one last was. */
  private var idleReports = 0

  /** No listener, so nothing would report the handle ready: whether it is drained is not known. */
  protected final def forgetDrained(): Unit = drainedTick = reports - 1

  /** Counts the times the handle's listener reported it ready: read before an attempt, for `registerWaiter`. */
  final def tick: Int = reports

  /** Whether the handle is known to have nothing to read, or no room to write, as of `tick`, read just before. It is
    * known from the last try that found it so, or from an op that took all a report was for, and only while a listener
    * would report anything new, which moves the tick on.
    */
  final def drainedAt(tick: Int): Boolean = drainedTick == tick

  /** Whether an op about to be performed, having read `tick` just before, waits without trying its work first: on a
    * handle known to be drained, where the try could only find that out again, and on one with a queue while others are
    * ahead of it, whose turn it is.
    */
  def waitsWithoutTrying(tick: Int): Boolean

  /** Makes `op` one of the handle's waiters, if the handle accepts it: what a handle accepts is its own to decide, and
    * it refuses with an `IllegalArgumentException`. `tried` says whether the op tried its work after reading
    * `sinceTick` and found the handle not ready. Returns the event source that listens for the handle, which is `self`
    * if none did, and which this has then told to start listening; or null, with `op` not waiting, if the handle may be
    * ready for all this knows, for the performer to look again: reported ready since `sinceTick`, or never tried.
    */
  def registerWaiter(op: ReadinessOp, sinceTick: Int, tried: Boolean, self: S): S | Null

  /** Whether `op` was one of the handle's waiters, and no longer is. Costs nothing for an op that is not. */
  def clearWaiter(op: ReadinessOp): Boolean

  /** `source` reports the handle ready, for `available` bytes to read or room to write, or `ReadinessOp.Unknown`. The
    * waiters that can be claimed do their work here and now, on `source`'s thread, each no longer a waiter once that
    * settles it; one that finds the handle not ready after all waits on. A handle reported ready time after time with
    * no op waiting gives up its listener, which it tells to `stopListening`: the next op that waits makes its own
    * worker the listener. A report from a source that is not the listener is ignored.
    */
  def report(source: EventSource, available: Long): Unit

  /** For the handle's close: whoever waits fails. */
  private[shareable] def failWaiters(): Unit

  /** Under the lock, as `self` is told to wait: `self` is the listener if there was none. Whether it is to start. */
  protected final def listenWith(self: S): Boolean =
    if listener == null then
      listener = self
      true
    else false

  /** `self`, just made the listener, starts listening, outside the lock: it then reports the handle until it stops
    * being one. If it can't, `undo` takes back, under the lock, what was done for the op that waited on it.
    */
  protected final def startListening(self: S)(undo: => Unit): Unit =
    try self.startListening(handle, read)
    catch
      case failure: Throwable =>
        synchronized {
          if listener eq self then listener = null
          forgetDrained()
          undo
        }
        throw failure

  /** Under the lock: a report came with no op waiting. Whether that is one too many, and the listener is given up, for
    * the caller to tell it to stop once out of the lock.
    */
  protected final def idleReport(): Boolean =
    val idle = idleReports + 1
    val stop = idle >= handle.idleReportsLimit
    idleReports = if stop then 0 else idle
    if stop then
      listener = null
      forgetDrained()
    stop

  /** Under the lock: a report found an op waiting. */
  protected final def busyReport(): Unit = if idleReports != 0 then idleReports = 0

  /** Whether `source` is the handle's listener. */
  final def listenedBy(source: EventSource): Boolean = synchronized(listener eq source)

  /** For the handle's close: the listener drops the handle. */
  private[shareable] final def forgetListener(): Unit =
    val listening = synchronized(listener)
    if listening != null then listening.forgetHandle(handle)
}

/** The readiness of a stream, for one direction: one op waits at a time. */
private[shareable] final class SingleWaiter[S <: EventSource](handle: AbstractBaseHandle, read: Boolean)
    extends HandleReadiness[S](handle, read) {

  /** The op that waits. Read without the lock by `clearWaiter`, to find that it has nothing to do. */
  @volatile private var waiter: ReadinessOp | Null = null

  def waitsWithoutTrying(tick: Int): Boolean = drainedTick == tick

  def registerWaiter(op: ReadinessOp, sinceTick: Int, tried: Boolean, self: S): S | Null = {
    var start = false
    val listening: S | Null = synchronized {
      if !handle.acceptsWaiter(read, op, waiter) then
        throw new IllegalArgumentException(s"A ${if read then "read" else "write"} is already pending on this handle")
      // The listener may be another thread's, and have reported the handle ready, to no waiter, after the attempt.
      if reports != sinceTick then null
      else
        waiter = op
        start = listenWith(self)
        // The op waits because the handle was not ready at this tick: so the next need not try before the next.
        drainedTick = sinceTick
        listener
    }
    // No report can come before this, and a second op to wait meanwhile is refused, as this one is the waiter.
    if start then startListening(self)(if waiter eq op then waiter = null)
    listening
  }

  def clearWaiter(op: ReadinessOp): Boolean =
    // Looked at first without the lock: an op done by its handle's listener was taken out by `report`, and only its
    // own performer, here, puts it in.
    if waiter ne op then false
    else
      // Under the lock, which `report` claims under: see `ReadinessOp.claimAtReady`.
      synchronized {
        val held = waiter eq op
        if held then waiter = null
        held
      }

  /** The whole of it is under the one lock: the op is claimed while it is the waiter, does its work, and is the waiter
    * no longer, with nothing else to take or to swap. The work is done with the lock held and no claim on the handle's
    * work: a close, which begins by failing the waiters under this lock, waits for it, and a report that comes once the
    * handle is closing does nothing. An op that takes all that was reported leaves the handle known to be drained until
    * its next report.
    */
  def report(source: EventSource, available: Long): Unit = {
    var stop = false
    synchronized {
      // A report from a source that has stopped listening, already on its way, is not this listener's: ignored.
      if listener eq source then
        reports += 1
        val op = waiter
        if op == null then stop = idleReport()
        else
          busyReport()
          if handle.getState == HandleState.OpenState && op.claimAtReady() then
            val moved = op.readyWakeup()
            if moved == ReadinessOp.Blocked then
              // If a cancel settles the op as its claim is given back, its performer clears it.
              op.releaseReady(): Unit
            else
              waiter = null
              // All that was there as the source took its report: whatever comes after it is reported anew, so until
              // then there is nothing, and the next op need not look. Not at the end of a stream, where nothing was
              // moved, since no report follows it.
              if moved > 0 && moved >= available then drainedTick = reports
    }
    if stop then source.stopListening(handle, read)
  }

  /** Under the lock, which waits for a report's work that is under way, and after which no report does any. */
  private[shareable] def failWaiters(): Unit =
    val op = synchronized(waiter)
    if op != null then op.handleClosing()
}

/** The readiness of a handle whose ops take turns, for one direction: a queue of the ops that wait, in the order they
  * came. An op performed while others wait goes behind them without trying its work, so that none is overtaken; one
  * that finds no queue tries at once, and waits at its head if the handle is not ready.
  *
  * A report serves the queue from its head, on the listener's thread, until the handle is not ready any more or no op
  * waits. Unlike a stream's one waiter, the work is not done under the lock: other futures are joining and leaving the
  * queue meanwhile, and an accept is a system call and a handle made. Each op is taken from the queue and claimed under
  * the lock, and does its work outside it, with the handle's work claimed, which is what keeps a close away.
  */
private[shareable] final class WaiterQueue[S <: EventSource](handle: AbstractBaseHandle, read: Boolean)
    extends HandleReadiness[S](handle, read) {

  private val queue = new java.util.ArrayDeque[ReadinessOp]

  /** The ops in the queue, and the one a report is serving, if any. Written under the lock; read without it to find
    * that there is no queue to join, or to leave.
    */
  @volatile private var pending = 0

  def waitsWithoutTrying(tick: Int): Boolean = pending != 0 || drainedTick == tick

  def registerWaiter(op: ReadinessOp, sinceTick: Int, tried: Boolean, self: S): S | Null = {
    var start = false
    val listening: S | Null = synchronized {
      // Behind others it is safe to wait whatever this op knows: the one at the head found the handle not ready at
      // the latest report, or a report is serving the queue now, and will come to this op.
      val known = pending != 0 || (reports == sinceTick && (tried || drainedTick == sinceTick))
      if !known then null
      else
        if pending == 0 then drainedTick = sinceTick
        queue.addLast(op)
        pending += 1
        start = listenWith(self)
        listener
    }
    if start then
      // Ops that came to wait behind this one while `self` was starting are waiting on a listener that is not one:
      // they are taken out with it, and fail as it does, each for its own performer.
      var stranded = WaiterQueue.NoOps
      try
        startListening(self) {
          if queue.remove(op) then pending -= 1
          if !queue.isEmpty() then
            stranded = queue.toArray(WaiterQueue.NoOps)
            queue.clear()
            pending -= stranded.length
        }
      catch
        case failure: Throwable =>
          stranded.foreach(other => if other.claimAtReady() then other.failReady(failure))
          throw failure
    listening
  }

  def clearWaiter(op: ReadinessOp): Boolean =
    if pending == 0 then false
    else
      synchronized {
        val held = queue.remove(op)
        if held then pending -= 1
        held
      }

  /** Under the lock: the first op in the queue that can be claimed, claimed and out of the queue, still counted in
    * `pending` as the one being served; null if there is none, or the handle is closing, whose close fails them. One
    * that was settled otherwise, by its time limit or a cancel, and not yet cleared by its performer, is dropped.
    */
  private def takeClaimed(): ReadinessOp | Null =
    var taken: ReadinessOp | Null = null
    if handle.getState == HandleState.OpenState then
      while taken == null && !queue.isEmpty() do
        val op = queue.pollFirst()
        if op.claimAtReady() then taken = op
        else pending -= 1
    taken

  /** `op`, claimed, does its work with the handle's work claimed: what `readyWakeup` returns. If the handle began to
    * close since the op was claimed, it fails as the close fails the others.
    */
  private def serve(op: ReadinessOp): Int =
    val open =
      try
        handle.claimWork()
        true
      catch
        case closed: java.io.IOException =>
          op.failReady(closed)
          false
    if !open then ReadinessOp.SettledOtherwise
    else
      try op.readyWakeup()
      finally handle.releaseWork()

  def report(source: EventSource, available: Long): Unit = {
    var stop = false
    var op: ReadinessOp | Null = null
    synchronized {
      if listener eq source then
        reports += 1
        if pending == 0 then stop = idleReport()
        else
          busyReport()
          op = takeClaimed()
    }
    while op != null do
      val serving = op.nn
      val moved = serve(serving)
      synchronized {
        if moved == ReadinessOp.Blocked then
          // Not ready any more: it waits on at the head, and so do those behind it, for the next report.
          queue.addFirst(serving)
          drainedTick = reports
          serving.releaseReady(): Unit
          op = null
        else
          pending -= 1
          op = takeClaimed()
      }
    if stop then source.stopListening(handle, read)
  }

  /** Those in the queue fail. One a report is serving has the handle's work claimed, which the close waits for. */
  private[shareable] def failWaiters(): Unit =
    val waiting = synchronized(queue.toArray(new Array[ReadinessOp](0)))
    waiting.foreach(_.handleClosing())
}

private[shareable] object WaiterQueue {
  val NoOps: Array[ReadinessOp] = new Array[ReadinessOp](0)
}
