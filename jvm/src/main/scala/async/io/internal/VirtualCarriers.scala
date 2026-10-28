package gears.async.io
package internal

import gears.async.Cancellable

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.Executor
import java.util.concurrent.ThreadFactory
import scala.concurrent.duration.FiniteDuration

/** Virtual threads carried by a `ReactorPool`'s workers, through what the JDK keeps to itself: a virtual thread builder
  * that takes the executor to run its threads on, the carrier of the running virtual thread, and the pin that keeps a
  * virtual thread on its carrier. The first two need `--add-opens java.base/java.lang=ALL-UNNAMED`, the pin
  * `--add-opens java.base/jdk.internal.vm=ALL-UNNAMED`.
  */
private[io] object VirtualCarriers {

  private def opened[A](what: String, pkg: String = "java.lang")(get: => A): A =
    try get
    catch {
      case e: (ReflectiveOperationException | RuntimeException) =>
        throw new UnsupportedOperationException(
          s"Running virtual threads on the reactor pool needs $what, which the JDK opens only with " +
            s"--add-opens java.base/$pkg=ALL-UNNAMED",
          e
        )
    }

  private lazy val builder = opened("ThreadBuilders.VirtualThreadBuilder(Executor)") {
    val constructor =
      Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder").getDeclaredConstructor(classOf[Executor])
    constructor.setAccessible(true)
    constructor
  }

  private lazy val carrier: MethodHandle = opened("Thread.currentCarrierThread") {
    MethodHandles
      .privateLookupIn(classOf[Thread], MethodHandles.lookup())
      .findStatic(classOf[Thread], "currentCarrierThread", MethodType.methodType(classOf[Thread]))
  }

  // `jdk.internal.vm.Continuation.pin` and `unpin`: a critical section of the running virtual thread, which cannot be
  // unmounted while it lasts. On a platform thread, which runs no continuation, both do nothing.
  private lazy val (pinHandle, unpinHandle) = opened("Continuation.pin and unpin", "jdk.internal.vm") {
    val continuation = Class.forName("jdk.internal.vm.Continuation")
    val lookup = MethodHandles.privateLookupIn(continuation, MethodHandles.lookup())
    val none = MethodType.methodType(classOf[Unit])
    (lookup.findStatic(continuation, "pin", none), lookup.findStatic(continuation, "unpin", none))
  }

  /** Checks, before any thread starts, that the JDK lets the pool carry virtual threads. */
  def check(): Unit = {
    builder
    carrier
    pinHandle: Unit
  }

  /** Keeps the running virtual thread on its carrier until `unpin`: a JDK that unmounts a virtual thread blocked on a
    * monitor (JEP 491, JDK 24) would otherwise resume it on another worker in the middle of a submission to this one's
    * reactor. Pinned, such a block parks the carrier instead, as before JDK 24. A park while pinned parks the carrier
    * too, so a caller unpins before it waits.
    */
  def pin(): Unit = pinHandle.invoke(): Unit

  def unpin(): Unit = unpinHandle.invoke(): Unit

  /** Makes virtual threads that `executor` runs: it is given each thread's continuation whenever it is to run. */
  def factory(executor: Executor): ThreadFactory =
    builder.newInstance(executor).asInstanceOf[Thread.Builder.OfVirtual].name("reactor-virtual-", 0L).factory()

  /** The platform thread running the caller: the carrier of a virtual thread, or the caller's own thread. */
  def currentCarrier(): Thread = carrier.invoke().asInstanceOf[Thread]
}

/** A pool whose workers carry virtual threads, so the worker running a caller is its carrier's. */
private[io] final class CarriedPool(backend: ReactorBackend, size: Int, policy: TakePolicy)
    extends ReactorPool(backend, size, policy) {
  override protected def callerThread(): Thread = VirtualCarriers.currentCarrier()
  override def pin(): Unit = VirtualCarriers.pin()
  override def unpin(): Unit = VirtualCarriers.unpin()
}

/** The gears scheduler of a program whose futures are virtual threads carried by `pool`: a task is a new virtual
  * thread, whose continuations the pool runs; a delayed one waits on a reactor's timer.
  */
private[io] final class VirtualReactorScheduler(val pool: ReactorPool, factory: ThreadFactory)
    extends ReactorScheduler {
  def backend: ReactorBackend = pool.backend
  def currentWorker: ReactorWorker = pool.currentWorker
  override def pin(): Unit = VirtualCarriers.pin()
  override def unpin(): Unit = VirtualCarriers.unpin()

  def execute(body: Runnable): Unit = factory.newThread(body).start()

  def schedule(delay: FiniteDuration, body: Runnable): Cancellable = pool.schedule(delay, () => execute(body))
}
