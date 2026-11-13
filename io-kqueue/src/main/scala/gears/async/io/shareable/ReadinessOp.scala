package gears.async.io.shareable

/** An op waiting for its handle to be ready, as the handle holds it and as an event source wakes it. */
trait ReadinessOp:

  /** Whether the op has its outcome, and so no longer waits, whether or not its performer has cleared it yet. */
  def settled: Boolean

  /** Claims the op for the worker that found its handle ready. Only the handle calls it, from `readyWaiter`, while
    * the op is still its waiter: a performer can't leave, and the op be performed again, between the two.
    */
  def claimAtReady(): Boolean

  /** Worker thread, after `claimAtReady`: tries the op's work again. True if that settled the op; false if the handle
    * was not ready after all, which leaves the op claimed, still the handle's waiter, for the event source to
    * `releaseReady`. `available` is how much the source reported the handle ready for, bytes to read or room to
    * write, as it took the report, or `ReadinessOp.Unknown`: an op that takes all of it tells the handle it is
    * drained (`BaseHandle.markDrained`).
    */
  def readyWakeup(available: Long, source: EventSource): Boolean

  /** Gives the claim back: whether the op still waits. */
  def releaseReady(): Boolean

  /** The handle is closing: the op fails as a closed channel's does. */
  def handleClosing(): Unit

object ReadinessOp:

  /** For `readyWakeup`: the source can't say how much the handle is ready for, or what it reported was not only that,
    * the end of a stream or an error among it, which no later report follows.
    */
  inline val Unknown = Long.MaxValue
