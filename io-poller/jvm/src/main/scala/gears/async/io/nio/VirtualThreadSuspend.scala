package gears.async.io.nio

import gears.async.SuspendSupport

import java.util.concurrent.locks.LockSupport
import scala.annotation.unchecked.uncheckedVariance

/** A value one thread hands to another, which waits for it parked: a field and an unpark, with no lock. One thread
  * waits at a time. Whoever gives writes the value and then looks for a waiter; whoever waits says so and then looks
  * for the value: one of the two sees the other.
  */
private[nio] final class Handover:
  @volatile private var value: Any = Handover.Nothing
  @volatile private var waiter: Thread | Null = null

  def clear(): Unit = value = Handover.Nothing

  def give(data: Any): Unit =
    value = data
    val waiting = waiter
    if waiting != null then LockSupport.unpark(waiting)

  /** Parks until a value has been given, and returns it, leaving it there. An interrupt does not end the wait: it is
    * kept for the thread to find afterwards, as a park returns at once for as long as one is pending.
    */
  def take(): Any =
    var taken = value
    if taken.asInstanceOf[AnyRef] eq Handover.Nothing then
      var interrupted = false
      waiter = Thread.currentThread()
      try
        taken = value
        while taken.asInstanceOf[AnyRef] eq Handover.Nothing do
          LockSupport.park(this)
          if Thread.interrupted() then interrupted = true
          taken = value
      finally
        waiter = null
        if interrupted then Thread.currentThread().interrupt()
    taken

private[nio] object Handover:
  private val Nothing = new AnyRef

/** Suspension on virtual threads, as gears' `VThreadSupport` has it, for threads started however the scheduler mixing
  * this in starts them (`startThread`): a boundary's body runs on a thread of its own, and a suspension is that thread
  * parked until it is given its input.
  */
trait VirtualThreadSuspend extends SuspendSupport:

  /** Starts a virtual thread that runs `body`. */
  protected def startThread(body: Runnable): Unit

  final class ThreadLabel[R] private[VirtualThreadSuspend] ():
    private val result = new Handover

    private[VirtualThreadSuspend] def clearResult(): Unit = result.clear()

    private[VirtualThreadSuspend] def setResult(data: R): Unit = result.give(data)

    private[VirtualThreadSuspend] def waitResult(): R = result.take().asInstanceOf[R]

  type Label[R] = ThreadLabel[R]

  // Outside the boundary a thread waits on the label; inside it, on the suspension.
  final class ThreadSuspension[-T, +R] private[VirtualThreadSuspend] (
      private[VirtualThreadSuspend] val label: ThreadLabel[R] @uncheckedVariance
  ) extends gears.async.Suspension[T, R]:
    private val nextInput = new Handover

    private[VirtualThreadSuspend] def setInput(data: T): Unit = nextInput.give(data)

    // Safe: only the thread that made the suspension reads it.
    private[VirtualThreadSuspend] def waitInput(): T @uncheckedVariance = nextInput.take().asInstanceOf[T]

    override def resume(arg: T): R =
      label.clearResult()
      setInput(arg)
      label.waitResult()

  type Suspension[-T, +R] = ThreadSuspension[T, R]

  override def boundary[R](body: Label[R] ?=> R): R =
    val label = new ThreadLabel[R]()
    startThread { () =>
      val result = body(using label)
      label.setResult(result)
    }
    label.waitResult()

  override def suspend[T, R](body: Suspension[T, R] => R)(using label: Label[R]): T =
    val suspension = new ThreadSuspension[T, R](label)
    val result = body(suspension)
    label.setResult(result)
    suspension.waitInput()

  /** Wakes the suspended thread with its input, and returns: the thread goes on wherever its scheduler runs it. */
  protected final def wake[T, R](suspension: Suspension[T, R], arg: T): Unit =
    suspension.label.clearResult()
    suspension.setInput(arg)

  /** A boundary on a thread of its own, which nothing waits for. */
  protected final def startBoundary(body: Label[Unit] ?=> Unit): Unit =
    startThread(() => body(using new ThreadLabel[Unit]()))
