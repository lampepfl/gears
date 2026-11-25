package gears.async.io.shareable

/** What one attempt at an op's work did, which must not block: it moved some bytes, or took some other count of what
  * the op takes; it found the end of a stream, having moved nothing; or the handle was not ready, and nothing was done.
  * One convention for every backend's calls and every op, in place of each call's own sentinels.
  *
  * The count is kept, not only that something was moved: a handle that learns from its event source how much there was
  * knows from it when it has been drained, and so that the next op need not try (see `HandleReadiness.report`).
  */
opaque type Moved = Int

object Moved:

  /** The handle was not ready: the attempt did nothing, and the op waits. */
  inline def Blocked: Moved = -1

  /** The end of a stream: the attempt is done, having moved nothing. */
  inline def EndOfStream: Moved = 0

  /** `count` bytes, or things, moved: not negative. Of a read, none is the end of the stream. */
  inline def apply(count: Int): Moved = count

  /** As `apply`, of a count a call gives in more than an `Int` holds, as a vectored call does: held at the most an
    * `Int` says, since wrapped it would say that nothing was moved, or that the handle was not ready. The bytes are
    * moved whatever is said of them.
    */
  def of(count: Long): Moved = if count > Int.MaxValue then Int.MaxValue else count.toInt

  extension (moved: Moved)

    /** Whether the handle was not ready. */
    inline def isBlocked: Boolean = moved < 0

    /** How much was moved, by an attempt that was not blocked. */
    inline def count: Int = moved

/** What became of an op that was woken because its handle was reported ready (`ReadinessOp.readyWakeup`): its work was
  * done, moving some count; the handle was not ready after all, and the op waits on, still claimed; or the op was
  * settled without its work being done, as cancelled or failed. Made of what the attempt did (`Readiness.of`), or
  * without one.
  */
opaque type Readiness = Int

object Readiness:

  /** The handle was not ready after all: the op is still claimed, and still the handle's waiter. */
  inline def Blocked: Readiness = -1

  /** The op was settled without its work being done. */
  inline def SettledOtherwise: Readiness = -2

  /** Of an attempt at the work: blocked if that was, and otherwise done, having moved what it moved. */
  inline def of(moved: Moved): Readiness = moved

  extension (readiness: Readiness)

    /** Whether the handle was not ready after all. */
    inline def isBlocked: Boolean = readiness == -1

    /** Whether the work was done and took all of the `available` its handle was reported ready for, so that the handle
      * has nothing more until it is reported again. Not at the end of a stream, where nothing was moved, since no
      * report follows it; and never when more was available than a count holds, which errs towards trying again.
      */
    inline def took(available: Long): Boolean = readiness > 0 && readiness >= available
