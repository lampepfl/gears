package gears.async.io.nio

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.Executor
import java.util.concurrent.ThreadFactory

/** Virtual threads carried by a pool's workers, through what the JDK keeps to itself: a virtual thread builder that
  * takes the executor its threads run on, and the carrier of the running virtual thread. Both are `java.lang`'s, which
  * the JDK opens only with `--add-opens java.base/java.lang=ALL-UNNAMED`.
  */
private[nio] object VirtualCarriers {
  inline val Opens = "--add-opens java.base/java.lang=ALL-UNNAMED"

  private def opened[A](what: String)(get: => A): A =
    try get
    catch
      case failure: (ReflectiveOperationException | RuntimeException) =>
        throw new UnsupportedOperationException(
          s"Running virtual threads on the pool's workers needs $what, which the JDK opens only with $Opens",
          failure
        )

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

  /** Checks, before any thread starts, that the JDK lets a pool carry virtual threads: throws saying what it needs. */
  def check(): Unit = {
    builder
    carrier: Unit
  }

  /** Makes virtual threads that `executor` runs: it is given each thread's continuation whenever it is to run. */
  def factory(executor: Executor): ThreadFactory =
    builder.newInstance(executor).asInstanceOf[Thread.Builder.OfVirtual].name("gears-io-", 0L).factory()

  /** The platform thread running the caller: the carrier of a virtual thread, or the caller's own thread. */
  def currentCarrier(): Thread = carrier.invokeExact(): Thread
}
