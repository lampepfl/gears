import gears.async.Async.race
import gears.async.Future
import gears.async.Future.Promise
import gears.async.Async
import gears.async.Listener
import gears.async.given
import scala.util.Success
import gears.async.Listener.ListenerLock
import gears.async.Listener.LockContext
import java.util.concurrent.atomic.AtomicBoolean

class ListenerBehavior extends munit.FunSuite:
  given munit.Assertions = this

  test("race two futures"):
    val prom1 = Promise[Unit]()
    val prom2 = Promise[Unit]()
    Async.blocking:
      val raced = race(Future { prom1.future.value ; 10 }, Future { prom2.future.value ; 20 })
      assert(!raced.poll(Listener.acceptingListener(x => fail(s"race uncomplete $x"))))
      prom1.complete(Success(()))
      assertEquals(Async.await(raced).get, 10)

  test("lock two listeners"):
    val listener1 = Listener.acceptingListener[Int](x => assertEquals(x, 1))
    val listener2 = Listener.acceptingListener[Int](x => assertEquals(x, 2))
    val (lock1, lock2) = Listener.lockBoth(listener1, listener2).right.get
    lock1.complete(1)
    lock2.complete(2)

  test("lock two listeners, one fails"):
    var listener1Locked = false
    val listener1 = new Listener[Nothing]:
      def tryLock()(using LockContext): Option[ListenerLock[Nothing]] =
        listener1Locked = true
        Some(new ListenerLock[Nothing] {
          def complete(data: Nothing): Unit =
            fail("should not succeed")
            listener1Locked = false
          def release(): Unit = listener1Locked = false
        })
    val listener2 = new Listener[Nothing]:
      def tryLock()(using LockContext): Option[ListenerLock[Nothing]] = None

    assertEquals(Listener.lockBoth(listener1, listener2), Left(listener2))
    assert(!listener1Locked)

    assertEquals(Listener.lockBoth(listener2, listener1), Left(listener2))
    assert(!listener1Locked)

  test("lock two races"):
    val source1 = TSource()
    val source2 = TSource()

    Async.race(source1).onComplete(Listener.acceptingListener[Int](x => assertEquals(x, 1)))
    Async.race(source2).onComplete(Listener.acceptingListener[Int](x => assertEquals(x, 2)))

    val (lock1, lock2) = Listener.lockBoth(source1.listener.get, source2.listener.get).right.get
    lock1.complete(1)
    lock2.complete(2)

  test("lock two races in reverse order"):
    val source1 = TSource()
    val source2 = TSource()

    Async.race(source1).onComplete(Listener.acceptingListener[Int](x => assertEquals(x, 1)))
    Async.race(source2).onComplete(Listener.acceptingListener[Int](x => assertEquals(x, 2)))

    val (lock2, lock1) = Listener.lockBoth(source2.listener.get, source1.listener.get).right.get
    lock1.complete(1)
    lock2.complete(2)

  test("lock two nested races"):
    val source1 = TSource()
    val source2 = TSource()

    val race1 = Async.race(source1)
    Async.race(Async.race(source2)).onComplete(Listener.acceptingListener[Int](x => assertEquals(x, 2)))
    Async.race(race1).onComplete(Listener.acceptingListener[Int](x => assertEquals(x, 1)))

    val (lock1, lock2) = Listener.lockBoth(source1.listener.get, source2.listener.get).right.get
    lock1.complete(1)
    lock2.complete(2)

  test("lock two nested races in reverse order"):
    val source1 = TSource()
    val source2 = TSource()

    val race1 = Async.race(source1)
    Async.race(Async.race(source2)).onComplete(Listener.acceptingListener[Int](x => assertEquals(x, 2)))
    Async.race(race1).onComplete(Listener.acceptingListener[Int](x => assertEquals(x, 1)))

    val (lock2, lock1) = Listener.lockBoth(source2.listener.get, source1.listener.get).right.get
    lock1.complete(1)
    lock2.complete(2)

  test("race successful without wait"):
    val source1 = TSource()
    val source2 = TSource()
    val listener = TestListener(false, false, 1)
    Async.race(source1, source2).onComplete(listener)

    assert(source1.listener.isDefined)
    assert(source2.listener.isDefined)

    val lock = source1.listener.get.tryLock()
    assert(lock.isDefined)

    Async.blocking:
      val l2 = source2.listener.get
      val f = Future(assert(l2.tryLock().isEmpty))
      lock.get.complete(1)
      assert(source1.listener.isEmpty)
      assert(source2.listener.isEmpty)
      f.value

  test("race successful with wait"):
    import LockExtension.completeAndCount
    val source1 = TSource()
    val source2 = TSource()
    val listener = TestListener(true, false, 1)
    Async.race(source1, source2).onComplete(listener)

    Async.blocking:
      val f1 = Future(source1.listener.get.tryLock().completeAndCount(1))
      listener.waitWaiter()
      val f2 = Future(source2.listener.get.tryLock().completeAndCount(1))
      assertEquals(f1.value + f2.value, 1)

    assert(source1.listener.isEmpty)
    assert(source2.listener.isEmpty)

  test("race failed without wait"):
    val source1 = TSource()
    val source2 = TSource()
    val listener = TestListener(false, true, 1)
    Async.race(source1, source2).onComplete(listener)

    assert(source1.listener.get.tryLock().isEmpty)
    assert(source1.listener.isEmpty)
    assert(source2.listener.isEmpty)

  test("lockBoth of race with competing lock"):
    // listener with smallest number
    val other = new NumberedTestListener(true, false, 1)

    val source1 = TSource()
    val source2 = TSource()
    Async.race(source1, source2).onComplete(NumberedTestListener(false, false, 1))
    val s1listener = source1.listener.get

    Async.blocking:
      val f1 = Future(Listener.lockBoth(s1listener, other))
      other.waitWaiter()
      source2.listener.get.tryLock().get.complete(1)
      other.continue()
      assertEquals(f1.value, Left(s1listener))

object LockExtension:
  extension[T] (lockOpt: Option[ListenerLock[T]])
    def completeAndCount(value: T) =
      lockOpt match
        case None => 0
        case Some(lock) =>
          lock.complete(value)
          1

private class TestListener private(sleep: AtomicBoolean, fail: Boolean, expected: Int)(using asst: munit.Assertions) extends Listener[Int]:
  private var waiter: Option[Promise[Unit]] = None
  private val lock = new ListenerLock[Int]:
    def release(): Unit = ()
    def complete(data: Int): Unit = asst.assertEquals(data, expected)

  def this(sleep: Boolean, fail: Boolean, expected: Int)(using munit.Assertions) =
    this(AtomicBoolean(sleep), fail, expected)

  def tryLock()(using LockContext) =
    if sleep.getAndSet(false) then
      Async.blocking:
        waiter = Some(Promise())
        waiter.get.future.value
    waiter.foreach: promise =>
      promise.complete(Success(()))
      waiter = None
    if fail then None
    else Some(lock)

  def waitWaiter() =
    while waiter.isEmpty do Thread.`yield`()

  def continue() = waiter.get.complete(Success(()))

private class NumberedTestListener(sleep: Boolean, fail: Boolean, expected: Int)(using munit.Assertions) extends TestListener(sleep, fail, expected) with Listener.LockingListener:
  override def tryLock()(using ctx: LockContext) =
    ctx.beforeLock(this)
    super.tryLock()

private class TSource(using asst: munit.Assertions) extends Async.Source[Int]:
  var listener: Option[Listener[Int]] = None
  def poll(k: Listener[Int]): Boolean = ???
  def onComplete(k: Listener[Int]): Unit =
    assert(listener.isEmpty)
    listener = Some(k)
  def dropListener(k: Listener[Int]): Unit =
    if listener.isDefined then
      asst.assertEquals(k, listener.get)
        listener = None
