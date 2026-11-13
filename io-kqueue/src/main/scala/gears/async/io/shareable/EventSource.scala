package gears.async.io.shareable

import gears.async.io.shareable.TimerHeap.{TimerOp, TimerSource}

/** What reports that handles are ready, a worker's kqueue for one, as the handles and their ops know it. */
trait EventSource {

  /** Puts `op`, which has a time limit, on this source's own heap, and returns that heap: an op waits where its
    * handle is listened for, so that one thread both fires its time limit and finds its handle ready.
    */
  def holdTimer(op: TimerOp): TimerSource[?]

  /** This source is no longer `handle`'s listener for reads, or for writes: it stops reporting them. Called on this
    * source's own thread, by the handle, from `readyWaiter`.
    */
  def stopListening(handle: BaseHandle, read: Boolean): Unit = ()

  /** `handle` is closing: whatever this source keeps to find it by is dropped, before its descriptor is released. */
  def forgetHandle(handle: BaseHandle): Unit = ()
}
