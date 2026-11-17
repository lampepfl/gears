package gears.async.io
package nio

import gears.async.Async

/** The JVM backend, over the JDK's channels and selectors, on the JDK's own virtual threads.
  *
  * A program's futures are virtual threads on the JDK's scheduler. Beside them runs a pool of workers (`NioScheduler`),
  * as the kqueue backend's are, each with a selector, a heap of timers and an inbox. An op is tried on the thread
  * performing it, and one that must wait is given to a worker, which does its work when its handle is ready, or fires
  * its time limit, and wakes the future. It needs nothing of the JDK beyond its public interface.
  */
object NioBackend extends Backend {
  type Interface = NioOps.type

  def pool(bufferSize: Int, buffers: Int): BufferPool = new DirectPool(bufferSize, buffers)

  /** Runs `program` beside a pool of one worker for each processor, shutting it down afterwards. The program runs under
    * the pool's group: a fatal error in a worker cancels it, and is thrown from here once it has unwound.
    */
  private[io] def run[T](program: Io[Interface] ?=> Async.Spawn ?=> T): T =
    runOn(Runtime.getRuntime.availableProcessors, shareable.Starvation.printWarning)(program)

  /** As `run`, on a pool of `workers` workers whose reports of starved workers go to `onStarvation`. */
  private[io] def runOn[T](workers: Int, onStarvation: shareable.Starvation.Handler)(
      program: Io[Interface] ?=> Async.Spawn ?=> T
  ): T = shareable.WorkpoolScheduler.run(new NioThreadsScheduler(workers, onStarvation))(program)
}

/** The JVM backend with its futures carried by the pool's own workers, in place of the JDK's scheduler: `NioBackend`'s
  * ops and handles, on a pool that runs the program as the kqueue backend's does on Scala Native. A future woken by a
  * worker goes on on that worker's thread, with no hand-over to another, and the pool reports and relieves a worker
  * that is kept from its tasks (see `Starvation`).
  *
  * The JDK lets nothing but itself schedule virtual threads: a program run over this backend needs
  * `--add-opens java.base/java.lang=ALL-UNNAMED`, and fails to start without it, saying so.
  */
object NioPoolBackend extends Backend {
  type Interface = NioOps.type

  def pool(bufferSize: Int, buffers: Int): BufferPool = new DirectPool(bufferSize, buffers)

  /** Runs `program` on a pool of one worker for each processor, shutting it down afterwards. */
  private[io] def run[T](program: Io[Interface] ?=> Async.Spawn ?=> T): T =
    runOn(Runtime.getRuntime.availableProcessors, shareable.Starvation.printWarning)(program)

  /** As `run`, on a pool of `workers` workers whose reports of starved workers go to `onStarvation`. */
  private[io] def runOn[T](workers: Int, onStarvation: shareable.Starvation.Handler)(
      program: Io[Interface] ?=> Async.Spawn ?=> T
  ): T = {
    VirtualCarriers.check()
    shareable.WorkpoolScheduler.run(new NioPoolScheduler(workers, onStarvation))(program)
  }
}
