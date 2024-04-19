import language.experimental.captureChecking

import gears.async.AsyncOperations.*
import gears.async.default.given
import gears.async.{Async, Future, Listener, withTimeout}

import java.util.concurrent.CancellationException
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.Random
import scala.util.{Failure, Success, Try}

import Async.either

class SourceBehavior extends munit.FunSuite {
  given ExecutionContext = ExecutionContext.global

  test("onComplete register after completion runs immediately") {
    @volatile var itRan = false
    Async.blocking:
      val f = Future.now(Success(10))
      f.onComplete(Listener.acceptingListener { (_, _) => itRan = true })
    assertEquals(itRan, true)
  }

  test("poll is asynchronous") {
    @volatile var itRan = false
    Async.blocking:
      val f = Future { sleep(50); 10 }
      f.poll(Listener.acceptingListener { (_, _) => itRan = true })
      assertEquals(itRan, false)
  }

  test("onComplete is asynchronous") {
    @volatile var itRan = false
    Async.blocking:
      val f = Future {
        sleep(50); 10
      }
      f.onComplete(Listener.acceptingListener { (_, _) => itRan = true })
      assertEquals(itRan, false)
  }

  test("await is synchronous") {
    @volatile var itRan = false
    Async.blocking:
      val f = Future {
        sleep(250);
        10
      }
      f.onComplete(Listener.acceptingListener { (_, _) => itRan = true })
      f.await
      Thread.sleep(100) // onComplete of await and manual may be scheduled
      assertEquals(itRan, true)
  }

  test("sources wait on children sources when they block") {
    Async.blocking:
      val timeBefore = System.currentTimeMillis()
      val f = Future {
        sleep(50)
        Future {
          sleep(70)
          Future {
            sleep(20)
            10
          }.await
        }.await
      }.await
      val timeAfter = System.currentTimeMillis()
      assert(timeAfter - timeBefore >= 50 + 70 + 20)
  }

  test("sources do not wait on zombie sources (which are killed at the end of Async.Blocking)") {
    val timeBefore = System.currentTimeMillis()
    Async.blocking:
      val f = Future {
        Future { sleep(300) }
        1
      }.await
    val timeAfter = System.currentTimeMillis()
    assert(timeAfter - timeBefore < 290)
  }

  test("poll()") {
    Async.blocking:
      val f = Future {
        sleep(100)
        1
      }
      assertEquals(f.poll(), None)
      f.await
      assertEquals(f.poll(), Some(Success(1)))
  }

  test("onComplete() fires") {
    Async.blocking:
      @volatile var aRan = false
      @volatile var bRan = false
      val f = Future {
        sleep(100)
        1
      }
      f.onComplete(Listener.acceptingListener { (_, _) => aRan = true })
      f.onComplete(Listener.acceptingListener { (_, _) => bRan = true })
      assertEquals(aRan, false)
      assertEquals(bRan, false)
      f.await
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
      val l: Listener[Try[Int]] = Listener.acceptingListener { (_, _) => aRan = true }
      f.onComplete(l)
      f.onComplete(Listener.acceptingListener { (_, _) => bRan = true })
      assertEquals(aRan, false)
      assertEquals(bRan, false)
      f.dropListener(l)
      f.await
      Thread.sleep(100) // onComplete of await and manual may be scheduled
      assertEquals(aRan, false)
      assertEquals(bRan, true)
  }

  test("transform values with") {
    Async.blocking:
      val f = Future { 10 }
      assertEquals(f.transformValuesWith({ case Success(i) => i + 1 }).awaitResult, 11)
      val g = Future.now(Failure(AssertionError(1123)))
      assertEquals(g.transformValuesWith({ case Failure(_) => 17 }).awaitResult, 17)
  }

  test("all listeners in chain fire") {
    Async.blocking:
      var aRan = Future.Promise[Unit]()
      var bRan = Future.Promise[Unit]()
      val wait = Future.Promise[Unit]()
      val f = Future {
        wait.await
        10
      }
      val g = f.transformValuesWith(identity)
      f.onComplete(Listener.acceptingListener { (_, _) => aRan.complete(Success(())) })
      g.onComplete(Listener.acceptingListener { (_, _) => bRan.complete(Success(())) })
      assertEquals(aRan.poll(), None)
      assertEquals(bRan.poll(), None)
      wait.complete(Success(()))
      f.await
      withTimeout(200.millis):
        aRan.zip(bRan).await
  }

  test("either") {
    @volatile var touched = false
    Async.blocking:
      val f1 = Future { sleep(300); touched = true; 10 }
      val f2 = Future { sleep(50); 40 }
      val g = either(f1, f2).awaitResult
      assertEquals(g, Right(Success(40)))
      sleep(350)
      assertEquals(touched, true)
  }

  test("source values") {
    Async.blocking:
      val src = Async.Source.values(1, 2)
      assertEquals(src.awaitResult, 1)
      assertEquals(src.awaitResult, 2)

    Async.blocking:
      val src = Async.Source.values(1)
      assertEquals(src.awaitResult, 1)
      assertEquals(
        Async
          .race(
            src, // this should block forever, so never resolve!
            Future { sleep(200); 0 }
          )
          .awaitResult,
        Success(0)
      )
  }

  test("transformValuesWith unsubscribes") {
    val base = Future.Promise[Unit]()
    val derived = base.transformValuesWith(_ => ())

    var touched = false
    val listener = Listener { (_data, _src) => touched = true }

    derived.onComplete(listener)
    assert(!touched)

    derived.dropListener(listener)
    base.complete(Success(()))
    assert(!touched)

    derived.onComplete(listener)
    assert(touched)
  }
}
