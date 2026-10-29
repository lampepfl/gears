package gears.async.io
package internal

import gears.async.io.Repeatable

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** What every op of a loop backend's reactor keeps, kqueue's and NIO's alike: `O` is the backend's sealed op type,
  * which extends this, so that an op is its own record while pending and submitting it allocates nothing. An op is in
  * its reactor's timer heap while it is a pending timer, or a pending op with a time limit (`TimerHeap.Entry`).
  */
trait LoopOp[O <: LoopOp[O]] extends TimerHeap.Entry with ReactorOperation { this: O =>

  // Whether this op has been submitted before, so a one-shot op is run once though submitted on two threads at once.
  private val submissions = new AtomicBoolean()

  /** Records a submission, refusing a one-shot op submitted before with `IllegalStateException`. */
  private[io] final def markSubmitted(): Unit =
    if submissions.getAndSet(true) && oneShot then
      throw new IllegalStateException(s"$this is not Repeatable and was already submitted")

  /** While the op is pending in the reactor: its completion, so the op is its own record and submitting it allocates
    * nothing. Taken off just before the completion runs, which may then submit the op again. Loop thread only.
    */
  private[io] var completion: Completion[O] | Null = null

  /** For an op that failed as soon as it was submitted, or a command whose handle was closed while it waited: its
    * failure, until its completion runs.
    */
  private[io] var atOnceFailure: Throwable | Null = null

  /** The failure of an op submitted with a slot still holding a value (`Checks.notEmpty`), which fails it at once,
    * before it does anything; null if its slots are empty.
    */
  private[io] def slotRefusal: Throwable | Null = null

  /** Why the op cannot be performed as it stands, such as a read into a buffer with no room, or null: then it fails as
    * it is submitted, after `slotRefusal` and before its handle's turn is looked at, and is never performed, on a
    * reactor and on a caller backend alike, so that no backend checks it again each time it performs the op.
    */
  private[io] def refusal: Throwable | Null = null

  /** The turn this op holds on its handle while pending (`Handle.reader` or `writer`); null for one that takes none.
    */
  private[io] def claim: Turns | Null = null

  /** Whether, finding its handle's turn held, it waits for it rather than failing (see `Turns`): a datagram socket's
    * receive or send, or a listener's accept.
    */
  private[io] def waitsItsTurn: Boolean = false

  /** Whether it may be submitted only once: an op that is not `Repeatable`, or a command that opens or closes. */
  private[io] def oneShot: Boolean = !this.isInstanceOf[Repeatable]

  /** Whether it closes a handle: then however it finishes, at once, from another thread or notified, its completion
    * runs at the end of a later turn than the one it finished in (`ReactorCore.closing`), after that turn has failed
    * the ops pending on the handle there, so that a close completes after the failures it causes (`Ops.close`).
    */
  private[io] def closes: Boolean = false

  /** The caller's own data, kept across submissions and never read by the reactor (`Reactor.attachment`). */
  private[io] var attachment: AnyRef | Null = null
}

/** An op the reactor performs as soon as it is submitted, with no handle to wait for: its completion runs on the next
  * turn, with the failure `perform` threw, if any. One-shot.
  */
abstract class LoopAtOnce[O <: LoopOp[O]] extends LoopOp[O] { this: O =>
  def perform(): Unit
}

/** An op that opens handles and writes them into slots (see `Ops`): if its submission is withdrawn, by `cancel`, a time
  * limit or the reactor closing, before its completion has run, `abandon` closes what it opened and empties its slots,
  * so that nothing is left open that the caller never got (`ReactorCore.abandon`). Each backend says how a handle it
  * opened is opened and closed again (`open`, `discard`); what is kept, and when it is let go, is the same.
  */
trait LoopOpens[O <: LoopOp[O]] extends LoopOp[O] { this: O =>
  def abandon(): Unit
}

/** An op that opens one handle at once, when submitted (`LoopAtOnce`), as a listener, a datagram socket or a FIFO is,
  * and writes it into `into`, which must be empty.
  */
trait LoopOpensAtOnce[O <: LoopOp[O], K <: AnyRef](into: Slot[K]) extends LoopOpens[O] { this: O & LoopAtOnce[O] =>
  override private[io] def slotRefusal: Throwable | Null = Checks.notEmpty(into)
  private var opened: K | Null = null

  /** Opens the handle; a failure fails the op. */
  protected def open(): K

  /** Closes `handle`, opened here, which never reached the caller. */
  protected def discard(handle: K): Unit

  def perform(): Unit = {
    val handle = open()
    opened = handle
    into.set(handle)
  }

  def abandon(): Unit = if opened != null then {
    into.clear(): Unit
    discard(opened.nn)
    opened = null
  }
}

/** An op that opens a pipe at once, when submitted (`LoopAtOnce`), its read end written into `read` and its write end
  * into `write`, both of which must be empty (see `PipeOps.pipe`).
  */
trait LoopOpensPipe[O <: LoopOp[O], R <: AnyRef, W <: AnyRef](read: Slot[R], write: Slot[W]) extends LoopOpens[O] {
  this: O & LoopAtOnce[O] =>
  override private[io] def slotRefusal: Throwable | Null = Checks.notEmpty(read, write)
  private var readEnd: R | Null = null
  private var writeEnd: W | Null = null

  /** Opens the pipe: its read end and its write end; a failure fails the op. */
  protected def open(): (R, W)

  /** Closes `end`, one end of a pipe opened here, which never reached the caller. */
  protected def discard(end: R | W): Unit

  def perform(): Unit = {
    val (in, out) = open()
    readEnd = in
    writeEnd = out
    read.set(in)
    write.set(out)
  }

  def abandon(): Unit = if readEnd != null then {
    read.clear(): Unit
    write.clear(): Unit
    discard(readEnd.nn)
    discard(writeEnd.nn)
    readEnd = null
    writeEnd = null
  }
}

/** A connect (see `IpStreamOps.connect`): its socket is opened, and its non-blocking connect started, as it is
  * submitted (`start`, from `ReactorCore.started` or a caller backend), after which it waits as a command does; once
  * connected (`finished`), the socket is written into `into`, which must be empty. If the connect fails, or the
  * submission is withdrawn first, `abandon` closes the socket and leaves `into` empty. One-shot, as it opens.
  */
trait LoopConnects[O <: LoopOp[O], K <: AnyRef](into: Slot[K]) extends LoopOpens[O] { this: O =>
  override private[io] def oneShot: Boolean = true
  override private[io] def slotRefusal: Throwable | Null = Checks.notEmpty(into)

  /** The socket the submission opened, until abandoned. */
  protected var socket: K | Null = null
  private var connected = false

  /** Opens the socket and starts connecting it; a failure fails the op. */
  protected def open(): K

  /** Closes `socket`, opened here, which never reached the caller. */
  protected def discard(socket: K): Unit

  /** Opens the socket and starts connecting it, as the op is submitted. */
  private[io] final def start(): Unit = {
    socket = open()
    connected = false
  }

  /** The connect is done: the socket goes into `into`. */
  protected final def finished(): Unit = {
    connected = true
    into.set(socket.nn)
  }

  def abandon(): Unit = if socket != null then {
    if connected then into.clear(): Unit
    discard(socket.nn)
    socket = null
    connected = false
  }
}

/** A one-shot timer, kept in its reactor's heap while pending (`TimerState`). */
abstract class LoopTimer[O <: LoopOp[O]] extends LoopOp[O] with Repeatable with TimerState { this: O => }

/** The reactor's side of a promise: completed from any thread, after which the reactor runs its completion on the loop
  * thread. A blocking op (`LoopBlocking`) completes itself from the blocker pool.
  */
abstract class LoopPromise[O <: LoopOp[O]] extends LoopOp[O] { this: O =>
  // `Unsettled` until the first completion or failure wins the compare-and-set; later ones are refused.
  private val outcome = new AtomicReference[Any](LoopPromise.Unsettled)
  @volatile private[io] var reactor: LoopPromise.Owner[O] | Null = null

  private[io] def failure: Throwable | Null = outcome.get() match {
    case LoopPromise.Failed(failure) => failure
    case _                           => null
  }

  /** Completes the promise with a failure instead. */
  def fail(failure: Throwable): Unit = settle(LoopPromise.Failed(failure))

  /** Forgets the previous submission's outcome, for a `Repeatable` promise submitted again. Loop thread only, before
    * the submission can complete.
    */
  private[io] def reset(): Unit = outcome.set(LoopPromise.Unsettled)

  /** Tells the reactor this promise has finished, without a value. */
  private[io] def signal(): Unit = settle(())

  /** Records the outcome, if this is the first, and tells the reactor this promise has finished. */
  protected final def settle(value: Any): Unit = {
    val owner = reactor
    if owner == null then throw new IllegalStateException("A promise must be submitted before it is completed")
    if !outcome.compareAndSet(LoopPromise.Unsettled, value) then
      throw new IllegalStateException("A promise can only be completed once")
    owner.resolved(this)
  }
}

object LoopPromise {

  /** The reactor a promise was submitted to, told once it has its outcome, from any thread. */
  trait Owner[O <: LoopOp[O]] {
    private[io] def resolved(promise: O & LoopPromise[O]): Unit
  }

  private[io] object Unsettled
  private[io] final case class Failed(failure: Throwable)
}

/** A promise the reactor completes by running `block` on the blocker pool (`Blockers`), with `BlockingRun`'s
  * cancellation: skipped if not started, interrupted if running, and `onCancel` waits for `block` to return. An
  * exception thrown by `block` is the op's failure. One marked `Repeatable`, such as a file op, is restarted on each
  * submission.
  */
trait LoopBlocking[O <: LoopOp[O]] extends LoopPromise[O] with BlockingRun { this: O =>

  /** The cancelled submission whose `onCancel` waits for `block` to return, or whose `onFailure` does, with
    * `cancelledBy`, for one whose time limit ran out. Loop thread only.
    */
  private[io] var cancelling: Completion[O] | Null = null
  private[io] var cancelledBy: Throwable | Null = null

  protected def settled(failure: Throwable | Null): Unit =
    if failure != null then fail(failure) else signal()
}
