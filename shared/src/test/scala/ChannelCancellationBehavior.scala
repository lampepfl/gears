import gears.async.Async.{Source, SourceId}
import gears.async.{Async, BufferedChannel, Channel, Listener, SyncChannel, UnboundedChannel}

import scala.collection.mutable.ArrayBuffer

class ChannelCancellationBehavior extends munit.FunSuite:
  // These tests drive sources synchronously so that cancellation and queue order are deterministic.
  private class Probe[T] extends Listener[T]:
    var active = true
    var attempts = 0
    val received = ArrayBuffer[T]()
    val lock = new Listener.ListenerLock with Listener.NumberedLock:
      val selfNumber = number
      def acquire(): Boolean =
        attempts += 1
        active
      def release(): Unit = ()
    def complete(value: T, source: SourceId): Unit =
      assert(active)
      active = false
      received += value

  private def channels = List(SyncChannel[Int](), BufferedChannel[Int](1), UnboundedChannel[Int]())

  test("buffered reads skip cancelled senders and refill from the next live sender"):
    val c = BufferedChannel[Int](1)
    assertEquals(c.sendSource(0).poll(), Some(Right(())))
    val dead = List.fill(2)(new Probe[Either[Channel.Closed, Unit]])
    dead.zipWithIndex.foreach { (k, i) => c.sendSource(i + 1).onComplete(k) }
    val first = new Probe[Either[Channel.Closed, Unit]]
    val second = new Probe[Either[Channel.Closed, Unit]]
    c.sendSource(3).onComplete(first)
    c.sendSource(4).onComplete(second)
    // Dropping is optional. Leave cancelled registrations in place, as a failed tryLock does.
    dead.foreach(_.active = false)

    assertEquals(c.readSource.poll(), Some(Right(0)))
    assertEquals(first.received.toList, List(Right(())))
    assert(second.received.isEmpty)
    assertEquals(c.readSource.poll(), Some(Right(3)))
    assertEquals(second.received.toList, List(Right(())))
    assertEquals(c.readSource.poll(), Some(Right(4)))
    assertEquals(c.readSource.poll(), None)
    assert(dead.forall(_.received.isEmpty))
    c.close()

  test("failed race acquisition preserves the next queued reader"):
    for c <- channels do
      val dead = new Probe[Either[Channel.Closed, Int]]
      Async.race(c.readSource).onComplete(dead)
      val live = new Probe[Either[Channel.Closed, Int]]
      c.readSource.onComplete(live)
      dead.active = false

      // The race calls dropListener from acquire while matching is visiting its queue entry.
      assertEquals(c.sendSource(7).poll(), Some(Right(())))
      assertEquals(live.received.toList, List(Right(7)))
      assert(dead.received.isEmpty)
      assertEquals(c.readSource.poll(), None)
      c.close()

  test("failed race acquisition preserves the next queued sender"):
    val c = SyncChannel[Int]()
    val dead = new Probe[Either[Channel.Closed, Unit]]
    Async.race(c.sendSource(1)).onComplete(dead)
    val live = new Probe[Either[Channel.Closed, Unit]]
    c.sendSource(2).onComplete(live)
    dead.active = false

    assertEquals(c.readSource.poll(), Some(Right(2)))
    assertEquals(live.received.toList, List(Right(())))
    assert(dead.received.isEmpty)
    assertEquals(c.readSource.poll(), None)
    c.close()

  test("reentrant sender drops do not prevent buffered refill"):
    val c = BufferedChannel[Int](1)
    assertEquals(c.sendSource(0).poll(), Some(Right(())))
    val deadSource = c.sendSource(1)
    val dead = new Probe[Either[Channel.Closed, Unit]]
    val live = new Probe[Either[Channel.Closed, Unit]]
    deadSource.onComplete(dead)
    c.sendSource(2).onComplete(live)

    assert(c.readSource.poll(Listener.acceptingListener { (value, _) =>
      assertEquals(value, Right(0))
      dead.active = false
      deadSource.dropListener(dead)
    }))
    assertEquals(live.received.toList, List(Right(())))
    assertEquals(c.readSource.poll(), Some(Right(2)))
    assert(dead.received.isEmpty)
    c.close()

  test("uncontended drops remove dead readers from idle channels"):
    for c <- channels do
      val dead = new Probe[Either[Channel.Closed, Int]]
      c.readSource.onComplete(dead)
      dead.active = false
      c.readSource.dropListener(dead)
      c.close()
      assertEquals(dead.attempts, 0)

  test("uncontended drops remove dead senders from idle channels"):
    for c <- List(SyncChannel[Int](), BufferedChannel[Int](1)) do
      c.sendSource(0).poll() // Fill the buffer, if any.
      val source = c.sendSource(1)
      val dead = new Probe[Either[Channel.Closed, Unit]]
      source.onComplete(dead)
      dead.active = false
      source.dropListener(dead)
      c.close()
      assertEquals(dead.attempts, 0)

  test("close skips dead readers left in the queue and completes live readers"):
    for c <- channels do
      val dead = new Probe[Either[Channel.Closed, Int]]
      val live = new Probe[Either[Channel.Closed, Int]]
      c.readSource.onComplete(dead)
      c.readSource.onComplete(live)
      dead.active = false
      c.close()
      assert(dead.received.isEmpty)
      assertEquals(live.received.toList, List(Left(Channel.Closed)))

  test("close skips dead senders left in the queue and completes live senders"):
    for c <- List(SyncChannel[Int](), BufferedChannel[Int](1)) do
      c.sendSource(0).poll() // Fill the buffer, if any.
      val dead = new Probe[Either[Channel.Closed, Unit]]
      val live = new Probe[Either[Channel.Closed, Unit]]
      c.sendSource(1).onComplete(dead)
      c.sendSource(2).onComplete(live)
      dead.active = false
      c.close()
      assert(dead.received.isEmpty)
      assertEquals(live.received.toList, List(Left(Channel.Closed)))
