package gears.async.io
package internal

import gears.async.Cancellable

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.FiniteDuration

/** The thread of a pool's worker, for every worker but one started by `ReactorPool.run`, whose first worker runs on the
  * thread that started the pool. It holds its worker, so that finding the worker running the caller takes no
  * thread-local lookup beyond the current thread. A daemon: a program that ends without stopping its pool is not kept
  * alive by it.
  */
private[io] final class ReactorWorkerThread(
    val pool: ReactorPool,
    index: Int,
    started: java.util.concurrent.CountDownLatch
) extends Thread(s"reactor-worker-$index") {
  setDaemon(true)
  var worker: ReactorWorker | Null = null // on this thread only

  override def run(): Unit = {
    val mine = new ReactorWorker(pool, index, pool.backend.open()) // opened here, where it hears of closes
    worker = mine
    pool.workers(index) = mine
    started.countDown()
    started.await() // so that every worker this one may take tasks from is in `workers`
    mine.loop()
  }
}

/** A gears scheduler over a pool of workers, each a thread driving a reactor of `backend`'s (see `ReactorWorker`).
  * Futures inherit their parent's scheduler, so a program started by `ReactorPool.run`, and everything it spawns, runs
  * on the pool. A task queued on a worker runs there unless another worker, out of work, takes it; queued from outside
  * the pool, it goes to a shared queue, which every worker takes from. With one worker, everything runs on the thread
  * that called `run`.
  */
class ReactorPool private[io] (val backend: ReactorBackend, size: Int, val policy: TakePolicy)
    extends ReactorScheduler {
  require(size >= 1 && size <= 64, "A pool has between 1 and 64 workers")

  /** The workers, by index; filled in as each starts on its own thread. */
  private[io] val workers = new Array[ReactorWorker](size)

  /** The threads of the workers started on threads of their own, for `join`. */
  private var threads: Seq[ReactorWorkerThread] = Nil

  // Tasks queued from outside the pool, and those that overflowed a worker's queue.
  private val injector = new ConcurrentLinkedQueue[Runnable]()

  // A bit per worker blocked in its reactor, or about to be, so that work can be offered to one of them.
  private val idle = new java.util.concurrent.atomic.AtomicLong(0L)

  /** Workers looking for work on others; while one is, offering work wakes no one else. */
  private[io] val searching = new java.util.concurrent.atomic.AtomicInteger(0)

  @volatile private[io] var stopped = false

  /** For measurement: wake-ups sent to idle workers, offering work and for tasks from outside. */
  private[io] val wakeUps = new java.util.concurrent.atomic.AtomicLong
  private[io] val injections = new java.util.concurrent.atomic.AtomicLong

  /** The thread the caller runs on, as the pool knows its workers: on the JVM, a virtual thread's carrier. */
  protected def callerThread(): Thread = Thread.currentThread()

  /** The worker running the caller, if it is one of this pool's: for the pool's own queueing, which the JDK may ask for
    * from a thread of its own (see `CarriedPool`).
    */
  private def workerOrNull: ReactorWorker | Null =
    callerThread() match {
      case thread: ReactorWorkerThread => if thread.pool eq this then thread.worker else null
      case thread                      =>
        val first = workers(0)
        if first != null && (first.owner eq thread) then first else null
    }

  def currentWorker: ReactorWorker = {
    val worker = workerOrNull
    if worker == null then throw new IllegalStateException(s"${Thread.currentThread()} is no worker of $this")
    worker
  }

  /** Queues `body` on the worker running the caller, or on the shared queue from outside the pool. */
  def execute(body: Runnable): Unit = {
    val worker = workerOrNull
    if worker != null then worker.push(body) else inject(body)
  }

  /** Queues `task` on the shared queue and wakes an idle worker other than `from`, the worker queueing it if any. */
  private[io] def inject(task: Runnable, from: Int = -1): Unit = {
    injections.incrementAndGet()
    injector.add(task)
    wakeOne(from)
  }

  private[io] def injected(): Runnable | Null = injector.poll()

  /** Worker `from` has more queued than it is about to run: wakes another idle worker to take some, unless one is
    * searching. `from` itself may still be marked idle, since a worker runs completions, which queue tasks, in the turn
    * it blocked in; but it is running, so it is not the one to wake.
    */
  private[io] def offerWork(from: Int): Unit =
    if (idle.get() & ~(1L << from)) != 0L && searching.get() == 0 then wakeOne(from)

  /** Wakes an idle worker other than `except` (-1 for none). */
  private def wakeOne(except: Int = -1): Unit = {
    val others = if except >= 0 then ~(1L << except) else -1L
    var mask = idle.get() & others
    while mask != 0L do {
      val i = java.lang.Long.numberOfTrailingZeros(mask)
      val current = idle.get()
      if (current & (1L << i)) != 0L && idle.compareAndSet(current, current & ~(1L << i)) then {
        wakeUps.incrementAndGet()
        workers(i).reactor.wakeup()
        return
      }
      mask = idle.get() & others
    }
  }

  private[io] def markIdle(index: Int): Unit = {
    var mask = idle.get()
    while !idle.compareAndSet(mask, mask | (1L << index)) do mask = idle.get()
  }

  private[io] def unmarkIdle(index: Int): Unit = {
    var mask = idle.get()
    while (mask & (1L << index)) != 0L && !idle.compareAndSet(mask, mask & ~(1L << index)) do mask = idle.get()
  }

  /** Whether there is work `worker` could take: from outside the pool, or spare on another worker. */
  private[io] def hasWorkFor(worker: ReactorWorker): Boolean =
    !injector.isEmpty || workers.exists(other => (other ne worker) && other.hasSpare)

  /** Runs `body` after `delay` on the worker running the caller, or on the first worker from outside the pool. */
  def schedule(delay: FiniteDuration, body: Runnable): Cancellable = {
    val worker = workerOrNull
    (if worker != null then worker else workers(0)).schedule(delay, body)
  }

  /** Runs the first worker, on the thread that started the pool, until the pool stops; then throws the pool's failure,
    * if a broken reactor stopped it (see `fail`), since the program, its tasks stranded, will not finish.
    */
  private[io] def runFirstWorker(): Unit = {
    workers(0).loop()
    val failure = failed.get()
    if failure != null then throw failure
  }

  // The error of the first worker whose reactor broke (see `ReactorWorker.turn`).
  private val failed = new java.util.concurrent.atomic.AtomicReference[Throwable | Null](null)

  /** Stops the pool because a worker's reactor is broken, with `failure` its error: `runFirstWorker` throws it. */
  private[io] def fail(failure: Throwable): Unit = {
    failed.compareAndSet(null, failure): Unit
    stop()
  }

  /** Starts the workers from `from` on, each on a thread of its own, where it opens its reactor, and returns once every
    * one of them is in `workers`: the array is complete, and published to every worker, before any task runs.
    */
  private def startThreads(from: Int): Unit = {
    val started = new java.util.concurrent.CountDownLatch(size - from)
    threads = (from until size).map { i =>
      val thread = new ReactorWorkerThread(this, i, started)
      thread.start()
      thread
    }
    started.await()
  }

  /** Stops every worker after its current round. */
  private[io] def stop(): Unit = {
    stopped = true
    workers.foreach(worker => if worker != null then worker.reactor.wakeup())
  }

  /** Waits for the workers started on threads of their own to end, once stopped: each closes its reactor first. */
  private[io] def join(): Unit = threads.foreach(_.join())
}

object ReactorPool {

  /** Runs a pool of `workers` workers, each with a reactor of `backend`'s: the calling thread becomes the first, and
    * the others get threads of their own, each opening its reactor there. `drive` then runs on the calling thread: it
    * starts the program, runs the first worker (`runFirstWorker`) until the program finishes and stops the pool,
    * returns the program's result and rethrows its failure. Each reactor is closed afterwards, on its own thread, then
    * `stopped` is called with the pool. `newPool` builds the pool, for a platform whose workers are found another way.
    */
  def run[T](
      backend: ReactorBackend,
      workers: Int,
      policy: TakePolicy,
      newPool: (ReactorBackend, Int, TakePolicy) => ReactorPool = new ReactorPool(_, _, _),
      stopped: ReactorPool => Unit = _ => ()
  )(drive: ReactorPool => T): T = {
    val pool = newPool(backend, workers, policy)
    pool.workers(0) = new ReactorWorker(pool, 0, backend.open()) // opened here too, on its worker's thread
    pool.startThreads(from = 1)
    try drive(pool)
    finally {
      pool.stop()
      pool.join()
      stopped(pool)
    }
  }
}
