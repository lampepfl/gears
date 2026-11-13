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
    * `releaseReady`.
    */
  def readyWakeup(): Boolean

  /** Gives the claim back: whether the op still waits. */
  def releaseReady(): Boolean

  /** The handle is closing: the op fails as a closed channel's does. */
  def handleClosing(): Unit
