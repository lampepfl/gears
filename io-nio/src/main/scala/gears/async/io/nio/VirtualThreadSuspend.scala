package gears.async.io.nio

import gears.async.SuspendSupport

import java.util.concurrent.locks.ReentrantLock
import scala.annotation.unchecked.uncheckedVariance

/** Suspension on virtual threads, as gears' `VThreadSupport` has it, for threads started however the scheduler mixing
  * this in starts them (`startThread`): a boundary's body runs on a thread of its own, and a suspension is that thread
  * parked until it is given its input.
  */
trait VirtualThreadSuspend extends SuspendSupport:

  /** Starts a virtual thread that runs `body`. */
  protected def startThread(body: Runnable): Unit

  final class ThreadLabel[R] private[VirtualThreadSuspend] ():
    private var result: Option[R] = None
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    private[VirtualThreadSuspend] def clearResult(): Unit =
      lock.lock()
      result = None
      lock.unlock()

    private[VirtualThreadSuspend] def setResult(data: R): Unit =
      lock.lock()
      try
        result = Some(data)
        cond.signalAll()
      finally lock.unlock()

    private[VirtualThreadSuspend] def waitResult(): R =
      lock.lock()
      try
        while result.isEmpty do cond.await()
        result.get
      finally lock.unlock()

  type Label[R] = ThreadLabel[R]

  // Outside the boundary a thread waits on the label; inside it, on the suspension.
  final class ThreadSuspension[-T, +R] private[VirtualThreadSuspend] (
      private[VirtualThreadSuspend] val label: ThreadLabel[R] @uncheckedVariance
  ) extends gears.async.Suspension[T, R]:
    private var nextInput: Option[T] @uncheckedVariance = None
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    private[VirtualThreadSuspend] def setInput(data: T): Unit =
      lock.lock()
      try
        nextInput = Some(data)
        cond.signalAll()
      finally lock.unlock()

    // Safe: only the thread that made the suspension reads it.
    private[VirtualThreadSuspend] def waitInput(): T @uncheckedVariance =
      lock.lock()
      try
        while nextInput.isEmpty do cond.await()
        nextInput.get
      finally lock.unlock()

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
