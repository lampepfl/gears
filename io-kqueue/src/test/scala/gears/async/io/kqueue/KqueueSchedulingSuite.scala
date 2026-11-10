package gears.async.io
package kqueue

import gears.async.Async
import gears.async.AsyncOperations
import gears.async.AsyncSupport
import gears.async.BufferedChannel
import gears.async.Future
import gears.async.Future.Promise
import gears.async.SyncChannel

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.util.Success

/** What a resume may rely on: it happens promptly, but only once the listener resuming it has returned, as sources
  * complete their listeners while holding locks and partway through changing their state.
  */
class KqueueSchedulingSuite extends munit.FunSuite {
  override def munitTimeout: Duration = 60.seconds

  private def spin(nanos: Long): Unit = {
    val end = System.nanoTime() + nanos
    while System.nanoTime() < end do Thread.onSpinWait()
  }

  /** `Io.run` on a pool of the given size. */
  private def onPool[T](workers: Int)(program: Io[KqueueBackend.type] ?=> Async.Spawn ?=> T): T = {
    val pool = KqueueScheduler(workers)
    given poolSupport: (AsyncSupport { type Scheduler = pool.Scheduler }) = pool
    given poolScheduler: pool.Scheduler = pool
    try Async.blocking(Future(program(using pool)).await)
    finally pool.shutdown()
  }

  test("a sender that reads the channel once resumed doesn't get its own item, nor resume twice") {
    onPool(4) {
      val channel = SyncChannel[Int]()
      val first = Future { channel.send(1); channel.read() }
      AsyncOperations.sleep(50)
      val second = Future { val item = channel.read(); channel.send(2); item }
      assertEquals(first.await, Right(2))
      assertEquals(second.await, Right(1))
    }
  }

  test("a sender resumed from a full buffer can't overtake its own item") {
    onPool(4) {
      val channel = BufferedChannel[Int](1)
      val sender = Future { channel.send(1); channel.send(2); channel.send(3) }
      AsyncOperations.sleep(50)
      val reader = Future(List(channel.read(), channel.read(), channel.read()))
      assertEquals(reader.await, List(Right(1), Right(2), Right(3)))
      sender.await
    }
  }

  test("a long chain of waiters doesn't grow the stack") {
    onPool(4) {
      val start = Promise[Int]()
      var last: Future[Int] = start.asFuture
      for _ <- 1 to 20_000 do {
        val previous = last
        last = Future(previous.await + 1)
      }
      AsyncOperations.sleep(500) // so that they are waiting
      start.complete(Success(0))
      assertEquals(last.await, 20_000)
    }
  }

  test("a thread outside the pool can resume a waiter") {
    onPool(4) {
      val promise = Promise[Int]()
      val waiter = Future(promise.asFuture.await)
      AsyncOperations.sleep(50)
      val outside = new Thread(() => promise.complete(Success(1)))
      outside.start()
      outside.join()
      assertEquals(waiter.await, 1)
    }
  }

  test("a due timer's waiter runs ahead of the inbox") {
    val ran = AtomicInteger(0)
    val backlog = 300
    val ranBeforeResume = onPool(1) { io ?=>
      val deadline = System.nanoTime() + 5_000_000L
      val waiter = Future {
        io.perform(io.backend.ops.deadlineTimer(deadline))
        ran.get()
      }
      io.perform(io.backend.ops.timer(1))
      for _ <- 1 to backlog do
        summon[Async].scheduler.execute { () =>
          spin(200_000L)
          ran.incrementAndGet(): Unit
        }
      waiter.await
    }
    assert(ranBeforeResume < backlog / 2, s"$ranBeforeResume of $backlog tasks ran first")
  }

  test("a worker survives a plain task and a scheduled body that throw") {
    onPool(2) {
      val scheduler = summon[Async].scheduler
      for _ <- 1 to 2 do {
        scheduler.execute(() => throw new RuntimeException("expected: a throwing task"))
        scheduler.schedule(1.milli, () => throw new RuntimeException("expected: a throwing scheduled body")): Unit
      }
      AsyncOperations.sleep(50)
      assertEquals((1 to 8).map(i => Future(i)).map(_.await).sum, 36)
    }
  }

  test("what a task throws is reported to the uncaught exception handler, on its worker's thread") {
    val reported = new java.util.concurrent.ConcurrentLinkedQueue[(String, Throwable)]
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler((thread, failure) => reported.add((thread.getName(), failure)): Unit)
    try {
      val thrown = new RuntimeException("expected: a throwing task")
      val fatal = new LinkageError("expected: a task throwing a fatal error")
      onPool(2) {
        val scheduler = summon[Async].scheduler
        scheduler.execute(() => throw thrown)
        scheduler.schedule(1.milli, () => throw fatal): Unit
        AsyncOperations.sleep(50)
        assertEquals((1 to 8).map(i => Future(i)).map(_.await).sum, 36) // both workers are still there
      }
      val failures = reported.toArray(Array.empty[(String, Throwable)]).toList
      assertEquals(failures.map(_._2).toSet, Set[Throwable](thrown, fatal))
      assert(failures.forall(_._1.startsWith("WorkpoolSchedulerWorker-")), failures.map(_._1).toString)
    } finally Thread.setDefaultUncaughtExceptionHandler(previous)
  }

  test("a fatal error in a task cancels the program, and the run throws it once the program has unwound") {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler((_, _) => ()) // reported there too, which is not what is tested here
    try {
      given KqueueBackend.type = KqueueBackend
      val fatal = new LinkageError("expected: a task throwing a fatal error")
      @volatile var unwound = false
      val thrown =
        try {
          Io.run { io ?=>
            summon[Async].scheduler.schedule(10.millis, () => throw fatal): Unit
            try io.perform(io.backend.ops.timer(30.seconds))
            finally unwound = true
          }
          None
        } catch { case error: LinkageError => Some(error) }
      assertEquals(thrown, Some(fatal))
      assert(unwound, "the program was not cancelled")
    } finally Thread.setDefaultUncaughtExceptionHandler(previous)
  }

  test("a task that throws a plain exception does not cancel the program") {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler((_, _) => ())
    try {
      given KqueueBackend.type = KqueueBackend
      val result = Io.run { io ?=>
        summon[Async].scheduler.execute(() => throw new RuntimeException("expected: a throwing task"))
        io.perform(io.backend.ops.timer(20.millis))
        1
      }
      assertEquals(result, 1)
    } finally Thread.setDefaultUncaughtExceptionHandler(previous)
  }

  test("a waiter resumed by one timer can move the next timer due") {
    onPool(1) { io ?=>
      val due = System.nanoTime() + 20_000_000L
      val first = io.backend.ops.deadlineTimer(due)
      val second = io.backend.ops.deadlineTimer(due + 1000L)
      val moved = new java.util.concurrent.atomic.AtomicLong(0L)
      val waiter = Future { io.perform(second); System.nanoTime() }
      val mover = Future {
        io.perform(first)
        moved.set(System.nanoTime() + 100_000_000L)
        second.moveTo(moved.get())
      }
      io.perform(io.backend.ops.timer(1))
      spin(40_000_000L) // past both deadlines before the only worker next looks at its heap
      val returned = waiter.await
      mover.await
      assert(returned >= moved.get(), s"${(moved.get() - returned) / 1000000}ms early")
    }
  }

  test("a pending deadline moved later returns at the new deadline, and one moved earlier at once") {
    onPool(2) { io ?=>
      val later = io.backend.ops.deadlineTimer(System.nanoTime() + 10_000_000L)
      val target = System.nanoTime() + 60_000_000L
      val waitingLater = Future { io.perform(later); System.nanoTime() }
      later.moveTo(target)
      val returnedLater = waitingLater.await // not within `assert`, which holds a monitor as it evaluates its condition
      assert(returnedLater >= target, "it returned at the old deadline")

      val earlier = io.backend.ops.deadlineTimer(System.nanoTime() + 60_000_000_000L)
      val waitingEarlier = Future { io.perform(earlier); System.nanoTime() }
      AsyncOperations.sleep(20)
      val moved = System.nanoTime()
      earlier.moveTo(moved)
      val returnedEarlier = waitingEarlier.await
      assert(returnedEarlier - moved < 5_000_000_000L, "it waited for the old deadline")
    }
  }

  test("a time too long for the clock is far off, not due at once") {
    onPool(2) { io ?=>
      val ran = java.util.concurrent.atomic.AtomicBoolean(false)
      val cancellable = summon[Async].scheduler.schedule(Long.MaxValue.nanos, () => ran.set(true))
      val waiter = Future(io.perform(io.backend.ops.timer(Long.MaxValue.nanos)))
      AsyncOperations.sleep(50)
      assert(!ran.get(), "a scheduled body ran at once")
      assert(waiter.poll().isEmpty, "a timer completed at once")
      cancellable.cancel()
      waiter.cancel()
      val outcome = waiter.awaitResult
      assert(outcome.isFailure)
    }
  }

  test("a negative timer is refused") {
    intercept[IllegalArgumentException](KqueueBackend.ops.timer((-1).milli))
  }

  test("every task a pool accepts as it shuts down is run") {
    for _ <- 1 to 300 do {
      val pool = KqueueScheduler(2)
      val ran = AtomicInteger(0)
      val accepted = AtomicInteger(0)
      val submitter = new Thread(() =>
        try
          while true do {
            pool.execute(() => ran.incrementAndGet(): Unit)
            accepted.incrementAndGet()
          }
        catch case _: IllegalStateException => ()
      )
      submitter.start()
      spin(100_000L)
      pool.shutdown()
      submitter.join()
      assertEquals(ran.get(), accepted.get())
    }
  }

  test("a run ending with work still in the inbox shuts down") {
    onPool(1) {
      for _ <- 1 to 100 do summon[Async].scheduler.execute(() => spin(100_000L))
    }
  }
}
