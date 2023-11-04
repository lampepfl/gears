import gears.async.{Async, Future, Listener, given}
import Async.either
import gears.async.AsyncOperations.*

import java.util.concurrent.CancellationException
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import scala.util.Random

class SourceBehavior extends munit.FunSuite {
  given ExecutionContext = ExecutionContext.global

  test("onComplete register after completion runs immediately") {
    @volatile var itRan = false
    Async.blocking:
      val f = Future.now(Success(10))
      f.onComplete(Listener.acceptingListener { _ => itRan = true })
    assertEquals(itRan, true)
  }

  test("poll is asynchronous") {
    @volatile var itRan = false
    Async.blocking:
      val f = Future{sleep(50); 10}
      f.poll(Listener.acceptingListener {_ => itRan = true})
      assertEquals(itRan, false)
  }

  test("onComplete is asynchronous") {
    @volatile var itRan = false
    Async.blocking:
      val f = Future {
        sleep(50); 10
      }
      f.onComplete(Listener.acceptingListener { _ => itRan = true })
      assertEquals(itRan, false)
  }

  test("await is synchronous") {
    @volatile var itRan = false
    Async.blocking:
      val f = Future {
        sleep(250);
        10
      }
      f.onComplete(Listener.acceptingListener { _ => itRan = true })
      Async.await(f)
      Thread.sleep(100) // onComplete of await and manual may be scheduled
      assertEquals(itRan, true)
  }

  test("sources wait on children sources when they block") {
    Async.blocking:
      val timeBefore = System.currentTimeMillis()
      val f = Future {
        sleep(50);
        Future {
          sleep(70)
          Future {
            sleep(20)
            10
          }.value
        }.value
      }.value
      val timeAfter = System.currentTimeMillis()
      assert(timeAfter - timeBefore >= 50 + 70 + 20)
  }

  test("sources do not wait on zombie sources (which are killed at the end of Async.Blocking)") {
    val timeBefore = System.currentTimeMillis()
    Async.blocking:
      val f = Future {
        Future { sleep(300) }
        1
      }.value
    val timeAfter = System.currentTimeMillis()
    assert(timeAfter - timeBefore < 290)
  }

  test("poll()") {
    Async.blocking:
      val f: Future[Int] = Future {
        sleep(100)
        1
      }
      assertEquals(f.poll(), None)
      Async.await(f)
      assertEquals(f.poll(), Some(Success(1)))
  }

  test("onComplete() fires") {
    Async.blocking:
      @volatile var aRan = false
      @volatile var bRan = false
      val f = Future{
        sleep(100)
        1
      }
      f.onComplete(Listener.acceptingListener {_ => aRan = true})
      f.onComplete(Listener.acceptingListener {_ => bRan = true})
      assertEquals(aRan, false)
      assertEquals(bRan, false)
      Async.await(f)
      Thread.sleep(100) // onComplete of await and manual may be scheduled
      assertEquals(aRan, true)
      assertEquals(bRan, true)
  }

  test("dropped onComplete() listener does not fire") {
    Async.blocking:
      @volatile var aRan = false
      @volatile var bRan = false
      val f = Future {
        sleep(100)
        1
      }
      val l: Listener[Try[Int]] = Listener.acceptingListener { _ => aRan = true }
      f.onComplete(l)
      f.onComplete(Listener.acceptingListener { _ => bRan = true })
      assertEquals(aRan, false)
      assertEquals(bRan, false)
      f.dropListener(l)
      Async.await(f)
      Thread.sleep(100) // onComplete of await and manual may be scheduled
      assertEquals(aRan, false)
      assertEquals(bRan, true)
  }

  test("map") {
    Async.blocking:
      val f: Future[Int] = Future{ 10 }
      assertEquals(Async.await(f.map({ case Success(i) => i + 1 })), 11)
      val g: Future[Int] = Future.now(Failure(AssertionError(1123)))
      assertEquals(Async.await(g.map({ case Failure(_) => 17 })), 17)
  }

  test("all listeners in chain fire") {
    Async.blocking:
      @volatile var aRan = Future.Promise[Unit]()
      @volatile var bRan = Future.Promise[Unit]()
      val f: Future[Int] = Future {
        sleep(50)
        10
      }
      val g = f.map(identity)
      f.onComplete(Listener.acceptingListener { _ => aRan.complete(Success(()))})
      g.onComplete(Listener.acceptingListener { _ => bRan.complete(Success(()))})
      assertEquals(aRan.future.poll(), None)
      assertEquals(bRan.future.poll(), None)
      Async.await(f)
      Thread.sleep(100) // onComplete of await and manual may be scheduled
      aRan.future.zip(bRan.future).alt(Future(sleep(600))).value
  }

  test("either") {
    @volatile var touched = false
    Async.blocking:
      val f1 = Future{ sleep(300); touched = true; 10 }
      val f2 = Future{ sleep(50); 40 }
      val g = Async.await(either(f1, f2))
      assertEquals(g, Right(Success(40)))
      sleep(350)
      assertEquals(touched, true)
  }
}