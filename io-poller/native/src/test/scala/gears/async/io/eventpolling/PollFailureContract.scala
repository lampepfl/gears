package gears.async.io
package eventpolling

import gears.async.Async
import gears.async.AsyncSupport
import gears.async.Future

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.util.Failure

/** What a pool whose polls can be made to fail has for the checks to set and to count. */
trait FailingPolls {

  /** From when this is set, every poll of every worker fails. */
  val failing = new AtomicBoolean(false)

  /** How many polls have failed. */
  val failedPolls = new AtomicInteger(0)

  /** What a worker's poll does in place of the poller's own while `failing` is set. */
  protected final def failPoll(): Nothing = {
    failedPolls.incrementAndGet()
    throw new IOException("Failed to poll events: injected")
  }
}

/** A worker whose event queue keeps failing gives it up: it tells the pool, once, which is fatal to the program, and
  * goes on running its tasks and firing its timers without it, so that the program can unwind. The same of either
  * poller: a system's suite gives the pool whose polls fail (`failingPool`), which is its poller's to make.
  */
trait PollFailureContract extends munit.FunSuite {
  override def munitTimeout: Duration = 60.seconds

  /** A pool of `workers` workers whose polls all fail from when its `failing` is set. */
  protected def failingPool(workers: Int): EventPollingScheduler & FailingPolls

  private def pipe(io: EventPollingScheduler)(using Async): (EventPollingReadable, EventPollingWritable) = {
    val read = Slot[EventPollingReadable]()
    val write = Slot[EventPollingWritable]()
    io.perform(EventPollingOps.pipe(read, write))
    (read.clear(), write.clear())
  }

  /** The failure goes to the thread's uncaught exception handler, as a worker's errors do: kept out of the log here. */
  private def quietly[T](body: => T): T = {
    val before = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler((_, _) => ())
    try body
    finally Thread.setDefaultUncaughtExceptionHandler(before)
  }

  test("workers that give their queue up report it once each, and still run tasks, timers and wake-ups") {
    quietly {
      val pool = failingPool(2)
      given poolSupport: (AsyncSupport { type Scheduler = pool.Scheduler }) = pool
      given poolScheduler: pool.Scheduler = pool
      try
        Async.blocking(Future {
          pool.perform(EventPollingOps.timer(1))
          assert(pool.fatalFailure == null)
          pool.failing.set(true)
          // Timers are the workers' own heaps, which need no queue: this one sees the failure through.
          val deadline = System.nanoTime() + 5_000_000_000L
          while pool.fatalFailure == null && System.nanoTime() < deadline do pool.perform(EventPollingOps.timer(1))
          val failure = pool.fatalFailure
          assert(failure.isInstanceOf[IOException], String.valueOf(failure))
          assert(failure.getMessage().contains("gave up"), failure.getMessage())

          // Given up, not retried for ever: after a moment no more polls are made.
          pool.perform(EventPollingOps.timer(50))
          val polls = pool.failedPolls.get()
          pool.perform(EventPollingOps.timer(50))
          assertEquals(pool.failedPolls.get(), polls)
          assert(polls <= 2 * 3, s"$polls failed polls for two workers")

          // Tasks from here and from other threads, and what waits on them, still run.
          assertEquals(List.tabulate(8)(i => Future(i * 2)).map(_.await).sum, 56)
          val fromOutside = Future.Promise[Int]()
          new Thread(() => { Thread.sleep(20); fromOutside.complete(scala.util.Success(7)) }).start()
          assertEquals(fromOutside.await, 7)
        }.await)
      finally pool.shutdown()
    }
  }

  test("the failure cancels the program, and an op that waited on a handle is cancelled with it") {
    quietly {
      val pool = failingPool(2)
      given poolSupport: (AsyncSupport { type Scheduler = pool.Scheduler }) = pool
      given poolScheduler: pool.Scheduler = pool
      try
        // As `EventPollingBackend.run` runs a program: under the pool's group, which a fatal failure cancels.
        val outcome = Async.blocking {
          Async.withNewCompletionGroup(pool.rootGroup)(Future {
            val (in, out) = pipe(pool)
            val reader = Future(pool.perform(EventPollingOps.read(in, ByteBuffer.allocate(1))))
            pool.perform(EventPollingOps.timer(20)) // it waits, on a queue that is about to fail
            pool.failing.set(true)
            reader.awaitResult
          }.awaitResult)
        }
        assert(pool.fatalFailure.isInstanceOf[IOException], String.valueOf(pool.fatalFailure))
        outcome match
          case Failure(_: CancellationException)                     => () // the program itself was cancelled
          case scala.util.Success(Failure(_: CancellationException)) => () // or saw its reader cancelled first
          case other                                                 => fail(s"not cancelled: $other")
      finally pool.shutdown()
    }
  }
}
