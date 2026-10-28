package gears.async.io
package internal

import gears.async.Cancellable

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration

/** One worker of a `ReactorPool`: a thread, the reactor it drives, and the tasks queued for it. Its loop alternates
  * between running queued tasks, such as starting a future or resuming a suspended one, and turns of the reactor; with
  * nothing to run, it looks for work on the other workers, and failing that blocks in its reactor until an event, or a
  * worker with work to spare, wakes it. Code running on a worker submits ops to its reactor directly.
  *
  * Its reactor is one of `backend`'s, opened on the worker's own thread, so it runs any op the backend builds.
  */
private[io] final class ReactorWorker(val pool: ReactorPool, val index: Int, val reactor: Reactor) {

  /** The thread it runs on, which created it. */
  private[io] val owner = Thread.currentThread()

  // Tasks for this worker, almost all of them: resuming a continuation is queued by a completion, which runs here. A
  // fixed ring that allocates nothing per task, and from which other workers take tasks when they run out.
  private[io] val local = new RunQueue

  // Tasks that must run on this worker in particular, queued from other threads, which wake it: cancelling an op
  // pending on its reactor, or arming a timer there (`sync`). `drain` runs them first, never through `local`, from
  // which other workers take tasks.
  private val inbox = new ConcurrentLinkedQueue[Runnable]()

  // Under the affine and hybrid policies, tasks resumed by this worker's reactor, which no other worker may take: a
  // task doing I/O here stays, with its descriptors. On this worker's thread only.
  private val home = new java.util.ArrayDeque[Runnable]()

  // Whether the lone task in `local` waits behind resumes in `home`, so that other workers may take it: set when that
  // happens, and cleared once a drain has run the resumes. Read by other workers; `loneShared` is the owner's copy, so
  // that the usual case, with nothing to share, neither writes nor reads it.
  @volatile private var loneSpare = false
  private var loneShared = false

  /** Offers the lone task in `local`, which waits behind resumes, to idle workers. */
  private def shareLone(): Unit = {
    if !loneShared then {
      loneShared = true
      loneSpare = true
    }
    pool.offerWork(index)
  }

  // Whether this worker is in a turn of its reactor, which runs completions: a task queued meanwhile from this thread
  // is a resume from the reactor, of an op finished there.
  private var inTurn = false

  /** Whether the caller runs on this worker's thread, so it may use the reactor directly. */
  def isCurrent: Boolean = Thread.currentThread() eq owner

  /** Queues `task` here, on this worker's thread only. A resume from this worker's reactor stays here under the affine
    * and hybrid policies; anything else goes where other workers may take it, a full queue overflowing to the pool's
    * shared queue, and with more queued there than this worker is about to run, an idle worker is woken to take some.
    */
  private[io] def push(task: Runnable): Unit =
    if inTurn && pool.policy != TakePolicy.Free then home.add(task)
    else if !local.push(task) then pool.inject(task, index)
    else {
      val queued = local.size
      if queued > 1 then pool.offerWork(index)
      else if queued == 1 && !home.isEmpty then shareLone()
    }

  /** Whether another worker may take some of this one's tasks: one is queued behind others that this worker is about to
    * run first, its resumes or another queued task. A lone queued task is left to it, since taking it would only move
    * it. Read from any thread.
    */
  private[io] def hasSpare: Boolean = {
    val queued = local.size
    queued > 1 || queued == 1 && loneSpare
  }

  /** A turn of this worker's reactor. One that throws is reported, and the worker goes on, as it does after a task that
    * throws: what threw is a completion, or the reactor, which fails only an op for what it can (kqueue's errors, for
    * one), and the worker's tasks and the reactor's other ops are still to be run. A reactor that throws turn after
    * turn, `BrokenTurns` of them in a row, is broken: the worker fails the pool with its error (`ReactorPool.fail`),
    * which stops it, rather than spinning, or ending its thread and stranding its tasks.
    */
  private def turn(block: Boolean): Unit = {
    inTurn = true
    try {
      reactor.runOnce(block)
      if failedTurns != 0 then failedTurns = 0
    } catch {
      case t: Throwable =>
        failedTurns += 1
        t.printStackTrace()
        if failedTurns >= ReactorWorker.BrokenTurns then pool.fail(t)
    } finally inTurn = false
    if !home.isEmpty && local.size == 1 then shareLone()
  }

  // Turns in a row that threw (see `turn`). On this worker's thread only.
  private var failedTurns = 0

  /** Runs `body` on this worker's thread: now if called there, or queued and the worker woken otherwise. */
  def sync(body: => Unit): Unit =
    if isCurrent then body
    else {
      inbox.add(() => body)
      reactor.wakeup()
    }

  /** Ops that `perform` may still finish without suspending in this round while no other task is queued, reset by each
    * `drain`. Spent, `perform` suspends even for an op that could finish at once, so the reactor's poll gets to run.
    */
  private[io] var fastPathsLeft = ReactorWorker.FastPathBudget

  /** Ops that `perform` may still finish without suspending in the running task's turn, even with other tasks queued,
    * reset before each task. Spent, `perform` suspends, so a future whose ops rarely wait still gives up its turn.
    */
  private[io] var turnFastPathsLeft = 0

  /** For measurement, on this worker's thread only: tasks taken from other workers, and the times it blocked. */
  private[io] var taken = 0L
  private[io] var blocked = 0L

  /** Whether no task is queued here. On this worker's thread only. */
  private[io] def isIdle: Boolean = local.isEmpty && home.isEmpty && inbox.isEmpty

  /** Runs `body` after `delay`, using a timer of this worker's reactor; the returned cancel may be called anywhere. */
  private[io] def schedule(delay: FiniteDuration, body: Runnable): Cancellable = {
    val cancelTimer = new AtomicReference[(() => Unit) | Null](null)
    sync {
      val timer = reactor.ops.timer(delay.toMillis.toInt)
      reactor.submit(timer, _ => body.run())
      cancelTimer.set(() => reactor.cancel(timer))
    }
    () =>
      sync {
        val cancel = cancelTimer.get()
        if cancel != null then cancel()
      }
  }

  /** Runs the tasks queued when it starts. Tasks they queue wait for the next drain, after the reactor's turn: work
    * that keeps queueing more, such as a computation yielding between slices, would otherwise keep I/O from ever being
    * polled.
    */
  private[io] def drain(): Unit = {
    fastPathsLeft = ReactorWorker.FastPathBudget
    // Tasks for this worker in particular run first, here: in `local`, another worker could take one and call this
    // worker's reactor from its own thread. They run as though from the reactor's turn, so that a task they resume,
    // such as one whose op a cancel finished, stays here under the affine and hybrid policies. Those queued meanwhile
    // wait for the next drain, which the loop does not block before.
    var mine = ReactorWorker.InboxPerDrain
    var task = inbox.poll()
    inTurn = true
    try
      while task != null do {
        runTask(task)
        mine -= 1
        task = if mine > 0 then inbox.poll() else null
      }
    finally inTurn = false
    // Then some from outside the pool join the local ones. Once `local` is full, the one that did not fit runs now
    // and the rest wait for the next drain: a task it runs could queue more here meanwhile.
    var fromOutside = ReactorWorker.InjectedPerDrain
    task = if fromOutside > 0 then pool.injected() else null
    while task != null do {
      fromOutside -= 1
      if !local.push(task) then {
        runTask(task)
        task = null
      } else task = if fromOutside > 0 then pool.injected() else null
    }
    // Under the hybrid policy, a backlog of resumes here is shared: half of it goes where idle workers may take it.
    if pool.policy == TakePolicy.Hybrid && home.size > ReactorWorker.HybridBacklog then {
      var shared = home.size / 2
      while shared > 0 && local.push(home.poll()) do shared -= 1
      pool.offerWork(index)
    }
    // Resumes from this worker's reactor first, then the rest.
    var resumes = home.size
    while resumes > 0 do {
      runTask(home.poll())
      resumes -= 1
    }
    if loneShared then {
      loneShared = false
      loneSpare = false
    }
    var remaining = local.size
    while remaining > 0 do {
      val next = local.pop()
      remaining -= 1
      if next != null then runTask(next)
    }
  }

  private def runTask(task: Runnable): Unit = {
    turnFastPathsLeft = ReactorWorker.TurnFastPathBudget
    try task.run()
    catch case t: Throwable => t.printStackTrace()
  }

  /** Takes tasks from another worker with some to spare (see `hasSpare`), or from outside the pool, and returns whether
    * it found any.
    */
  private def takeWork(): Boolean = {
    val injected = pool.injected()
    if injected != null then {
      push(injected)
      return true
    }
    val workers = pool.workers
    if workers.length > 1 then {
      pool.searching.incrementAndGet()
      try {
        val start = java.util.concurrent.ThreadLocalRandom.current().nextInt(workers.length)
        var i = 0
        while i < workers.length do {
          val source = workers((start + i) % workers.length)
          if (source ne this) && source.hasSpare then {
            val moved = source.local.moveHalfTo(local)
            if moved > 0 then {
              taken += moved
              return true
            }
          }
          i += 1
        }
      } finally pool.searching.decrementAndGet()
    }
    false
  }

  /** Runs until the pool stops, then closes the reactor. */
  private[io] def loop(): Unit = {
    try
      while !pool.stopped do {
        drain()
        if !pool.stopped then
          if !isIdle || takeWork() then turn(block = false)
          else {
            // About to block: say so first, then look again, so that a worker queueing work meanwhile either sees this
            // one idle and wakes it, or has its work seen here.
            pool.markIdle(index)
            if !isIdle || pool.hasWorkFor(this) then {
              pool.unmarkIdle(index)
              turn(block = false)
            } else {
              blocked += 1
              turn(block = true)
              pool.unmarkIdle(index)
            }
          }
      }
    finally reactor.close() // Pending ops are cancelled, rejecting any futures still waiting on them.
  }
}

private[io] object ReactorWorker {

  /** How many ops `perform` may finish without suspending in one round of a worker. */
  inline val FastPathBudget = 32

  /** How many ops `perform` may finish without suspending in one task's turn while other tasks are queued. */
  inline val TurnFastPathBudget = 1

  /** How many tasks queued for a worker in particular it runs in each round, at most. */
  inline val InboxPerDrain = 256

  /** How many turns of its reactor in a row may throw before a worker takes it as broken and fails the pool. */
  inline val BrokenTurns = 16

  /** How many tasks from outside the pool a worker takes into each round, at most. */
  inline val InjectedPerDrain = 32

  /** Under the hybrid policy, how many resumes a worker keeps to itself before sharing half of them. */
  inline val HybridBacklog = 16
}
