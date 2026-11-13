package gears.async.io.shareable


import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** A handle's state and the work claimed on it, in one word, so that each changes with regard to the other in one
  * step: the state in the top byte, and in the 56 bits below it the count of the work now claimed.
  */
object HandleState:
  inline val UnenteredState = 0
  inline val OpenState = 1
  inline val ClosingState = 2
  inline val ClosedState = 3
  type State = UnenteredState.type | OpenState.type | ClosingState.type | ClosedState.type

  def showState(state: State): String =
    state match
      case UnenteredState => "Unentered"
      case OpenState      => "Open"
      case ClosingState   => "Closing"
      case ClosedState    => "Closed"

  private inline val StateShift = 56

  /** The most work that can be claimed at once, and the mask of its count in the word. */
  inline val MaxClaimed = 0x00ffffffffffffffL

  private inline def stateOf(word: Long): Int = (word >>> StateShift).toInt
  private inline def claimedOf(word: Long): Long = word & MaxClaimed
  private inline def wordOf(state: Int, claimed: Long): Long = (state.toLong << StateShift) | claimed

  sealed abstract class Holder:
    private val word = AtomicLong(wordOf(UnenteredState, 0L))

    final def getState: State = stateOf(word.get()).asInstanceOf[State]

    /** How much work is claimed on the handle now. */
    final def claimedWork: Long = claimedOf(word.get())

    /** Moves from `expected` to `newState`, whatever work is claimed, which stays as it is. */
    final def compareAndSetState(expected: State, newState: State): Boolean =
      var result = false
      var done = false
      while !done do
        val current = word.get()
        if stateOf(current) != expected then done = true
        else if word.compareAndSet(current, wordOf(newState, claimedOf(current))) then
          result = true
          done = true
      result

    /** Claims work if the handle is open: the state it was found in, which is `OpenState` if the work was claimed. */
    protected final def claimIfOpen(): State =
      var found: Int = UnenteredState
      var done = false
      while !done do
        val current = word.get()
        found = stateOf(current)
        if found != OpenState then done = true
        else if claimedOf(current) == MaxClaimed then throw new IllegalStateException("Too much work claimed")
        else done = word.compareAndSet(current, current + 1)
      found.asInstanceOf[State]

    /** Gives back work claimed with `claimIfOpen`, which a handle that has begun to close still lets its work do:
      * whether that was the last of a closing handle's, for the caller then to close it.
      */
    protected final def releaseClaimed(): Boolean =
      var last = false
      var done = false
      while !done do
        val current = word.get()
        if claimedOf(current) == 0 then
          throw new IllegalStateException(s"Releasing work when none is claimed (${showState(getState)})")
        else if word.compareAndSet(current, current - 1) then
          last = stateOf(current) == ClosingState && claimedOf(current) == 1
          done = true
      last

    /** Begins the close of an open handle: the state it was found in, and, if that was `OpenState`, whether no work
      * was claimed, for the caller then to close it; otherwise the last work to be released does.
      */
    protected final def beginClose(): (found: State, idle: Boolean) =
      var found: Int = UnenteredState
      var idle = false
      var done = false
      while !done do
        val current = word.get()
        found = stateOf(current)
        if found != OpenState then done = true
        else if word.compareAndSet(current, wordOf(ClosingState, claimedOf(current))) then
          idle = claimedOf(current) == 0
          done = true
      (found.asInstanceOf[State], idle)

trait BaseHandle extends HandleState.Holder:
  def open(): Unit
  def requestClose(): Boolean

  /** Counts the times the handle was reported readable, or writable: read before an attempt, for `awaitReadiness`. */
  def readinessTick(read: Boolean): Int

  /** Makes `op` the handle's waiter for reads, or for writes, if the handle accepts it: what a handle accepts is its
    * own to decide, and it refuses with an `IllegalArgumentException`. Returns the event source that listens for the
    * handle, which is `self` if none did, for `self` then to start listening; or null, with `op` not waiting, if the
    * handle was reported ready since `sinceTick`, for the performer to try again.
    */
  def awaitReadiness(read: Boolean, op: ReadinessOp, sinceTick: Int, self: EventSource): EventSource | Null

  /** Whether the handle is known to have nothing to read, or no room to write, as of `tick`, read with
    * `readinessTick` just before: an op then waits without trying first, since the try could only find that out
    * again. It is known from the last try that found it so, or from an op that took all a report was for, and only
    * while a listener would report anything new, which moves the tick on.
    */
  def drainedAt(read: Boolean, tick: Int): Boolean

  /** `source`, reporting the handle ready for so much, saw an op take all of it: the handle is drained until its
    * next report.
    */
  def markDrained(read: Boolean, source: EventSource): Unit

  /** Whether `op` was the handle's waiter, and no longer is. */
  def clearWaiter(read: Boolean, op: ReadinessOp): Boolean

  /** `source` reports the handle ready: its waiter, claimed with `claimAtReady` while still its waiter, or null if
    * there is none to claim. A handle reported ready time after time with no op waiting gives up its listener, which
    * it tells to `stopListening`: the next op that waits makes its own worker the listener.
    */
  def readyWaiter(read: Boolean, source: EventSource): ReadinessOp | Null

  /** Whether `source` is the handle's listener for reads, or for writes. */
  def listenedBy(read: Boolean, source: EventSource): Boolean

  /** Has `owner` told once the handle is closed, at once if it already is. For whoever's `requestClose` began the
    * close and returned false: a close has one owner, so one listener at most.
    */
  def onClosed(owner: CloseListener): Unit
  def claimWork(): Unit
  def releaseWork(): Unit

trait BaseReadableHandle extends BaseHandle
trait BaseWritableHandle extends BaseHandle

/** Told that a handle's close is done, by whichever thread finished it. */
trait CloseListener:
  def handleClosed(): Unit

object AbstractBaseHandle:
  private[shareable] case object Closed

abstract class AbstractBaseHandle extends BaseHandle:
  import HandleState.{ClosedState, ClosingState, OpenState, UnenteredState}

  opaque type ClosePermit = Unit

  /** does final cleanup of the handle and releases any associated resources. Returns true if the handle was closed,
    * else a listener is required.
    */
  def cleanup()(using ClosePermit): Boolean

  final private def closeHandle()(using ClosePermit): Boolean =
    failWaiter(read = true)
    failWaiter(read = false)
    forgetListener(read = true)
    forgetListener(read = false)
    val closedSync = cleanup()
    if closedSync then
      assert(getState == ClosedState)
      completeClose()
    closedSync

  /** Whoever waits for the close to be done: null until there is one, its owner while it waits, and `Closed` from
    * when the close is done, whichever comes first. The owner is told exactly once, by whichever of the two comes
    * second.
    */
  private val closeListener = AtomicReference[CloseListener | AbstractBaseHandle.Closed.type | Null](null)

  protected def completeClose(): Unit =
    closeListener.getAndSet(AbstractBaseHandle.Closed) match
      case owner: CloseListener => owner.handleClosed()
      case _                    => ()

  final def onClosed(owner: CloseListener): Unit =
    if !closeListener.compareAndSet(null, owner) then
      closeListener.get() match
        case AbstractBaseHandle.Closed => owner.handleClosed()
        case _ => throw new IllegalStateException("A close already has its owner waiting")

  /** The ops waiting for the handle to be readable, and to be writable. Guarded by this handle's monitor, which is
    * what makes a claim and a clear exclude each other (see `ReadinessOp.claimAtReady`).
    */
  private var readWaiter: ReadinessOp | Null = null
  private var writeWaiter: ReadinessOp | Null = null

  /** The event source listening for the handle to be readable, and to be writable: the first an op waited on, and
    * the same one for as long as ops go on waiting (see `idleReportsLimit`), so that only it is woken for the handle.
    * Guarded by this handle's monitor.
    */
  private var readListener: EventSource | Null = null
  private var writeListener: EventSource | Null = null

  /** Written under this handle's monitor. */
  @volatile private var readTick = 0
  @volatile private var writeTick = 0

  final def readinessTick(read: Boolean): Int = if read then readTick else writeTick

  /** Whether the handle accepts `op` as its waiter while `pending` is. By default one op waits in each direction, and
    * a second is refused; one that has settled, whose performer has yet to clear it, is not waiting, and gives way.
    */
  protected def acceptsWaiter(read: Boolean, op: ReadinessOp, pending: ReadinessOp | Null): Boolean =
    pending == null || pending.settled

  /** The tick at which the handle was last known drained, which is so now if it is still the tick: any other value,
    * as it is to begin with and when a listener goes, means it is not known. Written under this handle's monitor.
    */
  @volatile private var readDrainedTick = -1
  @volatile private var writeDrainedTick = -1

  final def drainedAt(read: Boolean, tick: Int): Boolean =
    (if read then readDrainedTick else writeDrainedTick) == tick

  /** No listener, so nothing would report the handle ready: whether it is drained is not known. */
  private def forgetDrained(read: Boolean): Unit =
    if read then readDrainedTick = readTick - 1 else writeDrainedTick = writeTick - 1

  final def markDrained(read: Boolean, source: EventSource): Unit = synchronized {
    if (if read then readListener else writeListener) eq source then
      if read then readDrainedTick = readTick else writeDrainedTick = writeTick
  }

  final def awaitReadiness(read: Boolean, op: ReadinessOp, sinceTick: Int, self: EventSource): EventSource | Null = {
    var start = false
    val listener = synchronized {
      if !acceptsWaiter(read, op, if read then readWaiter else writeWaiter) then
        throw new IllegalArgumentException(s"A ${if read then "read" else "write"} is already pending on this handle")
      // The listener may be another thread's, and have reported the handle ready, to no waiter, after the attempt.
      if readinessTick(read) != sinceTick then null
      else
        if read then readWaiter = op else writeWaiter = op
        var listener = if read then readListener else writeListener
        if listener == null then
          listener = self
          start = true
          if read then readListener = self else writeListener = self
        // The op waits because the handle was not ready at this tick: so the next need not try before the next.
        if read then readDrainedTick = sinceTick else writeDrainedTick = sinceTick
        listener
    }
    // Only as `self` becomes the listener: it then reports the handle until it stops being one. No report can come
    // before this, and a second op to wait meanwhile is refused, as this one is the waiter.
    if start then
      try self.startListening(this, read)
      catch
        case failure: Throwable =>
          synchronized {
            if read then { if readListener eq self then readListener = null }
            else if writeListener eq self then writeListener = null
            forgetDrained(read)
            clearWaiter(read, op): Unit
          }
          throw failure
    listener
  }

  final def clearWaiter(read: Boolean, op: ReadinessOp): Boolean = synchronized {
    val held = (if read then readWaiter else writeWaiter) eq op
    if held then if read then readWaiter = null else writeWaiter = null
    held
  }

  /** How many times in a row the handle is reported ready with no op waiting before it gives up its listener. Until
    * then an op that waits costs no system call; past it, the handle is taken to be one whose ops find it ready, for
    * which the reports only wake the listener for nothing, and tie the handle's next wait to a worker that may by
    * then be kept busy.
    */
  protected def idleReportsLimit: Int = 16

  /** Reports with no op waiting since one last was. Guarded by this handle's monitor. */
  private var readIdleReports = 0
  private var writeIdleReports = 0

  final def readyWaiter(read: Boolean, source: EventSource): ReadinessOp | Null = {
    var stop = false
    val claimed = synchronized {
      if read then readTick += 1 else writeTick += 1
      // A report from a source that has stopped listening, already on its way: counted above, for an op about to
      // wait, but the waiter is its new listener's to claim.
      if (if read then readListener else writeListener) ne source then null
      else
        val op = if read then readWaiter else writeWaiter
        if op == null then
          val idle = (if read then readIdleReports else writeIdleReports) + 1
          stop = idle >= idleReportsLimit
          if read then readIdleReports = if stop then 0 else idle
          else writeIdleReports = if stop then 0 else idle
          if stop then
            if read then readListener = null else writeListener = null
            forgetDrained(read)
          null
        else
          if read then readIdleReports = 0 else writeIdleReports = 0
          if op.claimAtReady() then op else null
    }
    if stop then source.stopListening(this, read)
    claimed
  }

  final def listenedBy(read: Boolean, source: EventSource): Boolean = synchronized {
    (if read then readListener else writeListener) eq source
  }

  /** First in a close, before anything is released: whoever waits fails. */
  private def failWaiter(read: Boolean): Unit =
    val op = synchronized(if read then readWaiter else writeWaiter)
    if op != null then op.handleClosing()

  /** Then the listeners drop the handle, while its descriptor is still its own. What they registered with the kernel
    * goes as the descriptor is closed.
    */
  private def forgetListener(read: Boolean): Unit =
    val listener = synchronized(if read then readListener else writeListener)
    if listener != null then listener.forgetHandle(this)

  final def open(): Unit =
    if compareAndSetState(UnenteredState, OpenState) then ()
    else throw new IllegalStateException("Handle is already open or closed")

  final def claimWork(): Unit =
    claimIfOpen() match
      case OpenState                  => ()
      case ClosedState | ClosingState => throw new ClosedChannelException()
      case UnenteredState             => throw new IOException("Handle is not open")

  /** Work claimed is released even once the handle is closing: the last of it then closes the handle. */
  final def releaseWork(): Unit =
    if releaseClaimed() then
      given ClosePermit = ()
      val _ = closeHandle()

  final def requestClose(): Boolean =
    val begun = beginClose()
    begun.found match
      case OpenState =>
        if begun.idle then
          given ClosePermit = ()
          closeHandle()
        else false // the last work released closes it
      // Closing is done once: a second close fails as any op on a closed handle does.
      case ClosingState | ClosedState => throw new ClosedChannelException()
      case UnenteredState             => throw new IllegalStateException("Handle is not open")
