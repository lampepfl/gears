package gears.async

import java.lang.invoke.{MethodHandles, MethodType}
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.{Executor, ForkJoinPool, ForkJoinWorkerThread, ThreadFactory}
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

object VThreadScheduler extends Scheduler:
  private val LOOKUP: MethodHandles.Lookup = MethodHandles.lookup()

  private object CarrierThreadFactory extends ForkJoinPool.ForkJoinWorkerThreadFactory {
    private val clazz = LOOKUP.findClass("jdk.internal.misc.CarrierThread")
    private val constructor = LOOKUP.findConstructor(clazz, MethodType.methodType(classOf[Unit], classOf[ForkJoinPool]))
    private val counter = new AtomicLong(0L)

    override def newThread(pool: ForkJoinPool): ForkJoinWorkerThread = {
      val t = constructor.invoke(pool).asInstanceOf[ForkJoinWorkerThread]
      t.setName("gears-CarrierThread-" + counter.getAndIncrement())
      t
    }
  }

  private val DEFAULT_SCHEDULER: ForkJoinPool = {
    val parallelismValue = sys.props
      .get("gears.default-scheduler.parallelism")
      .map(_.toInt)
      .getOrElse(Runtime.getRuntime.availableProcessors())

    val maxPoolSizeValue = sys.props
      .get("gears.default-scheduler.max-pool-size")
      .map(_.toInt)
      .getOrElse(256)

    val minRunnableValue = sys.props
      .get("gears.default-scheduler.min-runnable")
      .map(_.toInt)
      .getOrElse(parallelismValue / 2)

    new ForkJoinPool(
      parallelismValue,
      CarrierThreadFactory,
      (t: Thread, e: Throwable) => {
        // noop for now
      },
      true,
      0,
      maxPoolSizeValue,
      minRunnableValue,
      (pool: ForkJoinPool) => true,
      60,
      SECONDS
    )
  }

  private val VTFactory = createVirtualThreadFactory("gears", DEFAULT_SCHEDULER)

  /** Create a virtual thread factory with an executor, the executor will be used as the scheduler of virtual thread.
    *
    * The executor should run task on platform threads.
    *
    * returns null if not supported.
    */
  private def createVirtualThreadFactory(prefix: String, executor: Executor): ThreadFactory =
    try {
      val builderClass = ClassLoader.getSystemClassLoader.loadClass("java.lang.Thread$Builder")
      val ofVirtualClass = ClassLoader.getSystemClassLoader.loadClass("java.lang.Thread$Builder$OfVirtual")
      val ofVirtualMethod = LOOKUP.findStatic(classOf[Thread], "ofVirtual", MethodType.methodType(ofVirtualClass))
      var builder = ofVirtualMethod.invoke()
      if (executor != null) {
        val clazz = builder.getClass
        val privateLookup = MethodHandles.privateLookupIn(
          clazz,
          LOOKUP
        )
        val schedulerFieldSetter = privateLookup
          .findSetter(clazz, "scheduler", classOf[Executor])
        schedulerFieldSetter.invoke(builder, executor)
      }
      val nameMethod = LOOKUP.findVirtual(
        ofVirtualClass,
        "name",
        MethodType.methodType(ofVirtualClass, classOf[String], classOf[Long])
      )
      val factoryMethod = LOOKUP.findVirtual(builderClass, "factory", MethodType.methodType(classOf[ThreadFactory]))
      builder = nameMethod.invoke(builder, prefix + "-virtual-thread-", 0L)
      factoryMethod.invoke(builder).asInstanceOf[ThreadFactory]
    } catch {
      case NonFatal(e) =>
        // --add-opens java.base/java.lang=ALL-UNNAMED
        throw new UnsupportedOperationException("Failed to create virtual thread factory.", e)
    }

  override def execute(body: Runnable): Unit =
    val th = VTFactory.newThread(body)
    th.start()

  override def schedule(delay: FiniteDuration, body: Runnable): Cancellable = ScheduledRunnable(delay, body)

  private class ScheduledRunnable(val delay: FiniteDuration, val body: Runnable) extends Cancellable {
    @volatile var interruptGuard = true // to avoid interrupting the body

    val th = VTFactory.newThread: () =>
      try Thread.sleep(delay.toMillis)
      catch case e: InterruptedException => () /* we got cancelled, don't propagate */
      if ScheduledRunnable.interruptGuardVar.getAndSet(this, false) then body.run()
    th.start()

    final override def cancel(): Unit =
      if ScheduledRunnable.interruptGuardVar.getAndSet(this, false) then th.interrupt()
  }

  private object ScheduledRunnable:
    val interruptGuardVar = LOOKUP
      .in(classOf[ScheduledRunnable])
      .findVarHandle(classOf[ScheduledRunnable], "interruptGuard", classOf[Boolean])

object VThreadSupport extends AsyncSupport:

  type Scheduler = VThreadScheduler.type

  private final class VThreadLabel[R]():
    private var result: Option[R] = None
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    private[VThreadSupport] def clearResult() =
      lock.lock()
      result = None
      lock.unlock()

    private[VThreadSupport] def setResult(data: R) =
      lock.lock()
      try
        result = Some(data)
        cond.signalAll()
      finally lock.unlock()

    private[VThreadSupport] def waitResult(): R =
      lock.lock()
      try
        while result.isEmpty do cond.await()
        result.get
      finally lock.unlock()

  override opaque type Label[R] = VThreadLabel[R]

  // outside boundary: waiting on label
  //  inside boundary: waiting on suspension
  private final class VThreadSuspension[-T, +R](using private[VThreadSupport] val l: Label[R] @uncheckedVariance)
      extends gears.async.Suspension[T, R]:
    private var nextInput: Option[T] = None
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    private[VThreadSupport] def setInput(data: T) =
      lock.lock()
      try
        nextInput = Some(data)
        cond.signalAll()
      finally lock.unlock()

    // variance is safe because the only caller created the object
    private[VThreadSupport] def waitInput(): T @uncheckedVariance =
      lock.lock()
      try
        while nextInput.isEmpty do cond.await()
        nextInput.get
      finally lock.unlock()

    // normal resume only tells other thread to run again -> resumeAsync may redirect here
    override def resume(arg: T): R =
      l.clearResult()
      setInput(arg)
      l.waitResult()

  override opaque type Suspension[-T, +R] <: gears.async.Suspension[T, R] = VThreadSuspension[T, R]

  override def boundary[R](body: (Label[R]) ?=> R): R =
    val label = VThreadLabel[R]()
    VThreadScheduler.execute: () =>
      val result = body(using label)
      label.setResult(result)

    label.waitResult()

  override private[async] def resumeAsync[T, R](suspension: Suspension[T, R])(arg: T)(using Scheduler): Unit =
    suspension.l.clearResult()
    suspension.setInput(arg)

  override def scheduleBoundary(body: (Label[Unit]) ?=> Unit)(using Scheduler): Unit =
    VThreadScheduler.execute: () =>
      val label = VThreadLabel[Unit]()
      body(using label)

  override def suspend[T, R](body: Suspension[T, R] => R)(using l: Label[R]): T =
    val sus = new VThreadSuspension[T, R]()
    val res = body(sus)
    l.setResult(res)
    sus.waitInput()
