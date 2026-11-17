package gears.async.io.shareable

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

  /** After `claimAtReady`, on the thread of the event source reporting the handle ready, with the handle's lock for
    * this direction held: tries the op's work again. It takes no claim on the handle's work for it: the lock is what a
    * close waits on. Returns how many bytes the work moved if that settled the op; `ReadinessOp.Blocked` if the handle
    * was not ready after all, which leaves the op claimed, still the handle's waiter, to be released with
    * `releaseReady`; or `ReadinessOp.SettledOtherwise` if the op was settled without doing the work, as cancelled or
    * failed.
    */
  def readyWakeup(): Int

  /** Gives the claim back: whether the op still waits. */
  def releaseReady(): Boolean

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

/** What a handle keeps, for reading or for writing, for the op that waits for it to be ready and for the event source
  * that says when it is. Its own monitor guards it, and a handle has one for each direction, so that a reader and a
  * writer of one handle never wait on each other.
  */
final class HandleReadiness[S <: EventSource] private[shareable] (handle: AbstractBaseHandle, read: Boolean) {

  /** The op that waits. Read without the lock by `clearWaiter`, to find that it has nothing to do. */
  @volatile private var waiter: ReadinessOp | Null = null

  /** The event source listening for the handle: the first an op waited on, and the same one for as long as ops go on
    * waiting (see `AbstractBaseHandle.idleReportsLimit`), so that only it is woken for the handle.
    */
  private var listener: S | Null = null

  /** Counts the listener's reports. */
  @volatile private var reports = 0

  /** The tick at which the handle was last known drained, which is so now if it is still the tick: any other value, as
    * it is to begin with and when a listener goes, means it is not known.
    */
  @volatile private var drainedTick = -1

  /** Reports with no op waiting since one last was. */
  private var idleReports = 0

  /** No listener, so nothing would report the handle ready: whether it is drained is not known. */
  private def forgetDrained(): Unit = drainedTick = reports - 1

  /** Counts the times the handle's listener reported it ready: read before an attempt, for `registerWaiter`. */
  def tick: Int = reports

  /** Whether the handle is known to have nothing to read, or no room to write, as of `tick`, read just before: an op
    * then waits without trying first, since the try could only find that out again. It is known from the last try that
    * found it so, or from an op that took all a report was for, and only while a listener would report anything new,
    * which moves the tick on.
    */
  def drainedAt(tick: Int): Boolean = drainedTick == tick

  /** Makes `op` the handle's waiter, if the handle accepts it: what a handle accepts is its own to decide, and it
    * refuses with an `IllegalArgumentException`. Returns the event source that listens for the handle, which is `self`
    * if none did, and which this has then told to start listening; or null, with `op` not waiting, if the handle was
    * reported ready since `sinceTick`, for the performer to try again.
    */
  def registerWaiter(op: ReadinessOp, sinceTick: Int, self: S): S | Null = {
    var start = false
    val listening: S | Null = synchronized {
      if !handle.acceptsWaiter(read, op, waiter) then
        throw new IllegalArgumentException(s"A ${if read then "read" else "write"} is already pending on this handle")
      // The listener may be another thread's, and have reported the handle ready, to no waiter, after the attempt.
      if reports != sinceTick then null
      else
        waiter = op
        if listener == null then
          start = true
          listener = self
        // The op waits because the handle was not ready at this tick: so the next need not try before the next.
        drainedTick = sinceTick
        listener
    }
    // Only as `self` becomes the listener: it then reports the handle until it stops being one. No report can come
    // before this, and a second op to wait meanwhile is refused, as this one is the waiter.
    if start then
      try self.startListening(handle, read)
      catch
        case failure: Throwable =>
          synchronized {
            if listener eq self then listener = null
            forgetDrained()
            if waiter eq op then waiter = null
          }
          throw failure
    listening
  }

  /** Whether `op` was the handle's waiter, and no longer is. Costs nothing for an op that is not. */
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

  /** `source` reports the handle ready, for `available` bytes to read or room to write, or `ReadinessOp.Unknown`. The
    * handle's waiter, if it can be claimed, does its work here and now, on `source`'s thread, and is the waiter no
    * longer once that settles it; one that finds the handle not ready after all waits on. An op that takes all that was
    * reported leaves the handle known to be drained until its next report. A handle reported ready time after time with
    * no op waiting gives up its listener, which it tells to `stopListening`: the next op that waits makes its own
    * worker the listener. A report from a source that is not the listener is ignored.
    *
    * The whole of it is under the one lock: the op is claimed while it is the waiter, does its work, and is the waiter
    * no longer, with nothing else to take or to swap. The work is done with the lock held and no claim on the handle's
    * work: a close, which begins by failing the waiters under this lock, waits for it, and a report that comes once the
    * handle is closing does nothing.
    */
  def report(source: EventSource, available: Long): Unit = {
    var stop = false
    synchronized {
      // A report from a source that has stopped listening, already on its way, is not this listener's: ignored.
      if listener eq source then
        reports += 1
        val op = waiter
        if op == null then
          val idle = idleReports + 1
          stop = idle >= handle.idleReportsLimit
          idleReports = if stop then 0 else idle
          if stop then
            listener = null
            forgetDrained()
        else
          idleReports = 0
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

  /** Whether `source` is the handle's listener. */
  def listenedBy(source: EventSource): Boolean = synchronized(listener eq source)

  /** For the handle's close: whoever waits fails. Under the lock, which waits for a report's work that is under way,
    * and after which no report does any.
    */
  private[shareable] def failWaiter(): Unit =
    val op = synchronized(waiter)
    if op != null then op.handleClosing()

  /** For the handle's close: the listener drops the handle. */
  private[shareable] def forgetListener(): Unit =
    val listening = synchronized(listener)
    if listening != null then listening.forgetHandle(handle)
}
