package gears.async

import java.util.concurrent.ThreadFactory
import java.util.concurrent.locks.ReentrantLock
import scala.annotation.unchecked.uncheckedVariance

/** `VThreadSupport`, with the virtual threads made by `factory` instead of gears' own, and `S` as its scheduler: so
  * that the threads can run on a scheduler of the caller's, such as a reactor pool, which `factory`'s threads are
  * carried by. In gears' package, since the hooks it overrides are gears' own.
  */
final class FactoryVThreadSupport[S <: Scheduler](factory: ThreadFactory) extends AsyncSupport:

  type Scheduler = S

  private def start(body: Runnable): Unit = factory.newThread(body).start()

  /** Where a boundary's body leaves its result each time it suspends or finishes. Results are counted, so a caller
    * waiting for the next one waits for a result newer than the one it saw before resuming, rather than for one not yet
    * cleared: with the clear-then-wait `VThreadSupport` once had, a `resumeAsync` clearing the result before a waiting
    * `resume` had looked left that `resume` waiting on, woken by every later suspension of the body for nothing.
    */
  private[async] final class FactoryLabel[R]():
    private var result: Option[R] = None
    private var results = 0L // how many times a result has been set
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    /** How many results have been set so far, to wait for a newer one with `waitResult`. */
    def seen(): Long =
      lock.lock()
      try results
      finally lock.unlock()

    def setResult(data: R) =
      lock.lock()
      try
        result = Some(data)
        results += 1
        cond.signalAll()
      finally lock.unlock()

    /** The first result set after `after` results. */
    def waitResult(after: Long): R =
      lock.lock()
      try
        while results <= after do cond.await()
        result.get
      finally lock.unlock()

  override type Label[R] = FactoryLabel[R]

  private[async] final class FactorySuspension[-T, +R](using val l: Label[R] @uncheckedVariance)
      extends gears.async.Suspension[T, R]:
    private var nextInput: Option[T] = None
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    def setInput(data: T) =
      lock.lock()
      try
        nextInput = Some(data)
        cond.signalAll()
      finally lock.unlock()

    def waitInput(): T @uncheckedVariance =
      lock.lock()
      try
        while nextInput.isEmpty do cond.await()
        nextInput.get
      finally lock.unlock()

    override def resume(arg: T): R =
      val after = l.seen()
      setInput(arg)
      l.waitResult(after)

  override type Suspension[-T, +R] = FactorySuspension[T, R]

  override def boundary[R](body: (Label[R]) ?=> R): R =
    val label = FactoryLabel[R]()
    start: () =>
      val result = body(using label)
      label.setResult(result)
    label.waitResult(after = 0)

  override private[async] def resumeAsync[T, R](suspension: Suspension[T, R])(arg: T)(using Scheduler): Unit =
    suspension.setInput(arg)

  override def scheduleBoundary(body: (Label[Unit]) ?=> Unit)(using Scheduler): Unit =
    start: () =>
      val label = FactoryLabel[Unit]()
      body(using label)
      label.setResult(()) // releases a `resume` waiting for the body's next result, which there is none of

  override def suspend[T, R](body: Suspension[T, R] => R)(using l: Label[R]): T =
    val sus = new FactorySuspension[T, R]()
    val res = body(sus)
    l.setResult(res)
    sus.waitInput()
