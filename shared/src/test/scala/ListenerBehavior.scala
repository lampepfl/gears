import language.experimental.captureChecking

import gears.async.Async
import gears.async.Async.Source
import gears.async.Async.race
import gears.async.Future
import gears.async.Future.Promise
import gears.async.Listener
import gears.async.Listener.ListenerLock
import gears.async.default.given
import gears.async.listeners.ConflictingLocksException
import gears.async.listeners.lockBoth

import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable.Buffer
import scala.util.Success

class ListenerBehavior extends munit.FunSuite:
  given munit.Assertions = this

  test("race two futures"):
    val prom1 = Promise[Unit]()
    val prom2 = Promise[Unit]()
    Async.blocking:
      val raced = race(Future { prom1.await; 10 }, Future { prom2.await; 20 })
      assert(!raced.poll(Listener.acceptingListener((x, _) => fail(s"race uncomplete $x"))))
      prom1.complete(Success(()))
      assertEquals(raced.await, 10)

  test("lock two listeners"):
    val listener1 = Listener.acceptingListener[Int]((x, _) => assertEquals(x, 1))
    val listener2 = Listener.acceptingListener[Int]((x, _) => assertEquals(x, 2))
    assertEquals(lockBoth(listener1, listener2), true)
    listener1.complete(1, Dummy)
    listener2.complete(2, Dummy)

  test("lock two listeners where one fails"):
    var listener1Locked = false
    val listener1 = new Listener[Nothing]:
      val lock = null
      def complete(data: Nothing, src: Async.SourceSymbol[Nothing]): Unit =
        fail("should not succeed")
      def release() =
        listener1Locked = false
    val listener2 = NumberedTestListener(false, true, 1)

    assertEquals(lockBoth(listener1, listener2), listener2)
    assert(!listener1Locked)

    assertEquals(lockBoth(listener2, listener1), listener2)
    assert(!listener1Locked)

  test("lock two races"):
    val source1 = TSource()
    val source2 = TSource()

    Async.race(source1).onComplete(Listener.acceptingListener[Int]((x, _) => assertEquals(x, 1)))
    Async.race(source2).onComplete(Listener.acceptingListener[Int]((x, _) => assertEquals(x, 2)))

    assert(lockBoth(source1.listener.get, source2.listener.get) == true)
    source1.completeWith(1)
    source2.completeWith(2)

  test("lock two races in reverse order"):
    val source1 = TSource()
    val source2 = TSource()

    Async.race(source1).onComplete(Listener.acceptingListener[Int]((x, _) => assertEquals(x, 1)))
    Async.race(source2).onComplete(Listener.acceptingListener[Int]((x, _) => assertEquals(x, 2)))

    assert(lockBoth(source2.listener.get, source1.listener.get) == true)
    source1.completeWith(1)
    source2.completeWith(2)

  test("lock two nested races"):
    val source1 = TSource()
    val source2 = TSource()

    val race1 = Async.race(source1)
    Async.race(Async.race(source2)).onComplete(Listener.acceptingListener[Int]((x, _) => assertEquals(x, 2)))
    Async.race(race1).onComplete(Listener.acceptingListener[Int]((x, _) => assertEquals(x, 1)))

    assert(lockBoth(source1.listener.get, source2.listener.get) == true)
    source1.completeWith(1)
    source2.completeWith(2)

  test("lock two nested races in reverse order"):
    val source1 = TSource()
    val source2 = TSource()

    val race1 = Async.race(source1)
    Async.race(Async.race(source2)).onComplete(Listener.acceptingListener[Int]((x, _) => assertEquals(x, 2)))
    Async.race(race1).onComplete(Listener.acceptingListener[Int]((x, _) => assertEquals(x, 1)))

    assert(lockBoth(source2.listener.get, source1.listener.get) == true)
    source1.completeWith(1)
    source2.completeWith(2)

  test("race successful without wait"):
    val source1 = TSource()
    val source2 = TSource()
    assert(source1 != source2)
    val listener = TestListener(1)
    Async.race(source1, source2).onComplete(listener)

    assert(source1.listener.isDefined)
    assert(source2.listener.isDefined)

    val lock = source1.listener.get.acquireLock()
    assertEquals(lock, true)

    Async.blocking:
      val l2 = source2.listener.get
      val f = Future(assertEquals(l2.acquireLock(), false))
      source1.completeWith(1)
      assert(source1.listener.isEmpty)
      assert(source2.listener.isEmpty)
      f.await

  test("race successful with wait"):
    val source1 = TSource()
    val source2 = TSource()
    val listener = NumberedTestListener(true, false, 1)
    Async.race(source1, source2).onComplete(listener)

    val l2 = source2.listener.get

    Async.blocking:
      val f1 = Future(source1.completeNowWith(1))
      listener.sleeping.await
      listener.continue()
      val f2 = Future:
        val completed = l2.completeNow(1, source2)
        if completed then source2.dropListener(l2) // usually the source will do it by default
        completed
      assert(f1.await || f2.await)
      assert(!f1.await || !f2.await)
      println(s"${f1.await} ${f2.await}")

    assert(source1.listener.isEmpty)
    assert(source2.listener.isEmpty)

  test("race polling"):
    val source1 = new Async.Source[Int]():
      override def poll(k: Listener[Int]^): Boolean = k.completeNow(1, this) || true
      override def onComplete(k: Listener[Int]^): Unit = ???
      override def dropListener(k: Listener[Int]^): Unit = ???
    val source2 = TSource()
    val listener = TestListener(1)

    assert(Async.race(source1, source2).poll(listener))
    assert(Async.race(source2, source1).poll(listener))

  test("race failed without wait"):
    val source1 = TSource()
    val source2 = TSource()
    val listener = NumberedTestListener(false, true, 1)
    Async.race(source1, source2).onComplete(listener)

    assertEquals(source1.lockListener(), false)
    assert(source1.listener.isEmpty)
    assert(source2.listener.isEmpty)

  test("lockBoth of race with competing lock"):
    val source1 = TSource()
    val source2 = TSource()
    Async.race(source1, source2).onComplete(NumberedTestListener(false, false, 1))
    // listener with greatest number
    val other = new NumberedTestListener(true, false, 1)
    val s1listener = source1.listener.get

    Async.blocking:
      val f1 = Future(lockBoth(s1listener, other))
      other.sleeping.await
      assert(source2.listener.get.completeNow(1, source2))
      other.continue()
      assertEquals(f1.await, s1listener)

  test("conflicting locks"):
    val l = NumberedTestListener(false, false, 1)
    val srcs = Seq(TSource(), TSource())
    val Seq(s1, s2) = srcs
    val Seq(l1, l2) = srcs.map(_ => {
      val src = TSource()
      val r = race(src)
      r.onComplete(l)
      src.listener.get
    })
    try
      lockBoth(l1, l2)
      ???
    catch
      case ConflictingLocksException(base) =>
        assert(base == (l1, l2))
    try
      lockBoth(l2, l1)
      ???
    catch
      case ConflictingLocksException(base) =>
        assert(base == (l2, l1))
    try
      lockBoth(l, l2)
      ???
    catch
      case ConflictingLocksException(base) =>
        assert(base == (l, l2))
    try
      lockBoth(l1, l)
      ???
    catch
      case ConflictingLocksException(base) =>
        assert(base == (l1, l))
    try
      lockBoth(l, l)
      ???
    catch
      case ConflictingLocksException(base) =>
        assert(base == (l, l))

  test("failing downstream listener is dropped in race"):
    val source1 = TSource()
    val source2 = TSource()
    val source3 = TSource()
    Async.race(Async.race(source1, source2), source3).onComplete(NumberedTestListener(false, true, 1))
    assert(source1.listener.isDefined)
    assert(source2.listener.isDefined)
    assert(source3.listener.isDefined)

    // downstream listener fails -> should fail -> should drop listener everywhere
    assert(!source1.listener.get.completeNow(1, source1))
    assert(source1.listener.isEmpty)
    assert(source2.listener.isEmpty)
    assert(source3.listener.isEmpty)

  test("succeeding downstream listener is dropped in race"):
    val source1 = TSource()
    val source2 = TSource()
    val source3 = TSource()
    Async.race(Async.race(source1, source2), source3).onComplete(NumberedTestListener(false, false, 1))
    assert(source1.listener.isDefined)
    assert(source2.listener.isDefined)
    assert(source3.listener.isDefined)

    // downstream listener succeeds -> should cleanup, i.e., drop listener everywhere
    assert(source1.listener.get.completeNow(1, source1))
    // do not check source1 because it is assumed to drop the listener itself
    assert(source2.listener.isEmpty)
    assert(source3.listener.isEmpty)

private class TestListener(expected: Int)(using asst: munit.Assertions) extends Listener[Int]:
  val lock = null

  def complete(data: Int, source: Async.SourceSymbol[Int]): Unit =
    asst.assertEquals(data, expected)

private class NumberedTestListener private (sleep: AtomicBoolean, fail: Boolean, expected: Int)(using munit.Assertions)
    extends TestListener(expected):
  // A promise that is waited for inside `lock` until `continue` is called.
  private val waiter = if sleep.get() then Some(Promise[Unit]()) else None
  // A promise that is resolved right before the lock starts waiting for `waiter`.
  private val sleepPromise = Promise[Unit]()

  /** A [[Future]] that resolves when the listener goes to sleep. */
  val sleeping = sleepPromise.asFuture

  def this(sleep: Boolean, fail: Boolean, expected: Int)(using munit.Assertions) =
    this(AtomicBoolean(sleep), fail, expected)

  override val lock = new ListenerLock with Listener.NumberedLock:
    val selfNumber = this.number
    def acquire() =
      if sleep.getAndSet(false) then
        Async.blocking:
          sleepPromise.complete(Success(()))
          waiter.get.await
      if fail then false
      else true
    def release() = ()

  def continue() = waiter.get.complete(Success(()))

/** Dummy source that never completes */
private object Dummy extends Async.Source[Nothing]:
  def poll(k: Listener[Nothing]^): Boolean = false
  def onComplete(k: Listener[Nothing]^): Unit = ()
  def dropListener(k: Listener[Nothing]^): Unit = ()

private class TSource(using asst: munit.Assertions) extends Async.Source[Int]:
  var listener: Option[Listener[Int]] = None
  def poll(k: Listener[Int]^): Boolean = false
  def onComplete(k: Listener[Int]^): Unit =
    import caps.unsafe.unsafeAssumePure
    assert(listener.isEmpty)
    listener = Some(k.unsafeAssumePure)
  def dropListener(k: Listener[Int]^): Unit =
    if listener.isDefined then
      asst.assert(k == listener.get)
      listener = None
  def lockListener() =
    val r = listener.get.acquireLock()
    if !r then listener = None
    r
  def completeWith(value: Int) =
    val l = listener.get
    listener = None
    l.complete(value, this)
  def completeNowWith(value: Int) =
    val r = listener.get.completeNow(value, this)
    listener = None
    r
