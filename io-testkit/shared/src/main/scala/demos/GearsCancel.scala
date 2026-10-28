package gears.io.demos

import gears.async.Async
import gears.async.Future
import gears.async.Future.Promise
import gears.async.io.Blocking
import gears.async.io.Io
import gears.async.io.Pipes
import gears.async.io.Slot
import gears.async.io.testkit.BufferArena
import gears.io.higherlevel.HigherLevel

import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.Failure

import DemoIO.text

/** Mirror of `example.KQueueExampleCancel`. Cancellation is just `Future.cancel()`: the bridge asks the reactor to
  * cancel the op, and the future is rejected when the reactor confirms through the completion's `onCancel`. Cancelling
  * in the reactor is also what frees the read slot for the fresh read afterwards. The reactor has no promise op here:
  * code written against `Reactor` uses gears promises, which do not involve the reactor.
  */
object GearsCancel {

  /** A blocking body that sleeps for `millis`, recording whether cancellation interrupted it. */
  private def sleeper(millis: Long, interrupted: AtomicBoolean): () => Unit = () => {
    try Thread.sleep(millis)
    catch {
      case e: InterruptedException =>
        interrupted.set(true)
        throw e
    }
  }

  /** A blocking body that keeps running for `millis` whatever happens, like a native call that cannot be interrupted.
    */
  private def stubborn(millis: Long, finished: AtomicBoolean): () => String = () => {
    val deadline = System.nanoTime() + millis * 1_000_000L
    while System.nanoTime() < deadline do ()
    finished.set(true)
    "finished"
  }

  /** Waits up to two seconds for `interrupted` to be set. */
  private def interruptedSoon(interrupted: AtomicBoolean): Boolean = {
    val deadline = System.nanoTime() + 2_000_000_000L
    while !interrupted.get() && System.nanoTime() < deadline do Thread.sleep(10)
    interrupted.get()
  }

  private def isCancelled(future: Future[?]): Boolean =
    future.poll().exists {
      case Failure(_: CancellationException) => true
      case _                                 => false
    }

  /** What the program leaves running when it ends, a future it never awaits, and what became of it, which only the end
    * of the program's run settles: what `report` prints once the run has ended.
    */
  final class Leftover {
    private[GearsCancel] val interrupted = new AtomicBoolean() // its task is still running when the program ends
    private[GearsCancel] val notified = new AtomicBoolean() // set when the program's end cancels its future
    private[GearsCancel] val stopped = new AtomicBoolean() // whether its task had stopped by then

    /** Prints what the end of the program's run did to the leftover future: call it once the run has returned. */
    def report(): Unit = {
      println(s"the program's end notified the leftover: ${notified.get()}")
      println(s"the leftover had stopped when notified: ${stopped.get()}")
      println(s"blocking interrupted by the program's end: ${interruptedSoon(interrupted)}")
    }
  }

  /** The program, for a pool of one worker driving a reactor, so that every op goes straight to it. It returns the
    * future it leaves running, whose fate its run's end decides, for `Leftover.report` once the run has returned.
    */
  def run()(using io: Io[Blocking & Pipes], async: Async.Spawn): Leftover = {
    val leftover = new Leftover
    BufferArena.scoped { arena =>
      val buf = arena.allocate(64)
      val (readFd, writeFd) = HigherLevel.pipe()
      try {
        val sleeperInterrupted = new AtomicBoolean()
        // A read with nothing written: cancelled before bytes come, it has taken none of them.
        val read = io.backend.ops.read(readFd, arena.allocate(64))
        val blocking = io.backend.ops.blocking(sleeper(1000, sleeperInterrupted))
        val pending = Seq(
          "read" -> Future(io.perform(read)), // nothing has been written yet
          "blocking" -> Future(io.perform(blocking)),
          "timer" -> Future(io.perform(io.backend.ops.timer(300)))
        )
        // Left running, never awaited: the program's end cancels the future, as it cancels every future still
        // running, and with it the op, and waits for it; `perform` throws once the task has stopped.
        Future {
          try {
            io.perform(io.backend.ops.blocking(sleeper(10000, leftover.interrupted)))
            println("unexpected completion of the leftover task")
          } catch {
            case cancelled: CancellationException =>
              leftover.notified.set(true)
              leftover.stopped.set(leftover.interrupted.get())
              throw cancelled
          }
        }: Unit
        // Cancelled while it runs; it ignores the interrupt, so the future is rejected only once it has finished, and
        // the value it produces after the cancel must not reach its slot.
        val stubbornFinished = new AtomicBoolean()
        val stubbornResult = Slot[String]()
        val stubbornTask =
          Future(io.perform(io.backend.ops.blocking(stubborn(300, stubbornFinished), stubbornResult)))
        println("submitted a read, a blocking task, and a timer")

        io.perform(io.backend.ops.timer(100))
        stubbornTask.cancel()
        for (name, future) <- pending do {
          future.cancel()
          future.awaitResult // rejected once the reactor confirms the cancel, through the completion's onCancel
          println(s"$name cancelled: ${isCancelled(future)}")
        }

        // A blocking op is not Repeatable, so the reactor refuses it a second time, which `perform` throws.
        val refused =
          try {
            io.perform(blocking)
            false
          } catch case _: IllegalStateException => true
        println(s"resubmitting the cancelled blocking task: ${if refused then "refused" else "accepted"}")

        // Closing a handle fails the ops pending on it, on whichever reactor (`Ops.close`), so a future waiting on
        // one is rejected by that failure, with nothing left for a cancel to do.
        val (orphanRead, orphanWrite) = HigherLevel.pipe()
        val reading = Promise[Unit]()
        val orphan = Future {
          reading.complete(scala.util.Success(()))
          io.perform(io.backend.ops.read(orphanRead, arena.allocate(16)))
        }
        // Lets the future start and submit its read before the pipe is closed: on a pool's worker, the read is pending
        // once the future has started; on the JVM's own virtual threads, it is handed to the reactor's thread first,
        // which the timer gives time for.
        reading.await
        io.perform(io.backend.ops.timer(20))
        io.perform(io.backend.ops.close(orphanRead))
        io.perform(io.backend.ops.close(orphanWrite))
        val closedFailed = orphan.awaitResult.failed.toOption.exists(_.isInstanceOf[java.io.IOException])
        orphan.cancel() // nothing left to cancel
        println(s"closing fails a pending read: $closedFailed")

        stubbornTask.awaitResult
        println(s"a running blocking task had stopped before onCancel: ${stubbornFinished.get()}")
        println(s"the cancelled task left its slot empty: ${DemoIO.isEmpty(stubbornResult)}")

        // Data arrives only now; the cancelled read must not see it, and its slot takes a fresh read.
        io.perform(io.backend.ops.write(writeFd, arena.of("hello".getBytes(StandardCharsets.UTF_8))))
        buf.clear()
        io.perform(io.backend.ops.read(readFd, buf))
        buf.flip()
        println(s"read after cancel: `${text(buf)}`")

        // Past the cancelled timer's deadline, with the interrupted sleeper long stopped.
        io.perform(io.backend.ops.timer(500))
        val late = pending.count((_, future) => !isCancelled(future))
        println(s"blocking interrupted: ${sleeperInterrupted.get()}")
        println(s"cancelled completions run: $late")
        // Each future can only have been rejected by its completion's onCancel.
        println(s"cancel notifications: ${pending.count((_, future) => isCancelled(future))}")
      } finally {
        io.perform(io.backend.ops.close(readFd))
        io.perform(io.backend.ops.close(writeFd))
      }
    }
    leftover
  }
}
