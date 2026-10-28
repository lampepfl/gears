package gears.async.io
package internal

import gears.async.Async
import gears.async.AsyncSupport
import gears.async.Cancellable
import gears.async.Future
import gears.async.Listener
import gears.async.Scheduler
import gears.async.native.NativeSuspend

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.FiniteDuration
import scala.scalanative.posix.sched.sched_yield
import scala.scalanative.posix.signal.sigaltstack
import scala.scalanative.posix.signal.sigprocmask
import scala.scalanative.posix.signal.sigset_t
import scala.scalanative.posix.signal.stack_t
import scala.scalanative.unsafe.*

/** Checks that a suspended future can resume on a different thread from the one it suspended on, as a pool whose
  * workers take tasks from one another will make it (see `ReactorPoolSuite`). Two threads hand every task to each
  * other, so each resume moves the future to the other thread. After each one the future checks:
  *   - its own state: locals and a chain of frames below the suspension, which the resume copies to the new thread's
  *     stack;
  *   - the new thread's own signal state: Scala Native gives each thread an alternate signal stack, and on macOS libc's
  *     `setjmp`, which a resume calls, also saves signal state, so restoring the old thread's would leave two threads
  *     sharing one alternate stack;
  *   - an exception thrown after the move and caught in a frame the resume restored, and one that ends the future;
  *   - a `@blocking` call and an allocation, which switch the new thread's GC state.
  *
  * The signal mask is compared without SIGINT and SIGTERM: Scala Native's GC blocks them for a collection with
  * `sigprocmask` (`Synchronizer_acquire`), which on macOS applies to every thread of the process, and unblocks them
  * only after resuming the threads it stopped, so the other threads can briefly see them blocked, moved or not.
  */
object MigrateCheck {

  /** How the check went: failures, resumes that moved threads, and resumes in all. */
  final case class Outcome(failures: Long, moved: Long, resumes: Long)

  /** The signal state of the calling thread: its alternate stack and flags, and its signal mask without the signals the
    * GC blocks.
    */
  final case class SignalState(altStack: Long, altSize: Long, altFlags: Int, mask: Long)

  /** SIGINT and SIGTERM, as bits of a mask. */
  private inline val BlockedByGC = (1L << (2 - 1)) | (1L << (15 - 1))

  def signalState(): SignalState = {
    val stack = stackalloc[stack_t]()
    if sigaltstack(null, stack) != 0 then throw new IllegalStateException("sigaltstack failed")
    val mask = stackalloc[Long]() // sigset_t is 4 bytes on macOS, 8 on Linux
    !mask = 0L
    if sigprocmask(0, null, mask.asInstanceOf[Ptr[sigset_t]]) != 0 then
      throw new IllegalStateException("sigprocmask failed")
    SignalState(stack._1.toLong, stack._2.toLong, stack._3, !mask & ~BlockedByGC)
  }

  /** Two threads that run every task on the other one, so each resume moves the resumed future across. */
  final class Swapper(stay: Boolean) extends Scheduler {
    private val queues = Array.fill(2)(new LinkedBlockingQueue[Runnable]())
    private val threads = Array.tabulate(2)(i => new Thread(() => work(i), s"migrate-$i"))
    private val baselines = new Array[SignalState](2)
    private val started = new CountDownLatch(2)
    @volatile private var stopped = false

    threads.foreach(t => { t.setDaemon(true); t.start() })
    started.await()

    private def work(i: Int): Unit = {
      baselines(i) = signalState()
      started.countDown()
      while !stopped do {
        val task = queues(i).take()
        try task.run()
        catch case t: Throwable => t.printStackTrace()
      }
    }

    /** Which of the two threads the caller is, or -1. */
    def index: Int = threads.indexOf(Thread.currentThread())

    /** The signal state the calling thread had before it ran any task. */
    def baseline: SignalState = baselines(index)

    def execute(body: Runnable): Unit = queues(if (index == 0) != stay then 1 else 0).put(body)

    def schedule(delay: FiniteDuration, body: Runnable): Cancellable =
      throw new UnsupportedOperationException("no timers here")

    def stop(): Unit = { stopped = true; queues.foreach(_.put(() => ())) }
  }

  object SwapperSupport extends AsyncSupport with NativeSuspend {
    type Scheduler = Swapper
  }

  /** A source that completes its listener as soon as it is added, so awaiting it suspends and resumes at once. */
  private object Resume extends Async.Source[Unit] {
    override def poll(): Option[Unit] = None
    def poll(k: Listener[Unit]): Boolean = false
    def onComplete(k: Listener[Unit]): Unit = k.completeNow((), this)
    def dropListener(k: Listener[Unit]): Unit = ()
  }

  /** Thrown after a move, carrying what the frames it skips would have returned. */
  private final class Thrown(val value: Int) extends Exception(null, null, false, false)

  /** Every so many rounds, the suspending frame throws after the resume, and a frame halfway up catches it. */
  private inline val ThrowEvery = 16

  def run(futures: Int, resumes: Int, stay: Boolean = false): Outcome = {
    val swapper = new Swapper(stay)
    given Swapper = swapper
    given SwapperSupport.type = SwapperSupport
    val moves = new AtomicLong
    val failures = new AtomicLong
    def fail(message: String): Unit =
      if failures.getAndIncrement() < 10 then System.err.println(s"migrate: $message")

    // Suspends `depth` frames down, so the resume has a chain of frames to restore, each checking its own locals. Each
    // frame returns its depth plus what the frames below it return, and the bottom one 256.
    def nested(id: Int, round: Int, depth: Int)(using Async): Int =
      if depth == 0 then {
        val before = swapper.index
        summon[Async].await(Resume)
        val after = swapper.index
        if after != before then moves.incrementAndGet()
        val signals = signalState()
        if signals != swapper.baseline then
          fail(s"future $id round $round: thread $after has signal state $signals, not ${swapper.baseline}")
        sched_yield() // a @blocking call on the new thread
        val bottom = List.fill(4)(new Array[Byte](64)).map(_.length).sum
        if round % ThrowEvery == 0 then throw new Thrown(bottom + (1 to 3).sum)
        bottom
      } else {
        val mine = id * 1_000_003L + round * 31L + depth
        val below =
          if depth == 4 then
            try nested(id, round, depth - 1)
            catch case thrown: Thrown => thrown.value
          else nested(id, round, depth - 1)
        if mine != id * 1_000_003L + round * 31L + depth then fail(s"future $id round $round depth $depth: local lost")
        below + depth
      }

    Async.blocking {
      val ended = (0 until futures).map { id =>
        Future {
          var total = 0L
          var round = 0
          while round < resumes do {
            total += nested(id, round, 8)
            round += 1
          }
          val expected = resumes.toLong * (256 + (1 to 8).sum)
          if total != expected then fail(s"future $id: total $total, expected $expected")
          summon[Async].await(Resume)
          throw new IllegalStateException(s"future $id ends")
        }
      }
      for (future, id) <- ended.zipWithIndex do
        future.awaitResult match {
          case scala.util.Failure(e: IllegalStateException) if e.getMessage == s"future $id ends" => ()
          case other => fail(s"future $id ended with $other")
        }
    }
    swapper.stop()
    Outcome(failures.get(), moves.get(), futures.toLong * resumes)
  }
}
