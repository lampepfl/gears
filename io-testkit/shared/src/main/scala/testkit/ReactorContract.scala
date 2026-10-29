package gears.async.io
package testkit

import gears.async.io.internal.Completion
import gears.async.io.internal.CompletionFailed
import gears.async.io.internal.Reactor
import gears.async.io.internal.ReactorBackend

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

import munit.FunSuite

/** What every backend's reactors must do, as `Reactor` and `Ops` describe it, checked through the interface alone, so
  * that code written against it runs the same on any backend. A backend's suite extends this, and the contracts of the
  * capabilities its backend has (`SocketContract`, `FileContract`, `ResolveContract`, `UnixStreamContract`,
  * `UnixDatagramContract`, `FifoContract`), with the backend; buffers come from the platform's `BufferArena`. Each
  * check drives one reactor, or two, from the test's thread, and uses handles only through ops. The streams here are
  * pipes, the simplest a backend has.
  */
trait ReactorContract extends FunSuite {

  /** The backend under test: a reactor backend with blocking ops and pipes, which these checks also cover. */
  val backend: ReactorBackend & Blocking & Pipes

  /** Where the checks' buffers come from, freed when the suite ends. */
  private lazy val arena = gears.async.io.testkit.BufferArena.open()

  /** A buffer of `capacity` bytes that this backend's ops can fill and drain, live until the suite ends. */
  protected def allocate(capacity: Int): ByteBuffer = arena.allocate(capacity)

  override def afterAll(): Unit = {
    arena.close()
    super.afterAll()
  }

  /** Checks this backend is known to fail, by name: they are expected to fail, so one that starts passing is reported,
    * to be taken off the list.
    */
  def knownFailures: Set[String] = Set.empty

  override def test(options: munit.TestOptions)(body: => Any)(using loc: munit.Location): Unit =
    super.test(if knownFailures(options.name) then options.fail else options)(body)

  protected type R = Reactor.Of[backend.Op, backend.Handle]

  /** The backend's handles. */
  protected type Handle = backend.Handle
  protected type Readable = backend.Readable
  protected type Writable = backend.Writable
  protected type Connection = backend.Connection
  protected type Listener = backend.Listener
  protected type BoundDatagram = backend.BoundDatagram
  protected type ConnectedDatagram = backend.ConnectedDatagram
  protected type UnixBoundDatagram = backend.UnixBoundDatagram
  protected type UnixConnectedDatagram = backend.UnixConnectedDatagram
  protected type File = backend.File

  /** Opens something on a free port: no interface tells which port was bound, so it tries a few. */
  protected def onFreePort[K <: Handle](open: Int => K): (K, Int) = {
    val random = new java.util.Random()
    var attempts = 0
    var result: (K, Int) | Null = null
    while result == null do {
      val port = 20000 + random.nextInt(30000)
      try result = (open(port), port)
      catch {
        case e: IOException =>
          attempts += 1
          if attempts > 10 then throw e
      }
    }
    result
  }

  protected def withReactor(body: R => Unit): Unit = {
    val reactor = backend.open()
    try body(reactor)
    finally reactor.close()
  }

  /** Runs `op` on `r`, turning until it is done, and returns its failure, if any. */
  protected def run(r: R, op: backend.Op)(using munit.Location): Throwable | Null = {
    val outcome = Outcome()
    r.submit(op, outcome)
    turnUntil(r)(outcome.finished)
    outcome.failure
  }

  /** Closes `handle` through `r`, as the interface has it, and throws the close's failure. */
  protected def close(r: R, handle: Handle)(using munit.Location): Unit = {
    val failure = run(r, backend.ops.close(handle))
    if failure != null then throw failure
  }

  /** Closes `handle` from a thread with no reactor, on a reactor opened for it. */
  protected def closeAlone(handle: Handle)(using munit.Location): Unit = withReactor(r => close(r, handle))

  /** Runs `op`, which opens a handle into `into`, on `r`, and returns the handle; throws the op's failure. */
  protected def opened[K <: Handle](r: R, into: Slot[K], op: backend.Op)(using munit.Location): K = {
    val failure = run(r, op)
    if failure != null then throw failure
    into.clear()
  }

  /** Opens a pipe through `r`, as the interface has it: its read end and its write end. */
  protected def pipe(r: R)(using munit.Location): (Readable, Writable) = {
    val read = Slot[Readable]()
    val write = Slot[Writable]()
    val failure = run(r, backend.ops.pipe(read, write))
    if failure != null then throw failure
    (read.clear(), write.clear())
  }

  /** Opens a pipe from a thread with no reactor, on a reactor opened for it. */
  protected def pipeAlone()(using munit.Location): (Readable, Writable) = {
    var ends: (Readable, Writable) | Null = null
    withReactor(r => ends = pipe(r))
    ends.nn
  }

  /** Turns `reactors` without blocking until `cond` holds, failing after a few seconds. */
  protected def turnUntil(reactors: Reactor*)(cond: => Boolean)(using loc: munit.Location): Unit = {
    val deadline = System.nanoTime() + 5_000_000_000L
    while !cond do {
      if System.nanoTime() > deadline then fail(s"timed out waiting for the reactor at line ${loc.line}")
      reactors.foreach(_.runOnce(block = false))
    }
  }

  protected def bytes(text: String): ByteBuffer = {
    val encoded = text.getBytes(StandardCharsets.UTF_8)
    val buf = allocate(math.max(encoded.length, 1))
    buf.put(encoded).flip()
    buf
  }

  protected def text(buf: ByteBuffer): String = {
    val copy = buf.duplicate()
    copy.flip()
    val out = new Array[Byte](copy.remaining())
    copy.get(out)
    new String(out, StandardCharsets.UTF_8)
  }

  protected def tempDir(): Path = Files.createTempDirectory("gears-io")

  protected def deleteAll(dir: Path): Unit = {
    val entries = Files.list(dir)
    try entries.forEach(Files.deleteIfExists(_): Unit)
    finally entries.close()
    Files.deleteIfExists(dir): Unit
  }

  /** Completions that record what happened to an op. */
  protected final class Outcome extends Completion[backend.Op] {
    @volatile var completed = 0
    @volatile var failure: Throwable | Null = null
    @volatile var cancelled = 0
    def onComplete(op: backend.Op): Unit = completed += 1
    override def onFailure(op: backend.Op, t: Throwable): Unit = failure = t
    override def onCancel(op: backend.Op): Unit = cancelled += 1
    def finished: Boolean = completed > 0 || failure != null || cancelled > 0
  }

  test("a timer completes after its delay, and never inside submit") {
    withReactor { r =>
      val outcome = Outcome()
      val started = System.nanoTime()
      r.submit(backend.ops.timer(20), outcome)
      assertEquals(outcome.completed, 0)
      turnUntil(r)(outcome.completed > 0)
      assert(System.nanoTime() - started >= 20_000_000L, "the timer fired early")
    }
  }

  test("timers fire in deadline order, and a blocking turn wakes for the earliest") {
    withReactor { r =>
      val fired = scala.collection.mutable.ArrayBuffer.empty[Int]
      for delay <- List(30, 10, 20) do r.submit(backend.ops.timer(delay), _ => fired += delay)
      while fired.size < 3 do r.runOnce(block = true)
      assertEquals(fired.toList, List(10, 20, 30))
    }
  }

  test("a timer of a duration finer than a millisecond completes no earlier than it") {
    withReactor { r =>
      val outcome = Outcome()
      val started = System.nanoTime()
      r.submit(
        backend.ops.timer(scala.concurrent.duration.Duration(1500, java.util.concurrent.TimeUnit.MICROSECONDS)),
        outcome
      )
      turnUntil(r)(outcome.completed > 0)
      assert(System.nanoTime() - started >= 1_500_000L, "the timer fired early")
    }
  }

  test("a deadline timer moved later completes at the new deadline, with no cancel, its first deadline passing") {
    withReactor { r =>
      val start = System.nanoTime()
      val timer = backend.ops.deadlineTimer(start + 20_000_000L)
      val outcome = Outcome()
      r.submit(timer, outcome)
      timer.moveTo(start + 80_000_000L)
      timer.moveTo(start + 120_000_000L) // moved again, as QUIC's timers are for almost every packet
      turnUntil(r)(outcome.finished)
      assertEquals(outcome.completed, 1)
      assert(System.nanoTime() - start >= 120_000_000L, "it fired at a deadline it had left")
      assertEquals(timer.deadline, start + 120_000_000L)
    }
  }

  test("a deadline timer moved earlier from another thread wakes a reactor blocked waiting for it") {
    withReactor { r =>
      val timer = backend.ops.deadlineTimer(System.nanoTime() + 60_000_000_000L)
      val outcome = Outcome()
      r.submit(timer, outcome)
      val mover = new Thread(() => {
        Thread.sleep(50)
        timer.moveTo(System.nanoTime())
      })
      val started = System.nanoTime()
      mover.start()
      turnUntil(r)(outcome.finished) // blocks in the reactor until the move wakes it
      mover.join()
      assertEquals(outcome.completed, 1)
      assert(System.nanoTime() - started < 10_000_000_000L, "it waited for its first deadline")
    }
  }

  test("coalesced earlier moves observe the latest deadline and can notify again after a poll") {
    withReactor { r =>
      val start = System.nanoTime()
      val timer = backend.ops.deadlineTimer(start + 60_000_000_000L)
      val outcome = Outcome()
      r.submit(timer, outcome)
      def moveBatch(finalDeadline: Long): Unit = {
        val mover = new Thread(() => {
          for i <- 1 to 1000 do timer.moveTo(start + 60_000_000_000L - i)
          timer.moveTo(finalDeadline)
        })
        mover.start()
        mover.join(5000)
        assert(!mover.isAlive)
      }
      moveBatch(start + 30_000_000_000L)
      r.runOnce(block = false)
      assert(!outcome.finished)
      moveBatch(System.nanoTime() - 1)
      turnUntil(r)(outcome.finished)
      assertEquals(outcome.completed, 1)
    }
  }

  for oldHeapOccupied <- List(false, true) do
    test(s"a queued deadline move cannot touch reuse on another poller (old heap occupied: $oldHeapOccupied)") {
      withReactor { first =>
        withReactor { second =>
          val timer = backend.ops.deadlineTimer(System.nanoTime() + 60_000_000_000L)
          val previous = Outcome()
          first.submit(timer, previous)
          val mover = new Thread(() => timer.moveTo(System.nanoTime() + 30_000_000_000L))
          mover.start()
          mover.join(5000)
          assert(!mover.isAlive)
          assert(first.cancel(timer))
          assertEquals(previous.cancelled, 1)

          val sibling = backend.ops.timer(60000)
          val siblingOutcome = Outcome()
          if oldHeapOccupied then first.submit(sibling, siblingOutcome)
          val current = Outcome()
          second.submit(timer, current)
          first.runOnce(block = false) // Processes the stale move, whose heap index now names the second heap.
          assert(!current.finished)
          assert(!siblingOutcome.finished)
          if oldHeapOccupied then {
            assert(first.cancel(sibling))
            assertEquals(siblingOutcome.cancelled, 1)
          }
          timer.moveTo(System.nanoTime() - 1)
          turnUntil(second)(current.finished)
          assertEquals(current.completed, 1)
          assertEquals(current.cancelled, 0)
          assertEquals(previous.cancelled, 1)
        }
      }
    }

  test("a deadline timer completes at once for a deadline passed, and performed again at its deadline as it stands") {
    withReactor { r =>
      val timer = backend.ops.deadlineTimer(System.nanoTime() - 1_000_000L)
      val first = Outcome()
      r.submit(timer, first)
      assertEquals(first.completed, 0, "never inside submit")
      turnUntil(r)(first.finished)
      assertEquals(first.completed, 1)
      val start = System.nanoTime()
      timer.moveTo(start + 30_000_000L) // not pending: the next submission starts from it
      val second = Outcome()
      r.submit(timer, second)
      turnUntil(r)(second.finished)
      assertEquals(second.completed, 1)
      assert(System.nanoTime() - start >= 30_000_000L)
    }
  }

  test("a deadline timer's time limit fails it if the limit comes first, the deadline moved past it or not") {
    withReactor { r =>
      val timer = backend.ops.deadlineTimer(System.nanoTime() + 10_000_000L)
      val outcome = Outcome()
      r.submit(timer, outcome, timeoutMillis = 40)
      timer.moveTo(System.nanoTime() + 60_000_000_000L)
      turnUntil(r)(outcome.finished)
      assert(outcome.failure.isInstanceOf[TimeoutException], String.valueOf(outcome.failure))
    }
  }

  test("a completion that throws is reported, with its op, and the turn goes on to the other completions") {
    withReactor { r =>
      val reported = scala.collection.mutable.ArrayBuffer.empty[Throwable]
      r.reportFailure = reported += _
      val throwing = backend.ops.timer(0)
      val outcome = Outcome()
      r.submit(throwing, _ => throw new IllegalStateException("a bug in the completion"))
      r.submit(backend.ops.timer(0), outcome) // due in the same turn, after the throwing one
      turnUntil(r)(outcome.completed > 0)
      assertEquals(reported.size, 1, reported.mkString)
      reported.head match {
        case failed: CompletionFailed =>
          assert(failed.op.asInstanceOf[AnyRef] eq throwing.asInstanceOf[AnyRef])
          assert(failed.getCause.isInstanceOf[IllegalStateException], failed.getCause)
        case other => fail(s"reported $other")
      }
      // A completion that handles none of a failure: the default onFailure throws it, and that is reported the same way.
      reported.clear()
      val unhandled = backend.ops.timer(50)
      r.submit(unhandled, _ => (), 0) // its time limit is up before it fires, so it fails with TimeoutException
      turnUntil(r)(reported.nonEmpty)
      reported.head match {
        case failed: CompletionFailed =>
          assert(failed.op.asInstanceOf[AnyRef] eq unhandled.asInstanceOf[AnyRef])
          assert(failed.getCause.isInstanceOf[TimeoutException], failed.getCause)
        case other => fail(s"reported $other")
      }
    }
  }

  test("an op on a closed handle fails, and what reuses the handle's I/O object gets nothing of it") {
    withReactor { r =>
      val (in, out) = pipe(r)
      val built = backend.ops.write(out, bytes("stale")) // built before the close, submitted after
      close(r, in)
      close(r, out)
      val again = run(r, backend.ops.close(out))
      assert(again.isInstanceOf[ClosedChannelException], s"closing it again: $again")
      val (in2, out2) = pipe(r) // on kqueue, most likely the same two descriptor numbers
      try {
        val early = run(r, built)
        assert(early.isInstanceOf[ClosedChannelException], s"an op built before the close: $early")
        val late = run(r, backend.ops.write(out, bytes("stale")))
        assert(late.isInstanceOf[ClosedChannelException], s"an op built after the close: $late")
        assertEquals(run(r, backend.ops.write(out2, bytes("new"))), null)
        val buf = allocate(16)
        assertEquals(run(r, backend.ops.read(in2, buf)), null)
        assertEquals(text(buf), "new")
      } finally {
        close(r, in2)
        close(r, out2)
      }
    }
  }

  test("a read waits for data written later, then completes with it") {
    withReactor { r =>
      val (in, out) = pipe(r)
      try {
        val buf = allocate(64)
        val outcome = Outcome()
        r.submit(backend.ops.read(in, buf), outcome)
        for _ <- 1 to 5 do r.runOnce(block = false)
        assertEquals(outcome.completed, 0)
        val sent = Outcome()
        r.submit(backend.ops.write(out, bytes("hello")), sent)
        turnUntil(r)(outcome.finished && sent.finished)
        assertEquals(outcome.completed, 1)
        assertEquals(text(buf), "hello")
      } finally {
        close(r, in)
        close(r, out)
      }
    }
  }

  test("a read at the end of the stream completes without adding bytes") {
    withReactor { r =>
      val (in, out) = pipe(r)
      try {
        close(r, out)
        val buf = allocate(16)
        val outcome = Outcome()
        r.submit(backend.ops.read(in, buf), outcome)
        turnUntil(r)(outcome.finished)
        assertEquals(outcome.completed, 1)
        assertEquals(buf.position(), 0)
      } finally close(r, in)
    }
  }

  test("a read into a buffer with no room fails at once, reading nothing, whether or not bytes are waiting") {
    withReactor { r =>
      val (in, out) = pipe(r)
      try {
        val full = allocate(4)
        full.position(full.limit())
        val read = backend.ops.read(in, full)
        def refused(): Unit = {
          val outcome = Outcome()
          r.submit(read, outcome)
          turnUntil(r)(outcome.finished)
          assert(outcome.failure.isInstanceOf[IllegalArgumentException], String.valueOf(outcome.failure))
        }
        refused()
        val sent = Outcome()
        r.submit(backend.ops.write(out, bytes("hello")), sent)
        turnUntil(r)(sent.finished)
        refused()
        assertEquals(full.position(), full.limit())
        val buf = allocate(16)
        val outcome = Outcome()
        r.submit(backend.ops.read(in, buf), outcome)
        turnUntil(r)(outcome.finished)
        assertEquals(text(buf), "hello", "the refused reads took none of the bytes")
      } finally {
        close(r, in)
        close(r, out)
      }
    }
  }

  test("a write with nothing remaining completes, writing nothing, even with the reader gone, and fails once closed") {
    withReactor { r =>
      val (in, out) = pipe(r)
      val empty = allocate(4)
      empty.limit(0)
      val write = backend.ops.write(out, empty)
      try {
        close(r, in) // a write of bytes would fail now; one of nothing touches nothing
        assertEquals(run(r, write), null)
        assertEquals(run(r, write), null)
      } finally close(r, out)
      val failure = run(r, write)
      assert(failure.isInstanceOf[java.io.IOException], s"a closed handle fails first: $failure")
    }
  }

  /** Submits `read` until it completes without adding bytes, returning what `buf` then holds. */
  protected def readToEnd(r: R, read: backend.Op, buf: ByteBuffer)(using munit.Location): String = {
    var atEnd = false
    while !atEnd do {
      val before = buf.position()
      val outcome = Outcome()
      r.submit(read, outcome)
      turnUntil(r)(outcome.finished)
      assertEquals(outcome.completed, 1, String.valueOf(outcome.failure))
      atEnd = buf.position() == before
    }
    text(buf)
  }

  test("a write to a pipe whose reader has closed fails with an IOException, not a signal that ends the process") {
    withReactor { r =>
      val (in, out) = pipe(r)
      close(r, in)
      try {
        val failure = run(r, backend.ops.write(out, bytes("nobody reads this")))
        assert(failure.isInstanceOf[IOException], String.valueOf(failure))
      } finally close(r, out)
    }
  }

  test("a pipe's reads end after the bytes written before its writer closed") {
    withReactor { r =>
      val (in, out) = pipe(r)
      try {
        val sent = Outcome()
        r.submit(backend.ops.write(out, bytes("then the end")), sent)
        turnUntil(r)(sent.finished)
        close(r, out)
        val buf = allocate(64)
        assertEquals(readToEnd(r, backend.ops.read(in, buf), buf), "then the end")
      } finally close(r, in)
    }
  }

  test("an immediate completion that resubmits itself leaves the next batch and a sibling timer their turns") {
    withReactor { r =>
      val (in, out) = pipe(r)
      close(r, out)
      try {
        val read = backend.ops.read(in, allocate(1)) // EOF is immediately ready on every submission.
        var completed = 0
        lazy val again: Completion[backend.Op] = _ => {
          completed += 1
          if completed < 16 then r.submit(read, again)
        }
        val sibling = Outcome()
        r.submit(read, again)
        r.submit(backend.ops.timer(0), sibling)
        for turn <- 1 to 16 do {
          r.runOnce(block = false)
          assertEquals(completed, turn)
          assertEquals(sibling.completed, 1)
        }
      } finally close(r, in)
    }
  }

  test("writes fill a pipe, wait while it is full, and go on as it drains") {
    withReactor { r =>
      val (in, out) = pipe(r)
      try {
        val size = 1 << 20
        val source = allocate(size)
        while source.hasRemaining do source.put((source.position() % 251).toByte)
        source.flip()
        val sink = allocate(size)
        val write = backend.ops.write(out, source)
        val read = backend.ops.read(in, sink)
        lazy val onWrite: Completion[backend.Op] = _ => if source.hasRemaining then r.submit(write, onWrite)
        lazy val onRead: Completion[backend.Op] = _ => if sink.hasRemaining then r.submit(read, onRead)
        r.submit(write, onWrite)
        r.submit(read, onRead)
        turnUntil(r)(!sink.hasRemaining)
        r.runOnce(block = false) // the last read's completion, deferred to this turn, finds nothing left to read
        sink.flip()
        var i = 0
        while i < size do {
          if sink.get(i) != (i % 251).toByte then fail(s"byte $i is wrong")
          i += 1
        }
      } finally {
        close(r, in)
        close(r, out)
      }
    }
  }

  test("cancelling a pending read runs onCancel, and the op may then be submitted again") {
    withReactor { r =>
      val (in, out) = pipe(r)
      try {
        val buf = allocate(16)
        val read = backend.ops.read(in, buf)
        val first = Outcome()
        r.submit(read, first)
        r.runOnce(block = false)
        assert(r.cancel(read))
        assertEquals(first.cancelled, 1)
        assertEquals(first.completed, 0)
        assert(!r.cancel(read), "a cancelled op is no longer pending")
        val second = Outcome()
        r.submit(read, second)
        r.submit(backend.ops.write(out, bytes("again")), Outcome())
        turnUntil(r)(second.finished)
        assertEquals(second.completed, 1)
        assertEquals(first.completed, 0)
        assertEquals(text(buf), "again")
      } finally {
        close(r, in)
        close(r, out)
      }
    }
  }

  test("a read with a time limit fails with TimeoutException when nothing comes, and may then be submitted again") {
    withReactor { r =>
      val (in, out) = pipe(r)
      try {
        val buf = allocate(16)
        val read = backend.ops.read(in, buf)
        val first = Outcome()
        val started = System.nanoTime()
        r.submit(read, first, timeoutMillis = 20)
        turnUntil(r)(first.finished)
        assert(first.failure.isInstanceOf[TimeoutException], String.valueOf(first.failure))
        assert(System.nanoTime() - started >= 20_000_000L, "the read timed out early")
        assertEquals(buf.position(), 0) // nothing was read
        assert(!r.cancel(read), "a timed-out op is no longer pending")
        val second = Outcome()
        r.submit(read, second, timeoutMillis = 5_000)
        r.submit(backend.ops.write(out, bytes("in time")), Outcome())
        turnUntil(r)(second.finished)
        assertEquals(second.completed, 1)
        assertEquals(text(buf), "in time")
        // Its time limit went with it: nothing more comes of the read once it is done.
        val later = Outcome()
        r.submit(backend.ops.timer(30), later)
        turnUntil(r)(later.finished)
        assertEquals(second.completed, 1)
        assertEquals(second.failure, null)
        assertEquals(first.completed, 0)
      } finally {
        close(r, in)
        close(r, out)
      }
    }
  }

  test("a timer with a shorter time limit fails with TimeoutException when the limit is up") {
    withReactor { r =>
      val outcome = Outcome()
      val started = System.nanoTime()
      r.submit(backend.ops.timer(60_000), outcome, timeoutMillis = 20)
      turnUntil(r)(outcome.finished)
      assert(outcome.failure.isInstanceOf[TimeoutException], String.valueOf(outcome.failure))
      assert(System.nanoTime() - started < 30_000_000_000L)
      val again = Outcome()
      r.submit(backend.ops.timer(1), again, timeoutMillis = 5_000) // the op again, within its limit
      turnUntil(r)(again.finished)
      assertEquals(again.completed, 1)
    }
  }

  test("a blocking op past its time limit is interrupted, and fails once it has returned") {
    withReactor { r =>
      val returned = new AtomicBoolean
      val op = backend.ops.blocking { () =>
        try Thread.sleep(10_000)
        catch case _: InterruptedException => ()
        returned.set(true)
      }
      val outcome = Outcome()
      r.submit(op, outcome, timeoutMillis = 20)
      turnUntil(r)(outcome.finished)
      assert(outcome.failure.isInstanceOf[TimeoutException], String.valueOf(outcome.failure))
      assert(returned.get(), "onFailure ran before the body returned")
      assertEquals(outcome.completed, 0)
      assertEquals(outcome.cancelled, 0)
    }
  }

  test("an op that is not Repeatable cannot be submitted twice") {
    withReactor { r =>
      val op = backend.ops.blocking(() => ())
      val outcome = Outcome()
      r.submit(op, outcome)
      turnUntil(r)(outcome.finished)
      intercept[IllegalStateException](r.submit(op, Outcome()))
    }
  }

  test("a blocking op runs off the reactor thread and writes its value into its slot") {
    withReactor { r =>
      val into = Slot[String]()
      val reactorThread = Thread.currentThread()
      val outcome = Outcome()
      r.submit(
        backend.ops.blocking(() => if Thread.currentThread() eq reactorThread then "same" else "other", into),
        outcome
      )
      turnUntil(r)(outcome.finished)
      assertEquals(outcome.completed, 1)
      assertEquals(into.clear(), "other")
    }
  }

  test("a blocking op's failure fails the op") {
    withReactor { r =>
      val outcome = Outcome()
      r.submit(backend.ops.blocking(() => throw new IllegalArgumentException("boom")), outcome)
      turnUntil(r)(outcome.finished)
      assert(outcome.failure.isInstanceOf[IllegalArgumentException], String.valueOf(outcome.failure))
    }
  }

  test("cancelling a running blocking op interrupts it, and onCancel waits for it to return") {
    withReactor { r =>
      val started = new CountDownLatch(1)
      val returned = new AtomicBoolean
      val op = backend.ops.blocking { () =>
        started.countDown()
        try Thread.sleep(10_000)
        catch case _: InterruptedException => ()
        returned.set(true)
      }
      val outcome = Outcome()
      r.submit(op, outcome)
      started.await()
      assert(r.cancel(op))
      turnUntil(r)(outcome.cancelled > 0)
      assert(returned.get(), "onCancel ran before the body returned")
      assertEquals(outcome.completed, 0)
    }
  }

  test("a cancel that comes once a blocking op has written its value is too late: the op completes with it") {
    withReactor { r =>
      val returning = new CountDownLatch(1)
      val into = Slot[String]()
      val op = backend.ops.blocking(
        () => {
          returning.countDown()
          "value"
        },
        into
      )
      val outcome = Outcome()
      r.submit(op, outcome)
      returning.await()
      Thread.sleep(50) // for the value to be written once the body returns; the reactor does not turn meanwhile
      assert(!r.cancel(op), "the cancel took an op whose value was written")
      turnUntil(r)(outcome.finished)
      assertEquals((outcome.completed, outcome.cancelled), (1, 0))
      assertEquals(into.clear(), "value")
    }
  }

  test("an op submitted with a slot not yet cleared fails with IllegalArgumentException, doing nothing") {
    withReactor { r =>
      val into = Slot[String]()
      assertEquals(run(r, backend.ops.blocking(() => "first", into)), null)
      val ran = new AtomicBoolean
      val failure = run(
        r,
        backend.ops.blocking(
          () => {
            ran.set(true)
            "second"
          },
          into
        )
      )
      assert(failure.isInstanceOf[IllegalArgumentException], String.valueOf(failure))
      assert(!ran.get(), "the body ran")
      assertEquals(into.clear(), "first")

      val read = Slot[Readable]()
      val write = Slot[Writable]()
      assertEquals(run(r, backend.ops.pipe(read, write)), null)
      val in = read.clear()
      val refused = run(r, backend.ops.pipe(read, write)) // `write` still holds the first pipe's end
      assert(refused.isInstanceOf[IllegalArgumentException], String.valueOf(refused))
      intercept[IllegalStateException](read.clear()) // nothing opened
      close(r, in)
      close(r, write.clear())
    }
  }

  test("a repeatable op moves between two reactors of the backend") {
    withReactor { a =>
      withReactor { b =>
        val (in, out) = pipe(a)
        try {
          val buf = allocate(16)
          val read = backend.ops.read(in, buf)
          val first = Outcome()
          a.submit(read, first)
          a.submit(backend.ops.write(out, bytes("a")), Outcome())
          turnUntil(a)(first.finished)
          val second = Outcome()
          b.submit(read, second)
          for _ <- 1 to 3 do b.runOnce(block = false)
          b.submit(backend.ops.write(out, bytes("b")), Outcome())
          turnUntil(a, b)(second.finished)
          assertEquals((first.completed, second.completed), (1, 1))
          assertEquals(text(buf), "ab")
        } finally {
          close(a, in)
          close(a, out)
        }
      }
    }
  }

  test("closing a handle that a reactor blocked on another thread has used ends its peer's stream") {
    val (in, out) = pipeAlone()
    val registered = new CountDownLatch(1)
    @volatile var other: Reactor | Null = null
    val blocked = new Thread(() => {
      val b = backend.open()
      other = b
      b.submit(backend.ops.write(out, bytes("x")), _ => registered.countDown())
      b.run() // blocks between turns until stopped
      b.close()
    })
    blocked.start()
    try {
      registered.await()
      Thread.sleep(20) // so that the other reactor is blocked again
      closeAlone(out)
      withReactor { r =>
        val buf = allocate(16)
        assertEquals(readToEnd(r, backend.ops.read(in, buf), buf), "x")
      }
    } finally {
      val b = other
      if b != null then {
        b.stop()
        b.wakeup()
      }
      blocked.join()
      closeAlone(in)
    }
  }

  test("closing a handle fails the op pending on it") {
    val (in, out) = pipeAlone()
    try
      withReactor { r =>
        val outcome = Outcome()
        r.submit(backend.ops.read(in, allocate(16)), outcome)
        r.runOnce(block = false) // the read is registered, and waiting
        closeAlone(in)
        turnUntil(r)(outcome.finished)
        assert(outcome.failure.isInstanceOf[ClosedChannelException], String.valueOf(outcome.failure))
        assertEquals(outcome.completed, 0)
      }
    finally closeAlone(out)
  }

  test("closing a handle fails the op pending on it on a reactor blocked on another thread") {
    val (in, out) = pipeAlone()
    val registered = new CountDownLatch(1)
    val failed = new CountDownLatch(1)
    @volatile var failure: Throwable | Null = null
    @volatile var other: Reactor | Null = null
    val blocked = new Thread(() => {
      val b = backend.open()
      other = b
      b.submit(
        backend.ops.read(in, allocate(16)),
        new Completion[backend.Op] {
          def onComplete(op: backend.Op): Unit = failed.countDown()
          override def onFailure(op: backend.Op, t: Throwable): Unit = {
            failure = t
            failed.countDown()
          }
        }
      )
      b.submit(backend.ops.timer(0), _ => registered.countDown())
      b.run() // blocks between turns until stopped
      b.close()
    })
    blocked.start()
    try {
      registered.await()
      Thread.sleep(20) // so that the other reactor is blocked again
      closeAlone(in)
      assert(failed.await(5, java.util.concurrent.TimeUnit.SECONDS), "the pending read was not ended by the close")
      assert(failure.isInstanceOf[ClosedChannelException], String.valueOf(failure))
    } finally {
      val b = other
      if b != null then {
        b.stop()
        b.wakeup()
      }
      blocked.join()
      closeAlone(out)
    }
  }

  test("closing a reactor cancels its pending ops") {
    val (in, out) = pipeAlone()
    try {
      val reactor = backend.open()
      val read = Outcome()
      val timer = Outcome()
      reactor.submit(backend.ops.read(in, allocate(16)), read)
      reactor.submit(backend.ops.timer(60_000), timer)
      reactor.runOnce(block = false)
      reactor.close()
      assertEquals((read.cancelled, timer.cancelled), (1, 1))
      assertEquals((read.completed, timer.completed), (0, 0))
    } finally {
      closeAlone(in)
      closeAlone(out)
    }
  }

  test("a blocking op with a time limit that finishes in time leaves no timer behind, and the reactor closes cleanly") {
    val reactor = backend.open()
    val ran = new java.util.concurrent.atomic.AtomicInteger
    for submission <- 1 to 3 do {
      val outcome = Outcome()
      reactor.submit(backend.ops.blocking(() => ran.incrementAndGet(): Unit), outcome, timeoutMillis = 5_000)
      turnUntil(reactor)(outcome.finished)
      assertEquals(outcome.completed, 1, s"submission $submission: ${outcome.failure}")
    }
    assertEquals(ran.get(), 3)
    reactor.close() // well before the limits are due, with nothing left of them in the reactor
  }

  test("closing a reactor waits for a running blocking op with a time limit, and cancels it once it has returned") {
    val reactor = backend.open()
    val started = new CountDownLatch(1)
    val returned = new AtomicBoolean
    val op = backend.ops.blocking { () =>
      started.countDown()
      try Thread.sleep(10_000)
      catch case _: InterruptedException => ()
      returned.set(true)
    }
    val outcome = Outcome()
    reactor.submit(op, outcome, timeoutMillis = 60_000)
    started.await()
    reactor.close()
    assertEquals(outcome.cancelled, 1)
    assert(returned.get(), "onCancel ran before the body returned")
  }

  test("closing a reactor gives an op that already finished its outcome, not a cancel") {
    val (in, out) = pipeAlone()
    try {
      withReactor(r => assertEquals(run(r, backend.ops.write(out, bytes("kept"))), null))
      val reactor = backend.open()
      val buf = allocate(16)
      val read = Outcome()
      reactor.submit(backend.ops.read(in, buf), read) // finishes as it is submitted, its completion left for a turn
      reactor.close()
      assertEquals((read.completed, read.cancelled), (1, 0))
      assertEquals(text(buf), "kept")
    } finally {
      closeAlone(in)
      closeAlone(out)
    }
  }

  test("submitting to a closed reactor throws IllegalStateException") {
    val reactor = backend.open()
    reactor.close()
    intercept[IllegalStateException](reactor.submit(backend.ops.timer(0), Outcome()))
  }

  test("an op on a closed handle fails") {
    withReactor { r =>
      val (in, out) = pipe(r)
      close(r, out)
      close(r, in)
      val outcome = Outcome()
      r.submit(backend.ops.read(in, allocate(16)), outcome)
      turnUntil(r)(outcome.finished)
      assert(outcome.failure.isInstanceOf[ClosedChannelException], String.valueOf(outcome.failure))
    }
  }

  // ---- Reads into the backend's own buffers

  /** The bytes a pooled read received, as text, consuming them. */
  private def textOf(received: Received): String = {
    val buf = received.buffer
    val out = new Array[Byte](buf.remaining())
    buf.get(out)
    new String(out, StandardCharsets.UTF_8)
  }

  test("a pooled read fills a buffer of the pool's, which release gives back") {
    val pool = backend.pool(bufferSize = 64, buffers = 2)
    try
      withReactor { r =>
        val (in, out) = pipe(r)
        try {
          val into = Slot[Received]()
          val outcome = Outcome()
          r.submit(backend.ops.read(in, pool, into), outcome)
          for _ <- 1 to 3 do r.runOnce(block = false)
          assertEquals(outcome.completed, 0)
          r.submit(backend.ops.write(out, bytes("pooled")), Outcome())
          turnUntil(r)(outcome.finished)
          assertEquals(outcome.completed, 1, String.valueOf(outcome.failure))
          val received = into.clear()
          assertEquals(received.buffer.position(), 0)
          assertEquals(textOf(received), "pooled")
          assertEquals(pool.available, 1)
          received.release()
          assertEquals(pool.available, 2)
        } finally {
          close(r, in)
          close(r, out)
        }
      }
    finally pool.close()
  }

  test("pooled reads preserve full buffers and the trailing partial buffer across reuse") {
    val pool = backend.pool(bufferSize = 8, buffers = 1)
    try
      withReactor { r =>
        val (in, out) = pipe(r)
        val into = Slot[Received]()
        val read = backend.ops.read(in, pool, into)
        try {
          r.submit(backend.ops.write(out, bytes("abcdefghijklmnopqrs")), Outcome())
          for expected <- List("abcdefgh", "ijklmnop", "qrs") do {
            val outcome = Outcome()
            r.submit(read, outcome)
            turnUntil(r)(outcome.finished)
            assertEquals(outcome.completed, 1, String.valueOf(outcome.failure))
            val received = into.clear()
            try assertEquals(textOf(received), expected)
            finally received.release()
          }
          assertEquals(pool.available, 1)
        } finally {
          close(r, in)
          close(r, out)
        }
      }
    finally pool.close()
  }

  test("a pooled read at the end of the stream completes with an empty buffer") {
    val pool = backend.pool(bufferSize = 16, buffers = 1)
    try
      withReactor { r =>
        val (in, out) = pipe(r)
        try {
          close(r, out)
          val into = Slot[Received]()
          val outcome = Outcome()
          r.submit(backend.ops.read(in, pool, into), outcome)
          turnUntil(r)(outcome.finished)
          assertEquals(outcome.completed, 1, String.valueOf(outcome.failure))
          val received = into.clear()
          assertEquals(received.buffer.remaining(), 0)
          received.release()
        } finally close(r, in)
      }
    finally pool.close()
  }

  test("a pooled read fails when the pool has no buffer free, and goes on once one is released") {
    val pool = backend.pool(bufferSize = 16, buffers = 1)
    try
      withReactor { r =>
        val (in, out) = pipe(r)
        try {
          r.submit(backend.ops.write(out, bytes("one")), Outcome())
          val first = Slot[Received]()
          val got = Outcome()
          r.submit(backend.ops.read(in, pool, first), got)
          turnUntil(r)(got.finished)
          assertEquals(got.completed, 1, String.valueOf(got.failure))
          val held = first.clear()
          assertEquals(pool.available, 0)
          r.submit(backend.ops.write(out, bytes("two")), Outcome())
          val second = Slot[Received]()
          val refused = Outcome()
          r.submit(backend.ops.read(in, pool, second), refused)
          turnUntil(r)(refused.finished)
          assert(refused.failure.isInstanceOf[IOException], String.valueOf(refused.failure))
          held.release()
          val again = Outcome()
          r.submit(backend.ops.read(in, pool, second), again)
          turnUntil(r)(again.finished)
          assertEquals(again.completed, 1, String.valueOf(again.failure))
          val received = second.clear()
          assertEquals(textOf(received), "two")
          received.release()
        } finally {
          close(r, in)
          close(r, out)
        }
      }
    finally pool.close()
  }

  /** A pooled read of what `out` is sent, `text`, from `in`, run to its end: what it received, which the caller
    * releases.
    */
  private def receivedOf(r: R, in: Readable, out: Writable, pool: BufferPool, text: String)(using
      munit.Location
  ): Received = {
    r.submit(backend.ops.write(out, bytes(text)), Outcome())
    val into = Slot[Received]()
    val outcome = Outcome()
    r.submit(backend.ops.read(in, pool, into), outcome)
    turnUntil(r)(outcome.finished)
    assertEquals(outcome.completed, 1, String.valueOf(outcome.failure))
    into.clear()
  }

  test("releasing a Received twice throws IllegalStateException, leaving the pool as it was") {
    val pool = backend.pool(bufferSize = 16, buffers = 2)
    try
      withReactor { r =>
        val (in, out) = pipe(r)
        try {
          val received = receivedOf(r, in, out, pool, "once")
          received.release()
          intercept[IllegalStateException](received.release())
          assertEquals(pool.available, 2)
          val next = receivedOf(r, in, out, pool, "next") // the free list is whole
          val other = receivedOf(r, in, out, pool, "other")
          assertEquals(pool.available, 0)
          next.release()
          other.release()
        } finally {
          close(r, in)
          close(r, out)
        }
      }
    finally pool.close()
  }

  test("closing a pool with a buffer held fails, leaving it open, for the buffer to be released and the close done") {
    val pool = backend.pool(bufferSize = 16, buffers = 1)
    try
      withReactor { r =>
        val (in, out) = pipe(r)
        try {
          val held = receivedOf(r, in, out, pool, "held")
          intercept[IllegalArgumentException](pool.close())
          assertEquals(textOf(held), "held")
          held.release()
          assertEquals(pool.available, 1, "the release reached the pool")
          receivedOf(r, in, out, pool, "again").release() // and the pool still lends it
          pool.close()
        } finally {
          close(r, in)
          close(r, out)
        }
      }
    finally pool.close()
  }

  // ---- Closing as an op

  test("a close op completes after the failure of the op pending on the handle, on its reactor or another") {
    withReactor { a =>
      withReactor { b =>
        val (in, out) = pipe(a)
        val order = scala.collection.mutable.ArrayBuffer.empty[String]
        val read = new Completion[backend.Op] {
          def onComplete(op: backend.Op): Unit = order += "read completed"
          override def onFailure(op: backend.Op, t: Throwable): Unit =
            order += (if t.isInstanceOf[ClosedChannelException] then "read failed" else s"read failed with $t")
        }
        a.submit(backend.ops.read(in, allocate(16)), read)
        a.runOnce(block = false)
        val closed = Outcome()
        a.submit(backend.ops.close(in), op => { order += "closed"; closed.onComplete(op) })
        turnUntil(a)(closed.finished)
        assertEquals(order.toList, List("read failed", "closed"))
        order.clear()
        val (in2, out2) = pipe(a)
        b.submit(backend.ops.read(in2, allocate(16)), read) // waiting on the other reactor
        b.runOnce(block = false)
        val closed2 = Outcome()
        a.submit(backend.ops.close(in2), op => { order += "closed"; closed2.onComplete(op) })
        turnUntil(a, b)(closed2.finished && order.contains("read failed"))
        close(a, out)
        close(a, out2)
      }
    }
  }

  test("a second read pending on a handle, or a second write, fails with IllegalArgumentException, on any reactor") {
    withReactor { a =>
      withReactor { b =>
        val (in, out) = pipe(a)
        try {
          val first = Outcome()
          a.submit(backend.ops.read(in, allocate(16)), first)
          a.runOnce(block = false)
          for r <- List(a, b) do {
            val second = Outcome()
            r.submit(backend.ops.read(in, allocate(16)), second)
            turnUntil(r)(second.finished)
            assert(second.failure.isInstanceOf[IllegalArgumentException], s"a second read: ${second.failure}")
          }
          assert(!first.finished, "the first read is still pending")
          a.submit(backend.ops.write(out, bytes("x")), Outcome())
          turnUntil(a)(first.finished)
          assertEquals(first.completed, 1, String.valueOf(first.failure))
          val again = Outcome()
          b.submit(backend.ops.read(in, allocate(16)), again) // once the first has finished, another may wait
          b.runOnce(block = false)
          a.submit(backend.ops.write(out, bytes("y")), Outcome())
          turnUntil(a, b)(again.finished)
          assertEquals(again.completed, 1, String.valueOf(again.failure))
        } finally {
          close(a, in)
          close(a, out)
        }
      }
    }
  }

  test("an op that opens or closes is one-shot: submitted again, it throws IllegalStateException") {
    withReactor { r =>
      val read = Slot[Readable]()
      val write = Slot[Writable]()
      val opening = backend.ops.pipe(read, write)
      assertEquals(run(r, opening), null)
      val (in, out) = (read.clear(), write.clear())
      intercept[IllegalStateException](r.submit(opening, Outcome()))
      val closing = backend.ops.close(in)
      assertEquals(run(r, closing), null)
      intercept[IllegalStateException](r.submit(closing, Outcome()))
      close(r, out)
    }
  }

  test("a one-shot op submitted on two reactors at once opens once, the other submission refused") {
    for _ <- 1 to 100 do {
      val read = Slot[Readable]()
      val write = Slot[Writable]()
      val opening = backend.ops.pipe(read, write)
      val start = new CountDownLatch(1)
      val refused = new java.util.concurrent.atomic.AtomicInteger()
      val opened = new java.util.concurrent.atomic.AtomicInteger()
      val threads = Seq.fill(2)(new Thread(() => {
        val r = backend.open()
        try {
          start.await()
          val outcome = Outcome()
          try {
            r.submit(opening, outcome)
            turnUntil(r)(outcome.finished)
            if outcome.completed == 1 then opened.incrementAndGet(): Unit
          } catch case _: IllegalStateException => refused.incrementAndGet(): Unit
        } finally r.close()
      }))
      threads.foreach(_.start())
      start.countDown()
      threads.foreach(_.join())
      assertEquals((opened.get, refused.get), (1, 1))
      withReactor { r =>
        close(r, read.clear())
        close(r, write.clear())
      }
    }
  }

  test("a close op completes once the handle is closed, and fails the op pending on it") {
    withReactor { r =>
      val (in, out) = pipe(r)
      val read = Outcome()
      r.submit(backend.ops.read(in, allocate(16)), read)
      r.runOnce(block = false)
      val closed = Outcome()
      r.submit(backend.ops.close(in), closed)
      turnUntil(r)(closed.finished && read.finished)
      assertEquals(closed.completed, 1, String.valueOf(closed.failure))
      assert(read.failure.isInstanceOf[ClosedChannelException], String.valueOf(read.failure))
      val again = Outcome()
      r.submit(backend.ops.close(in), again)
      turnUntil(r)(again.finished)
      assert(again.failure.isInstanceOf[ClosedChannelException], s"closing a closed handle fails: ${again.failure}")
      val outClosed = Outcome()
      r.submit(backend.ops.close(out), outClosed)
      turnUntil(r)(outClosed.finished)
      assertEquals(outClosed.completed, 1, String.valueOf(outClosed.failure))
    }
  }

  /** The bytes of `bufs` read so far, in order, as text. */
  private def readText(bufs: Array[ByteBuffer]): String =
    bufs.map { b =>
      val copy = b.duplicate()
      copy.flip()
      java.nio.charset.StandardCharsets.UTF_8.decode(copy).toString
    }.mkString

  test("a scattering read fills its buffers in order, each before the next, completing once bytes have come") {
    withReactor { r =>
      val (in, out) = pipe(r)
      try {
        assertEquals(run(r, backend.ops.write(out, bytes("hello world"))), null)
        val bufs = Array(allocate(4), allocate(4), allocate(8))
        assertEquals(run(r, backend.ops.read(in, bufs)), null)
        assertEquals(bufs.map(_.position()).toList, List(4, 4, 3))
        assertEquals(readText(bufs), "hello world")
      } finally {
        close(r, in)
        close(r, out)
      }
    }
  }

  test("a scattering read refuses buffers none of which has room, or none, and reads nothing at the end") {
    withReactor { r =>
      val (in, out) = pipe(r)
      try {
        intercept[IllegalArgumentException](backend.ops.read(in, Array.empty[ByteBuffer]))
        val full = allocate(4)
        full.position(4)
        assert(run(r, backend.ops.read(in, Array(full, full.duplicate()))).isInstanceOf[IllegalArgumentException])
        close(r, out)
        val bufs = Array(allocate(4), allocate(4))
        assertEquals(run(r, backend.ops.read(in, bufs)), null)
        assertEquals(bufs.map(_.position()).toList, List(0, 0), "the end of the stream adds nothing")
      } finally close(r, in)
    }
  }

  test("a gathering write drains its buffers in order, and with nothing remaining completes at once") {
    withReactor { r =>
      val (in, out) = pipe(r)
      try {
        val parts = Array(bytes("ab"), bytes(""), bytes("cdef"))
        assertEquals(run(r, backend.ops.write(out, parts)), null)
        assert(parts.forall(!_.hasRemaining()), "all written: a pipe takes six bytes at once")
        assertEquals(run(r, backend.ops.write(out, parts)), null, "nothing remaining")
        val buf = allocate(16)
        assertEquals(run(r, backend.ops.read(in, buf)), null)
        buf.flip()
        assertEquals(java.nio.charset.StandardCharsets.UTF_8.decode(buf).toString, "abcdef")
      } finally {
        close(r, in)
        close(r, out)
      }
    }
  }

  test("any buffer is read into and written from, on the heap or not, single or several; a read-only one is written") {
    withReactor { r =>
      val (in, out) = pipe(r)
      try {
        def heap(text: String) = java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8))
        def received(buf: java.nio.ByteBuffer) = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8)
        val direct = java.nio.ByteBuffer.allocateDirect(4)
        direct.put("dir|".getBytes(StandardCharsets.UTF_8)).flip()
        assertEquals(run(r, backend.ops.write(out, heap("heap|"))), null)
        assertEquals(run(r, backend.ops.write(out, direct)), null)
        assertEquals(run(r, backend.ops.write(out, heap("ro|").asReadOnlyBuffer())), null)
        assertEquals(run(r, backend.ops.write(out, Array(heap("a"), bytes("b"), heap("c|")))), null)
        val into = java.nio.ByteBuffer.allocate(64)
        var got = ""
        while !got.endsWith("c|") do {
          assertEquals(run(r, backend.ops.read(in, into)), null)
          got = received(into)
        }
        assertEquals(got, "heap|dir|ro|abc|")
        assertEquals(run(r, backend.ops.write(out, bytes("scattered"))), null)
        val first = java.nio.ByteBuffer.allocate(4)
        val second = allocate(16)
        var total = 0
        while total < 9 do {
          assertEquals(run(r, backend.ops.read(in, Array(first, second))), null)
          total = first.position() + second.position()
        }
        assertEquals(received(first) + text(second), "scattered")
        intercept[IllegalArgumentException](backend.ops.read(in, java.nio.ByteBuffer.allocate(8).asReadOnlyBuffer()))
      } finally {
        close(r, in)
        close(r, out)
      }
    }
  }
}
