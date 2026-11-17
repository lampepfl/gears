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
  private def onPool[T](workers: Int, onStarvation: shareable.Starvation.Handler = shareable.Starvation.printWarning)(
      program: Io[KqueueOps.type] ?=> Async.Spawn ?=> T
  ): T = {
    val pool = KqueueScheduler(workers, onStarvation)
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
        io.perform(io.ops.deadlineTimer(deadline))
        ran.get()
      }
      io.perform(io.ops.timer(1))
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
            try io.perform(io.ops.timer(30.seconds))
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
        io.perform(io.ops.timer(20.millis))
        1
      }
      assertEquals(result, 1)
    } finally Thread.setDefaultUncaughtExceptionHandler(previous)
  }

  test("a waiter resumed by one timer can move the next timer due") {
    onPool(1) { io ?=>
      val due = System.nanoTime() + 20_000_000L
      val first = io.ops.deadlineTimer(due)
      val second = io.ops.deadlineTimer(due + 1000L)
      val moved = new java.util.concurrent.atomic.AtomicLong(0L)
      val waiter = Future { io.perform(second); System.nanoTime() }
      val mover = Future {
        io.perform(first)
        moved.set(System.nanoTime() + 100_000_000L)
        second.moveTo(moved.get())
      }
      io.perform(io.ops.timer(1))
      spin(40_000_000L) // past both deadlines before the only worker next looks at its heap
      val returned = waiter.await
      mover.await
      assert(returned >= moved.get(), s"${(moved.get() - returned) / 1000000}ms early")
    }
  }

  test("a pending deadline moved later returns at the new deadline, and one moved earlier at once") {
    onPool(2) { io ?=>
      val later = io.ops.deadlineTimer(System.nanoTime() + 10_000_000L)
      val target = System.nanoTime() + 60_000_000L
      val waitingLater = Future { io.perform(later); System.nanoTime() }
      later.moveTo(target)
      val returnedLater = waitingLater.await // not within `assert`, which holds a monitor as it evaluates its condition
      assert(returnedLater >= target, "it returned at the old deadline")

      val earlier = io.ops.deadlineTimer(System.nanoTime() + 60_000_000_000L)
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
      val waiter = Future(io.perform(io.ops.timer(Long.MaxValue.nanos)))
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
    intercept[IllegalArgumentException](KqueueOps.timer((-1).milli))
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

  test("a future spawned from a worker runs on that worker, when it is the only one waiting there") {
    onPool(4) {
      var elsewhere = 0
      var i = 0
      while i < 200 do
        val here = Thread.currentThread().getName()
        // Whichever worker resumes this future after the await is the one that spawns next.
        if Future(Thread.currentThread().getName()).await != here then elsewhere += 1
        i += 1
      assertEquals(elsewhere, 0)
    }
  }

  test("futures a worker can't get to are taken by workers with nothing to do") {
    onPool(4) {
      val work = 20L // ms each, keeping its worker
      val start = System.nanoTime()
      val threads = List.fill(16)(Future { Thread.sleep(work); Thread.currentThread().getName() }).map(_.await)
      val took = (System.nanoTime() - start) / 1_000_000L
      assert(threads.distinct.size > 1, s"all ran on ${threads.distinct}")
      assert(took < 16 * work, s"took ${took}ms, as long as one worker alone")
    }
  }

  /** The reports of starved workers made by the pool `body` runs its program on, with the handler it is given. */
  private def reportsDuring(body: shareable.Starvation.Handler => Unit): List[shareable.Starvation.Report] = {
    val reports = new java.util.concurrent.ConcurrentLinkedQueue[shareable.Starvation.Report]
    body(report => reports.add(report): Unit)
    reports.toArray(Array.empty[shareable.Starvation.Report]).toList
  }

  test("a worker whose thread is blocked while a task waits for it is reported, once") {
    for workers <- List(1, 4) do {
      var blocked: Thread | Null = null
      val reports = reportsDuring { handler =>
        onPool(workers, handler) {
          blocked = Thread.currentThread()
          val spawned = Future(1) // in this worker's inbox, alone, which calls for no thief
          Thread.sleep(400)
          assertEquals(spawned.await, 1)
        }
      }
      assertEquals(reports.map(_.worker), List(blocked.nn), s"on $workers workers")
      assert(reports.head.keptNanos >= shareable.Starvation.thresholdNanos, reports.head.message)
      assert(reports.head.message.contains("io.ops.blocking"), reports.head.message)
      // The task that waited was moved if there was a worker to move it to, and the report says which.
      assertEquals(reports.head.movedTasks, if workers == 1 then 0 else 1, reports.head.message)
      val caveat = if workers == 1 then "Nothing could be moved" else "still wait"
      assert(reports.head.message.contains(caveat), reports.head.message)
    }
  }

  test("a future that blocks its worker's thread on one it spawned is not left waiting for it, and is reported") {
    // What it spawned is in its own worker's inbox, alone, which calls for no thief: the monitor moves it.
    val reports = reportsDuring { handler =>
      onPool(4, handler) {
        val blocked = Thread.currentThread()
        val started = new java.util.concurrent.CountDownLatch(1)
        val spawned = Future {
          started.countDown()
          Thread.currentThread()
        }
        assert(started.await(5, java.util.concurrent.TimeUnit.SECONDS), "the spawned future never ran")
        assert(spawned.await ne blocked)
      }
    }
    assertEquals(reports.map(_.movedTasks), List(1))
  }

  test("tasks that come to a worker after it was found starved are moved as well, with no second report") {
    val reports = reportsDuring { handler =>
      onPool(2, handler) {
        val blocked = Thread.currentThread()
        val first = new java.util.concurrent.CountDownLatch(1)
        Future(first.countDown())
        assert(first.await(5, java.util.concurrent.TimeUnit.SECONDS), "the first future never ran")
        // Reported by now, and still on the same turn: what is spawned here is behind a worker known to be kept.
        val later = new java.util.concurrent.CountDownLatch(2)
        val threads = java.util.concurrent.ConcurrentHashMap.newKeySet[Thread]()
        for _ <- 1 to 2 do
          Future {
            threads.add(Thread.currentThread()): Unit
            later.countDown()
          }
        assert(later.await(5, java.util.concurrent.TimeUnit.SECONDS), "the later futures never ran")
        assert(!threads.contains(blocked))
      }
    }
    assertEquals(reports.size, 1, reports.map(_.message).mkString("\n"))
  }

  test("on one worker nothing can be moved: the task waits until the worker comes back to it") {
    val reports = reportsDuring { handler =>
      onPool(1, handler) {
        val started = new java.util.concurrent.CountDownLatch(1)
        val spawned = Future(started.countDown())
        assert(!started.await(400, java.util.concurrent.TimeUnit.MILLISECONDS), "ran while its only worker was blocked")
        spawned.await
      }
    }
    assertEquals(reports.map(_.movedTasks), List(0))
  }

  test("a worker whose thread is blocked while a timer of its own is due is reported, with nothing in its inbox") {
    var blocked: Thread | Null = null
    val reports = reportsDuring { handler =>
      onPool(1, handler) { io ?=>
        blocked = Thread.currentThread()
        val sleeping = Future(io.perform(io.ops.timer(20))) // waits on this worker's heap, not in its inbox
        io.perform(io.ops.timer(1)) // so that it has begun its wait
        Thread.sleep(400)
        sleeping.await
      }
    }
    assertEquals(reports.map(_.worker), List(blocked.nn))
  }

  test("a worker that waits in its ops, or blocks with nothing waiting for it, is not reported") {
    val reports = reportsDuring { handler =>
      onPool(1, handler) { io ?=>
        val ticking = Future { for _ <- 1 to 30 do io.perform(io.ops.timer(10)) }
        io.perform(io.ops.timer(250)) // a long wait, with the other future's tasks coming and going
        ticking.await
        Thread.sleep(250) // blocked, but keeping nothing
      }
    }
    assertEquals(reports.map(_.message), Nil)
  }

  test("what a run opened and left open, of every kind, is closed as the run ends") {
    given KqueueBackend.type = KqueueBackend
    val ops = KqueueOps
    val path = java.nio.file.Files.createTempFile("gears-run-end", ".txt")
    val left = Io.run { io ?=>
      // With the `Async` of whichever future performs it: the program's own is not a future's it spawned.
      def opening[K <: AnyRef](build: Slot[K] => ops.Op)(using Async): K = {
        val into = Slot[K]()
        io.perform(build(into))
        into.clear()
      }
      val listener = opening[ops.Listener](ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), _))
      val address = ops.localAddress(listener).asInstanceOf[IpSocketAddress]
      val connecting = Future(opening[ops.Connection](ops.connect(address, _)))
      val accepted = opening[ops.Connection](ops.accept(listener, _))
      val connected = connecting.await
      val datagram = opening[ops.BoundDatagram](ops.bindDatagram(IpSocketAddress(IpAddress.IPv4Loopback, 0), _))
      val file = opening[ops.File](ops.open(path.toString, false, _))
      val read = Slot[ops.Readable]()
      val write = Slot[ops.Writable]()
      io.perform(ops.pipe(read, write))
      val closedByProgram = opening[ops.BoundDatagram](ops.bindDatagram(IpSocketAddress(IpAddress.IPv4Loopback, 0), _))
      io.perform(ops.close(closedByProgram))
      List[ops.Handle](listener, accepted, connected, datagram, file, read.clear(), write.clear(), closedByProgram)
    }
    try for handle <- left do assertEquals(handle.getState, shareable.HandleState.ClosedState, handle.toString)
    finally java.nio.file.Files.deleteIfExists(path): Unit
  }

  test("a handle is refused by a run other than the one that opened it, while that one still runs") {
    given KqueueBackend.type = KqueueBackend
    val ops = KqueueOps
    val path = java.nio.file.Files.createTempFile("gears-foreign", ".txt")
    try
      Io.run { io ?=>
        def opening[K <: AnyRef](build: Slot[K] => ops.Op)(using Async): K = {
          val into = Slot[K]()
          io.perform(build(into))
          into.clear()
        }
        val read = Slot[ops.Readable]()
        val write = Slot[ops.Writable]()
        io.perform(ops.pipe(read, write))
        val (in, out) = (read.clear(), write.clear())
        val file = opening[ops.File](ops.open(path.toString, false, _))
        val listener = opening[ops.Listener](ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), _))
        val buf = java.nio.ByteBuffer.allocateDirect(8)
        // Each kind of op that uses a handle, built here and performed by another run, on a thread of its own.
        val foreign = List[(String, ops.Op)](
          "read" -> ops.read(in, buf),
          "write" -> ops.write(out, buf),
          "file read" -> ops.read(file, buf, 0L),
          "accept" -> ops.accept(listener, Slot[ops.Connection]()),
          "close" -> ops.close(out)
        )
        @volatile var refusals: List[(String, Throwable | Null)] = Nil
        val other = new Thread(() =>
          refusals = Io.run { theirs ?=>
            // No cast is needed: with the backend known, both runs' ops are `KqueueOps`, and their types the same.
            foreign.map((name, op) =>
              name -> (try { theirs.perform(op); null }
              catch case failed: Exception => failed)
            )
          }
        )
        other.start()
        io.perform(ops.blocking(() => other.join())) // off the workers
        for (name, failure) <- refusals do
          assert(failure.isInstanceOf[IllegalArgumentException], s"$name by another run: $failure")
        assertEquals(refusals.size, foreign.size)
        // Refused, they did nothing: the handles are as they were for the run they belong to.
        buf.clear().limit(1)
        io.perform(ops.write(out, buf))
        buf.clear()
        io.perform(ops.read(in, buf))
        assertEquals(buf.position(), 1)
        io.perform(ops.close(out))
      }
    finally java.nio.file.Files.deleteIfExists(path): Unit
  }

  test("an op that uses no handle is no run's: built outside, it is performed by one run and then by another") {
    given KqueueBackend.type = KqueueBackend
    val timer = KqueueOps.timer(1) // repeatable
    val ran = AtomicInteger(0)
    for _ <- 1 to 2 do
      Io.run { io ?=>
        io.perform(timer)
        io.perform(KqueueOps.blocking(() => ran.incrementAndGet(): Unit))
      }
    assertEquals(ran.get(), 2)
  }

  test("an op being performed in one run is refused by another meanwhile, and is the first run's still") {
    given KqueueBackend.type = KqueueBackend
    val timer = KqueueOps.timer(200)
    Io.run { io ?=>
      val waiting = Future(io.perform(timer))
      io.perform(KqueueOps.timer(20)) // so that it waits
      @volatile var refusal: Throwable | Null = null
      val other = new Thread(() =>
        refusal = Io.run { theirs ?=>
          try { theirs.perform(timer); null }
          catch case failed: Exception => failed
        }
      )
      other.start()
      io.perform(KqueueOps.blocking(() => other.join()))
      assert(refusal.isInstanceOf[IllegalStateException], String.valueOf(refusal))
      waiting.await // undisturbed
    }
  }

  test("a run's Io is refused on a thread that is not one of its pool's workers, another run's among them") {
    given KqueueBackend.type = KqueueBackend
    Io.run { io ?=>
      @volatile var refusal: Throwable | Null = null
      val other = new Thread(() =>
        refusal = Io.run { theirs ?=>
          // The first run's `Io`, from a future of this one: performing with it would run on no worker of its pool.
          try { io.perform(KqueueOps.timer(1)); null }
          catch case failed: Exception => failed
        }
      )
      other.start()
      io.perform(KqueueOps.blocking(() => other.join()))
      assert(refusal.isInstanceOf[IllegalStateException], String.valueOf(refusal))
    }
  }
}
