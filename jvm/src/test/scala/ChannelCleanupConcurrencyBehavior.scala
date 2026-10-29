import gears.async.Async.Source
import gears.async.{Async, BufferedChannel, Channel, Listener, SyncChannel}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}

class ChannelCleanupConcurrencyBehavior extends munit.FunSuite:
  private def await(latch: CountDownLatch): Unit =
    val arrived = latch.await(5, TimeUnit.SECONDS)
    assert(arrived, "timed out waiting for the other channel operation")

  // Daemon threads and bounded waits let the test report a lock cycle without hanging the test process.
  private def worker(failure: AtomicReference[Throwable])(body: => Unit): Thread =
    val thread = new Thread(() =>
      try body
      catch
        case t: Throwable =>
          failure.compareAndSet(null, t)
          ()
    )
    thread.setDaemon(true)
    thread.start()
    thread

  private def join(thread: Thread): Unit =
    thread.join(10000)
    assert(!thread.isAlive, s"channel operation did not finish: ${thread.getStackTrace.mkString("\n")}")

  test("two selects can clean up while completing under opposite channel locks"):
    val a = SyncChannel[Int]()
    val b = SyncChannel[Int]()
    val firstCompleting = CountDownLatch(1)
    val bothCompleting = CountDownLatch(2)
    val failure = AtomicReference[Throwable]()

    Async
      .race(a.readSource, b.readSource)
      .onComplete(Listener.acceptingListener { (value, _) =>
        assertEquals(value, Right(1))
        firstCompleting.countDown()
        bothCompleting.countDown()
        await(bothCompleting)
      })
    Async
      .race(a.readSource, b.readSource)
      .onComplete(Listener.acceptingListener { (value, _) =>
        assertEquals(value, Right(2))
        bothCompleting.countDown()
        await(bothCompleting)
      })

    val senderA = worker(failure):
      val sent = a.sendSource(1).poll()
      assertEquals(sent, Some(Right(())))
    val senderB = worker(failure):
      await(firstCompleting)
      val sent = b.sendSource(2).poll()
      assertEquals(sent, Some(Right(())))
    join(senderA)
    join(senderB)
    Option(failure.get()).foreach(throw _)
    a.close()
    b.close()

  test("dropping a buffered sender does not wait for another thread holding the channel lock"):
    val c = BufferedChannel[Int](1)
    assertEquals(c.sendSource(0).poll(), Some(Right(())))
    val deadSource = c.sendSource(1)
    val dead = new Listener[Either[Channel.Closed, Unit]]:
      val lock = new Listener.ListenerLock with Listener.NumberedLock:
        val selfNumber = number
        def acquire(): Boolean = false
        def release(): Unit = ()
      def complete(value: Either[Channel.Closed, Unit], source: Source[Either[Channel.Closed, Unit]]): Unit =
        fail("cancelled sender must not complete")
    deadSource.onComplete(dead)
    val live = AtomicBoolean(false)
    c.sendSource(2)
      .onComplete(Listener.acceptingListener { (value, _) =>
        assertEquals(value, Right(()))
        live.set(true)
      })

    val reading = CountDownLatch(1)
    val dropped = CountDownLatch(1)
    val failure = AtomicReference[Throwable]()
    val reader = worker(failure):
      val read = c.readSource.poll(Listener.acceptingListener { (value, _) =>
        assertEquals(value, Right(0))
        reading.countDown()
        await(dropped)
      })
      assert(read)
    val canceller = worker(failure):
      await(reading)
      deadSource.dropListener(dead)
      dropped.countDown()
    join(reader)
    join(canceller)
    Option(failure.get()).foreach(throw _)
    assert(live.get())
    assertEquals(c.readSource.poll(), Some(Right(2)))
    c.close()
