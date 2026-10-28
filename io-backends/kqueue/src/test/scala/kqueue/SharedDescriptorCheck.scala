package gears.async.io
package kqueue

import gears.async.io.internal.Completion
import gears.async.io.internal.NativeBuffer
import gears.async.io.internal.NonBlocking
import gears.async.io.kqueue.KqueueBackend.Handle

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import scala.scalanative.unsafe.Zone

/** Checks that kqueue reactors on different threads can share a descriptor, as a pool's workers will when a task moves
  * from one to another (see `SharedDescriptorSuite`). Two reactors, A and B, each on its own thread, take turns reading
  * a pipe:
  *   - A reads, then B: B's filter takes over, and A drops its own at its next event for the pipe, which a byte left
  *     unread for a moment makes sure of;
  *   - five more bytes arrive with no read pending, each read by B a moment later, while A has nothing to do: A must
  *     not wake for them;
  *   - A reads again;
  *   - the pipe is closed on B's thread and a new one reuses its numbers: A must not take what it knew of the old
  *     descriptor, a registered filter and nothing to read, for the new one, or its read would wait forever.
  * Each read must complete within two seconds.
  */
object SharedDescriptorCheck {

  /** How the check went: its failures, and whether the new pipe reused the old one's numbers. */
  final case class Outcome(failures: Int, reuseChecked: Boolean)

  /** A reactor whose own thread runs the tasks given to it between turns, counting the turns that return. */
  final class Driven(name: String) {
    val reactor: KqueueReactor = KqueueReactor.open()
    private val tasks = new ConcurrentLinkedQueue[Runnable]()
    @volatile private var running = true
    val turns = new AtomicLong
    private val thread = new Thread(() => loop(), name)
    thread.setDaemon(true)
    thread.start()

    private def loop(): Unit = {
      while running do {
        var task = tasks.poll()
        while task != null do {
          task.run()
          task = tasks.poll()
        }
        if running then reactor.runOnce(block = true)
        turns.incrementAndGet()
      }
      reactor.close()
    }

    /** Runs `body` on the reactor's thread, without waiting for it. */
    def post(body: => Unit): Unit = {
      tasks.add(() => body)
      reactor.wakeup()
    }

    /** Runs `body` on the reactor's thread, and waits until it has. */
    def onThread(body: => Unit): Unit = {
      val ran = new CountDownLatch(1)
      tasks.add(() => { body; ran.countDown() })
      reactor.wakeup()
      ran.await()
    }

    def stop(): Unit = {
      running = false
      reactor.wakeup()
    }
  }

  def run(): Outcome = {
    val zone = Zone.open()
    val a = new Driven("share-a")
    val b = new Driven("share-b")
    var failures = 0
    var reuseChecked = true
    def fail(message: String): Unit = {
      failures += 1
      System.err.println(s"share: $message")
    }
    val byte = NativeBuffer.allocate(1)(using zone)

    /** Writes a byte to `writeEnd`, which waits unread until something reads it. */
    def writeByte(writeEnd: Handle): Unit = {
      byte.clear()
      NonBlocking.write(KqueueHandles.fd(writeEnd), byte)
    }

    /** Submits a read of `fd` on `d`, then writes a byte to `writeEnd`, unless it is null, and waits for the read to
      * complete.
      */
    def readOn(d: Driven, label: String, fd: KqueueKinds.Readable, writeEnd: Handle | Null): Unit = {
      val done = new CountDownLatch(1)
      d.onThread {
        val buf = NativeBuffer.allocate(64)(using zone)
        d.reactor.submit(
          KqueueOps.read(fd, buf),
          new Completion[KqueueOp] {
            def onComplete(op: KqueueOp): Unit = done.countDown()
            override def onFailure(op: KqueueOp, failure: Throwable): Unit = {
              fail(s"$label failed: $failure")
              done.countDown()
            }
          }
        )
      }
      if writeEnd != null then writeByte(writeEnd)
      if !done.await(2, TimeUnit.SECONDS) then fail(s"$label never completed")
    }

    /** Waits until a thread has had time to handle what was sent its way. */
    def settle(): Unit = Thread.sleep(100)

    try {
      val (readEnd, writeEnd) = KqueueHandles.pipe()
      readOn(a, "A's first read", readEnd, writeEnd)
      readOn(b, "B's read after A", readEnd, writeEnd)
      // A byte nobody reads at once: A's filter, still registered, reports it, and A, finding that B owns the
      // descriptor now, drops the filter. Then B reads it.
      writeByte(writeEnd)
      settle()
      readOn(b, "B's read of the byte A saw", readEnd, null)
      settle()
      val aTurns = a.turns.get()
      for i <- 1 to 5 do {
        writeByte(writeEnd) // waits unread for a moment, long enough for a filter of A's to report it
        settle()
        readOn(b, s"B's read $i", readEnd, null)
      }
      settle()
      val woke = a.turns.get() - aTurns
      if woke != 0 then fail(s"A woke $woke times for reads that B owns")
      readOn(a, "A's read after B", readEnd, writeEnd)

      b.onThread {
        KqueueHandles.close(readEnd)
        KqueueHandles.close(writeEnd)
      }
      val (newRead, newWrite) = KqueueHandles.pipe()
      if KqueueHandles.descriptor(newRead) != KqueueHandles.descriptor(readEnd) then reuseChecked = false
      readOn(a, "A's read of the reused descriptor", newRead, newWrite)
      KqueueHandles.close(newRead)
      KqueueHandles.close(newWrite)
    } finally {
      a.stop()
      b.stop()
    }
    Outcome(failures, reuseChecked)
  }
}
