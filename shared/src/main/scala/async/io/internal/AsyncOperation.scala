package gears.async.io.internal

import gears.async.{Async, Cancellable, CompletionGroup, Listener}

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import scala.util.{Failure, Success, Try}

/** A reusable, single-consumer asynchronous operation with acknowledged cancellation.
  *
  * The operation itself is its source and cancellation-group member. Cancelling requests that its owner stop it; it
  * does not release the awaiting computation until the producer completes it, after releasing any borrowed resources.
  * Implementations call `begin` before starting work, `complete` when work stops, and `end` only after the consumer has
  * returned. These lifecycle calls must be serialized; `cancel`, completion and listener registration may race. A new
  * generation must not start until all producers of the previous one have stopped.
  *
  * Unlike a Future, this source may be reset, and supports only one listener at a time. It avoids listener sets,
  * per-submission promises and cancellation closures. A late cancellation queued on a former owner cannot affect the
  * next generation. Groups snapshot the cancellation's generation before releasing their membership lock, so a delayed
  * group cancellation cannot target a later registration either. It is intended for runtime and backend
  * implementations, not shared result storage.
  */
trait AsyncOperation[T] extends Async.Source[Try[T]] with CancellationSnapshot {
  private val active = new AtomicBoolean()
  private val state = new AtomicReference[AnyRef](AsyncOperation.Empty)
  @volatile private var owner: SerialExecutor | Null = null
  @volatile private var generation = 0L

  /** Claims this operation for a new use, refusing overlapping consumers. */
  protected final def begin(executor: SerialExecutor): Unit = {
    if !active.compareAndSet(false, true) then throw new IllegalStateException("Operation already in use")
    if owner ne executor then owner = executor
    generation += 1
    state.set(AsyncOperation.Empty)
  }

  /** Releases the consumer's use. The producer must have stopped before this is called. */
  protected final def end(): Unit = {
    active.set(false)
  }

  /** Runs on the captured owning executor. Use this owner, rather than mutable state naming a later generation's owner,
    * to stop the producer. Must eventually acknowledge cancellation by completing the operation.
    */
  protected def requestCancellation(owner: SerialExecutor): Unit

  final def cancel(): Unit = {
    val epoch = generation
    val target = owner
    if target != null then
      target.dispatch {
        cancelGeneration(epoch, target)
      }
  }

  private def cancelGeneration(epoch: Long, target: SerialExecutor): Unit = {
    if active.get() && !isCompleted && epoch == generation then requestCancellation(target)
  }

  // The group holds its membership lock here: unlink must finish before this operation can begin another use.
  // The snapshot doubles as the inbox task, avoiding a second closure when cancellation crosses workers.
  private[async] final def snapshotCancellation(): Cancellable = new Cancellation(generation, owner)

  private final class Cancellation(epoch: Long, target: SerialExecutor | Null) extends Cancellable with Runnable {
    def cancel(): Unit = if target != null then {
      if target.isCurrent then run() else target.execute(this)
    }
    def run(): Unit = if target != null then cancelGeneration(epoch, target)
  }

  protected final def isCompleted: Boolean = state.get().isInstanceOf[Try[?]]

  /** Publishes the result once. Duplicate completions in the same generation are ignored. */
  protected final def complete(result: Try[T]): Unit = {
    var previous = state.get()
    while !previous.isInstanceOf[Try[?]] do {
      if state.compareAndSet(previous, result) then {
        previous match {
          case listener: Listener[Try[T]] @unchecked => listener.completeNow(result, this)
          case _                                     => ()
        }
        return
      }
      previous = state.get()
    }
  }

  protected final def succeed(value: T): Unit = complete(Success(value))
  protected final def failOperation(error: Throwable): Unit = complete(Failure(error))
  protected final def acknowledgeCancellation(): Unit = failOperation(new AsyncOperation.Cancelled)

  /** Links the operation itself to the caller's group, and waits until completion, including cancellation cleanup. Call
    * only once per use. A cancellation that preceded registration is delivered by `CompletionGroup.add`.
    */
  final def awaitCompletion()(using async: Async): T = {
    link()
    try async.withGroup(CompletionGroup.Unlinked).await(this).get
    finally unlink()
  }

  override final def poll(): Option[Try[T]] = state.get() match {
    case result: Try[T] @unchecked => Some(result)
    case _                         => None
  }

  final def poll(listener: Listener[Try[T]]): Boolean = state.get() match {
    case result: Try[T] @unchecked =>
      listener.completeNow(result, this)
      true
    case _ => false
  }

  final def onComplete(listener: Listener[Try[T]]): Unit = {
    var registered = false
    while !registered do
      state.get() match {
        case result: Try[T] @unchecked =>
          listener.completeNow(result, this)
          registered = true
        case AsyncOperation.Empty             => registered = state.compareAndSet(AsyncOperation.Empty, listener)
        case existing if existing eq listener => registered = true
        case _ => throw new IllegalStateException("An AsyncOperation has only one consumer")
      }
  }

  final def dropListener(listener: Listener[Try[T]]): Unit = state.compareAndSet(listener, AsyncOperation.Empty): Unit
}

object AsyncOperation {
  private object Empty

  // The useful stack is the consumer's, not the poller's. Cancellation-heavy workloads must not unwind a stack
  // for every pending operation in a group.
  private final class Cancelled extends CancellationException("The operation was cancelled") {
    override def fillInStackTrace(): Throwable = this
  }
}
