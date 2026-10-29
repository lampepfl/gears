package gears.async.io.internal

import scala.util.Success

/** The Gears operation submitted to a poller. Its completion is the operation itself, so a backend does not allocate a
  * separate promise, waiter or callback for `Io.perform`. The poller acknowledges cancellation only after it has
  * stopped using the caller's buffers. The same operation may be reused after its previous consumer has returned.
  */
trait ReactorOperation extends AsyncOperation[Unit] with Completion[Any] {
  private[io] final def beginAwait(owner: ReactorOperation.Executor): Unit = begin(owner)

  private[io] final def endAwait(): Unit = end()

  protected final def requestCancellation(owner: SerialExecutor): Unit = {
    // Capture the poller from this cancellation's owner, never from mutable state of a later submission.
    val target = owner.asInstanceOf[ReactorOperation.Executor].reactor
    target.cancel(this.asInstanceOf[target.Op]): Unit
  }

  final def onComplete(op: Any): Unit = complete(ReactorOperation.Done)
  override final def onFailure(op: Any, failure: Throwable): Unit = failOperation(failure)
  override final def onCancel(op: Any): Unit = acknowledgeCancellation()
}

object ReactorOperation {

  /** The serial owner of submitted operations. Poller access is confined to this executor's thread. */
  trait Executor extends SerialExecutor {
    def reactor: Submitter
  }

  private val Done = Success(())
}
