package gears.async.io
package internal

/** What to do when an op finishes. The op comes back with its outcome, so one completion can own many ops. A completion
  * that submits further ops holds on to its submitter itself, since only a reference to that submitter has the right
  * `Op` type.
  */
@FunctionalInterface
trait Completion[-C] {

  /** The op finished. What finishing means for each kind of op is described in `Ops`; anything it produced is in the
    * caller's buffers and slots.
    */
  def onComplete(op: C): Unit

  /** The op failed with an exception instead of finishing. By default the failure is thrown, which a reactor reports as
    * it reports any completion that throws (`Reactor.reportFailure`).
    */
  def onFailure(op: C, failure: Throwable): Unit = throw failure

  /** The op was cancelled before it finished, by `Submitter.cancel` or because the reactor closed. It runs on the
    * submitter's thread once the submitter has stopped using the op's buffers and slots, which are the caller's again,
    * and afterwards neither `onComplete` nor `onFailure` runs for this submission. It must not submit ops while the
    * reactor is closing. By default it does nothing.
    */
  def onCancel(op: C): Unit = ()
}

/** What a reactor reports when a completion throws, with the op whose completion it was and what it threw: an exception
  * a completion lets out is a bug in the completion, not an outcome of the op, which had its outcome delivered. A
  * completion that handles nothing of a failure, so the default `onFailure` threw it, is reported the same way.
  */
final class CompletionFailed(val op: Any, cause: Throwable)
    extends RuntimeException(s"The completion of $op threw: $cause", cause)

/** Where ops are submitted with completions, and whose thread runs the completions: a `Reactor`, whose loop the caller
  * drives, or, for a backend with no loop of its own, such as one over a JavaScript host, the host's loop. Apart from
  * what says otherwise, a submitter is used from its own thread only.
  */
trait Submitter {

  /** Something submitted here that later completes. The reactors of one `ReactorBackend` share it, so an op built by
    * the backend may go to any of them; otherwise the type belongs to the submitter value: given `a, b: Submitter`,
    * `a.Op` and `b.Op` are different types.
    */
  type Op

  /** The handles its ops name, its backend's (see `Backend.Handle`). */
  type Handle <: AnyRef

  /** Builds this submitter's ops, as its backend's `ops` does. Building an op does not touch the submitter, so `ops`
    * may be used from any thread.
    */
  def ops: Ops[Op, Handle]

  /** Submits an op with the completion to run when it finishes, and no time limit (see the other `submit`). */
  final def submit[C <: Op](op: C, completion: Completion[C]): Unit = submit(op, completion, Backend.NoTimeout)

  /** Submits an op with the completion to run when it finishes, and how long it may take. An op marked `Repeatable` may
    * be submitted again once its previous submission has completed, failed, or been cancelled; any other op may be
    * submitted only once. No op may be pending twice at the same time. An op may finish during `submit`, such as a read
    * of data already waiting, but its completion never runs inside `submit`: it runs from a later turn of the loop. Nor
    * need the op be issued to the I/O facility inside `submit`: a submitter may hold the ops submitted in a turn and
    * issue them together at the turn's end, as io_uring's submission queue or Network.framework's batches invite, so
    * long as `trySubmit` issues at once and a turn issues everything before it blocks.
    *
    * Unless `timeoutMillis` is negative (`Backend.NoTimeout`), an op not finished that many milliseconds after its
    * submission is stopped, as `cancel` stops it, and fails with `java.util.concurrent.TimeoutException`: its
    * completion gets `onFailure` with that, from the loop, once the submitter has stopped using its buffers and slots,
    * which for an op running elsewhere, such as a blocking op that has to be interrupted, is later. An op that waits
    * for its handle to be ready before it touches anything, as the reads and writes of a readiness-based submitter do,
    * has then done nothing: its buffer is as it was and its handle as usable as before, so a read that timed out is a
    * wait that gave up, not a connection lost. This is how a read is given a timeout: no timer to race it against, and
    * no cancel to confirm. A timer whose own time is longer times out the same way. Zero gives the op until the turn
    * after its submission, as a timer of no time has.
    */
  def submit[C <: Op](op: C, completion: Completion[C], timeoutMillis: Int): Unit

  /** `op`'s attachment: the caller's own data, kept with the op across submissions, so that any reactor of the backend
    * returns it, and never used by the submitter itself.
    */
  def attachment(op: Op): AnyRef | Null

  /** Sets `op`'s attachment (see `attachment`). */
  def attach(op: Op, value: AnyRef | Null): Unit

  /** `trySubmit` with no time limit. */
  final def trySubmit[C <: Op](op: C, completion: Completion[C]): Boolean =
    trySubmit(op, completion, Backend.NoTimeout)

  /** Like `submit`, but an op the submitter can finish at once, as it may for an operation that only reports
    * would-block when not ready, it finishes here: it returns true without ever calling `completion`, and the op's
    * results are already in the caller's buffers and slots; a failure is thrown. Otherwise the op is submitted as by
    * `submit`, with the same time limit, `completion` runs when it finishes, and this returns false. A caller can then
    * continue at once instead of waiting for a later turn, but must take care to let other work run if its ops keep
    * finishing at once.
    */
  def trySubmit[C <: Op](op: C, completion: Completion[C], timeoutMillis: Int): Boolean

  /** Cancels a submitted op that has not finished, and returns true: its completion's `onComplete` and `onFailure` will
    * never run, and its `onCancel` runs once the submitter has stopped using the op's buffers and slots. That is before
    * this returns for most ops, but later, from the loop, for one still running elsewhere, such as a blocking op that
    * has to be interrupted. Returns false if the op already finished or was never submitted here.
    */
  def cancel(op: Op): Boolean
}

object Submitter {

  /** A submitter that runs ops of type `O`: one of a backend whose `Op` is `O`. */
  type Of[O] = Submitter { type Op = O }
}

/** A single-threaded event loop: a `Submitter` whose loop the caller drives with `runOnce` or `run`. Every completion
  * runs on the thread that drives the loop, and apart from `wakeup`, a reactor is only used from that thread. A
  * completion that throws does not end the turn, nor escape `cancel` or `close`: the reactor catches it, as other loops
  * do (Netty, Vert.x, `ExecutionContext.reportFailure`), delivers the rest, and reports it to `reportFailure` as a
  * `CompletionFailed`; it is never delivered again as the op's failure, since the op did not fail. The other
  * completions of the turn would otherwise be lost: a readiness event consumed by the poll is not reported again.
  */
trait Reactor extends Submitter with AutoCloseable {

  /** Where a completion that throws is reported (see `CompletionFailed`): by default, its stack trace to `System.err`.
    * Set it from the thread that drives the loop.
    */
  var reportFailure: Throwable => Unit = Reactor.printFailure

  /** Runs `completion.onComplete`, reporting what it throws instead of throwing it. For backends. */
  protected final def deliverComplete[C <: Op](completion: Completion[C], op: C): Unit =
    try completion.onComplete(op)
    catch case t: Throwable => reportFailure(new CompletionFailed(op, t))

  /** Runs `completion.onFailure`, reporting what it throws instead of throwing it: the failure itself, if the
    * completion handles none, as the default does. For backends.
    */
  protected final def deliverFailure[C <: Op](completion: Completion[C], op: C, failure: Throwable): Unit =
    try completion.onFailure(op, failure)
    catch case t: Throwable => reportFailure(new CompletionFailed(op, t))

  /** Runs `completion.onCancel`, reporting what it throws instead of throwing it. For backends. */
  protected final def deliverCancel[C <: Op](completion: Completion[C], op: C): Unit =
    try completion.onCancel(op)
    catch case t: Throwable => reportFailure(new CompletionFailed(op, t))

  /** One turn of the loop: runs the completions of everything that is ready now, and returns. With `block`, if nothing
    * was ready it first waits until something is, or until `wakeup` is called. A scheduler drives the loop with this,
    * running its own tasks between turns and blocking only when it has none.
    */
  def runOnce(block: Boolean): Unit

  /** Makes a blocked `runOnce` return, or the next one not block; it may be called from any thread. A scheduler calls
    * it when another thread gives it a task. Wake-ups requested before the loop gets to them may be merged into one.
    */
  def wakeup(): Unit

  /** Runs turns, blocking between them, until `stop` is called. */
  def run(): Unit

  /** Makes `run` return once the current turn finishes. */
  def stop(): Unit

  /** Releases the reactor's own resources. Every pending op is cancelled, so its completion gets `onCancel` and nothing
    * else; ops still running elsewhere, such as blocking ops, are waited for first. Handles named by ops belong to the
    * caller and stay open (closing one instead fails the ops pending on it: see `Ops.close`). Call it when `run` is not
    * running; closing twice does nothing.
    */
  def close(): Unit
}

object Reactor {

  /** The default `reportFailure`: the stack trace to `System.err`. */
  val printFailure: Throwable => Unit = _.printStackTrace()

  /** A reactor that runs ops of type `O` on handles of type `H`: one that a backend whose `Op` is `O` and whose
    * `Handle` is `H` opened.
    */
  type Of[O, H <: AnyRef] = Reactor { type Op = O; type Handle = H }

  /** Opens reactors of kind `R`. Code written against the interface asks for one of these instead of naming an
    * implementation, and states what it needs of the reactor through `R`.
    */
  @FunctionalInterface
  trait Factory[+R <: Reactor] {
    def open(): R
  }

  /** Opens a reactor for `body` and closes it afterwards. The reactor's type comes from the given factory. */
  def scoped[R <: Reactor](using factory: Factory[R])[A](body: R => A): A = {
    val reactor = factory.open()
    try body(reactor)
    finally reactor.close()
  }
}
