package gears.async.io.shareable

import gears.async.io.shareable.TimerHeap.{TimerOp, TimerSource}

/** What reports that handles are ready, a worker's kqueue for one, as the handles and their ops know it. */
trait EventSource {

  /** Puts `op`, which has a time limit, on this source's own heap, and returns that heap: an op waits where its
    * handle is listened for, so that one thread both fires its time limit and finds its handle ready.
    */
  def holdTimer(op: TimerOp): TimerSource[?]

  /** This source has become `handle`'s listener for reads, or for writes: from now on it reports each time the
    * handle becomes ready, not for as long as it is, and at once if it is ready now. Called once for as long as it
    * stays the listener, by the handle, from `awaitReadiness`, on this source's own thread.
    */
  def startListening(handle: BaseHandle, read: Boolean): Unit

  /** This source is no longer `handle`'s listener for reads, or for writes: it stops reporting them. Called on this
    * source's own thread, by the handle, from `readyWaiter`.
    */
  def stopListening(handle: BaseHandle, read: Boolean): Unit = ()

  /** `handle` is closing: whatever this source keeps to find it by is dropped, before its descriptor is released. */
  def forgetHandle(handle: BaseHandle): Unit = ()
}
