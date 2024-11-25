import gears.async.AsyncOperations.*
import gears.async.Future.{Promise, zip}
import gears.async.Listener
import gears.async.default.given
import gears.async.{Async, Future, Task, TaskSchedule, uninterruptible}

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.collection.mutable.Set
import scala.concurrent.ExecutionContext
import scala.util.Random
import scala.util.{Failure, Success, Try}

class FutureBehavior extends munit.FunSuite {
  given ExecutionContext = ExecutionContext.global

  test("Old test") {
    Async.blocking:
      val x = Future:
        val a = Future {
          22
        }
        val b = Future {
          11
        }
        val c = Future {
          assert(false); 1
        }
        val res = c
          .or(Future {
            val res = a.await + b.await
            res
          })
          .or(c)
          .await
        res
      val y = Future:
        val a = Future {
          22
        }
        val b = Future {
          11
        }
        val res = a.zip(b).await
        res
      val z = Future:
        val a = Future {
          22
        }
        val b = Future {
          true
        }
        val res = a.or(b).await
        res
      val _: Future[Int | Boolean] = z
      assertEquals(x.await, 33)
      assertEquals(y.await, (22, 11))
  }

  test("Constant returns") {
    Async.blocking:
      for (i <- -5 to 5)
        val f1 = Future { i }
        val f2 = Future.now(Success(i))
        assertEquals(f1.await, i)
        assertEquals(f1.await, f2.await)
  }

  test("Future.now returning") {
    Async.blocking:
      val f = Future.now(Success(116))
      assertEquals(f.await, 116)
  }

  test("Constant future with timeout") {
    Async.blocking:
      val f = Future {
        sleep(50)
        55
      }
      assertEquals(f.await, 55)
  }

  test("or") {
    Async.blocking:
      val error = new AssertionError()
      val fail = Future.now(Failure(error))
      val fail1 = Future.now(Failure(error))
      val succeed = Future.now(Success(13))

      assert(Set(10, 20).contains(Future { 10 }.or(Future { 20 }).await))
      assertEquals(fail.or(succeed).await, 13)
      assertEquals(succeed.or(fail).await, 13)
      assertEquals(fail.or(fail1).awaitResult, Failure(error))
  }

  test("orWithCancel of 2 futures") {
    Async.blocking:
      var touched = 0
      Future {
        sleep(200)
        touched += 1
      }.or(Future {
        10
      }).awaitResult
      sleep(300)
      assertEquals(touched, 1)
    Async.blocking:
      var touched = 0
      Future {
        sleep(200)
        touched += 1
      }.orWithCancel(Future { 10 }).awaitResult
      sleep(300)
      assertEquals(touched, 0)
  }

  test("zip") {
    Async.blocking:
      val error = new AssertionError()
      val fail = Future.now(Failure(error))
      val fail1 = Future.now(Failure(error))
      val succeed = Future.now(Success(13))

      assertEquals(Future { 10 }.zip(Future { 20 }).await, (10, 20))
      assertEquals(fail.zip(succeed).awaitResult, Failure(error))
      assertEquals(succeed.zip(fail).awaitResult, Failure(error))
      assertEquals(fail.zip(fail1).awaitResult, Failure(error))
  }

  test("result wraps exceptions") {
    Async.blocking:
      for (i <- -5 to 5)
        val error = new AssertionError(i)
        assertEquals(Future { throw error }.awaitResult, Failure(error))
  }

  test("result wraps values") {
    Async.blocking:
      for (i <- -5 to 5)
        assertEquals(Future { i }.awaitResult, Success(i))
  }

  test("value propagates exceptions exceptions through futures") {
    Async.blocking:
      val e = AssertionError(1151)
      val f = Future {
        val f1 = Future {
          throw e
        }
        (f1.await, f1.await)
      }
      try f.await
      catch
        case e1: AssertionError => assertEquals(e, e1)
        case z =>
          fail(String.valueOf(z))
  }

  test("futures can be nested") {
    Async.blocking:
      val z = Future {
        sleep(Random.between(0, 15L))
        Future {
          sleep(Random.between(0, 15L))
          Future {
            sleep(Random.between(0, 15L))
            10
          }.await
        }.await
      }.await
      assertEquals(z, 10)
  }

  test("future are cancelled via .interrupt() and not checking the flag in async") {
    Async.blocking:
      val f = Future {
        sleep(150L)
        10
      }
      f.cancel()
      f.awaitResult match
        case _: Failure[CancellationException] => ()
        case _                                 => assert(false)
  }

  test("future should cancel its group when the main body is completed") {
    Async.blocking:
      var touched = false
      val fut = Future:
        Future:
          sleep(1000)
          touched = true
        sleep(500)
        10
      assertEquals(fut.await, 10)
      sleep(1000)
      assertEquals(touched, false)
  }

  test("zombie threads exist and run to completion after the Async.blocking barrier") {
    var zombieModifiedThis = false
    Async.blocking:
      val f = Future {
        Future {
          sleep(200)
          zombieModifiedThis = true
        }.unlink()
        10
      }
      assertEquals(f.await, 10)
      assertEquals(zombieModifiedThis, false)
    Thread.sleep(300)
    assertEquals(zombieModifiedThis, true)
  }

  // test("zip on tuples with EmptyTuple") {
  //   Async.blocking:
  //     val z1 = Future { sleep(500); 10 } *: Future { sleep(10); 222 } *: Future { sleep(150); 333 } *: Future {
  //       EmptyTuple
  //     }
  //     assertEquals(z1.await, (10, 222, 333))
  // }

  // test("zip on tuples with last zip") {
  //   Async.blocking:
  //     val z1 = Future { 10 } *: Future { 222 }.zip(Future { 333 })
  //     assertEquals(z1.await, (10, 222, 333))
  // }

  // test("zip(3) first error") {
  //   for (_ <- 1 to 20)
  //     Async.blocking:
  //       val e1 = AssertionError(111)
  //       val e2 = AssertionError(211)
  //       val e3 = AssertionError(311)
  //       assertEquals(
  //         (Future {
  //           sleep(Random.between(200, 300));
  //           throw e1
  //         } *: Future {
  //           sleep(Random.between(200, 300));
  //           throw e2
  //         } *: Future {
  //           sleep(Random.between(50, 100));
  //           throw e3
  //         } *: Future.now(Success(EmptyTuple))).awaitResult,
  //         Failure(e3)
  //       )
  // }

  test("cancelled futures return the same constant CancellationException with no stack attached".ignore) {
    Async.blocking:
      val futures = List(
        Future { sleep(100); 1 },
        Future { sleep(100); 1 },
        Future { sleep(100); 1 },
        Future { sleep(100); 1 },
        Future { sleep(100); 1 }
      )
      futures.foreach(_.cancel())
      val exceptionSet = mutable.Set[Throwable]()
      for (f <- futures) {
        f.awaitResult match {
          case Failure(e) => exceptionSet.add(e)
          case _          => assert(false)
        }
      }
      assertEquals(exceptionSet.size, 1)
  }

  test("noninterruptible future immediate return") {
    Async.blocking:
      for (i <- -5 to 5)
        var j = -1
        uninterruptible {
          j = i
        }
        val f2 = Future.now(Success(i))
        assertEquals(j, f2.await)
        assertEquals(j, i)
  }

  test("Noninterruptible future cannot be interrupted immediately but throws later") {
    Async.blocking:
      var touched = false
      val f = Future {
        uninterruptible {
          sleep(300)
          touched = true
        }
      }
      sleep(50)
      f.cancel()
      f.awaitResult
      assertEquals(touched, true)
      f.awaitResult match
        case Failure(ex) if ex.isInstanceOf[CancellationException] => ()
        case _                                                     => assert(false)
  }

  test("Promise can be cancelled") {
    Async.blocking:
      val p = Promise[Int]()
      val f = p.asFuture
      f.cancel()
      p.complete(Success(10))
      f.awaitResult match
        case Failure(ex) if ex.isInstanceOf[CancellationException] => ()
        case _                                                     => assert(false)
  }

  test("Promise can't be cancelled after completion") {
    Async.blocking:
      val p = Promise[Int]()
      p.complete(Success(10))
      val f = p.asFuture
      f.cancel()
      assertEquals(f.await, 10)
  }

  test("Future.withResolver cancel handler is run once") {
    val num = AtomicInteger(0)
    val fut = Future.withResolver { _.onCancel { () => num.incrementAndGet() } }
    Async.blocking:
      (1 to 20)
        .map(_ => Future { fut.cancel() })
        .awaitAll
    assertEquals(num.get(), 1)
  }

  test("Future.withResolver cancel handler is not run after being completed") {
    val num = AtomicInteger(0)
    val fut = Future.withResolver[Int]: r =>
      r.onCancel { () => num.incrementAndGet() }
      r.resolve(1)
    fut.cancel()
    assertEquals(num.get(), 0)
  }

  test("Future.withResolver is only completed after handler decides") {
    val prom = Future.Promise[Unit]()
    val fut = Future.withResolver[Unit]: r =>
      r.onCancel(() => prom.onComplete(Listener { (_, _) => r.rejectAsCancelled() }))

    assert(fut.poll().isEmpty)
    fut.cancel()
    assert(fut.poll().isEmpty)
    prom.complete(Success(()))
    assert(fut.poll().isDefined)
  }

  test("Nesting of cancellations") {
    Async.blocking:
      var touched1 = false
      var touched2 = false
      val f1 = Future {
        uninterruptible {
          sleep(400)
          touched1 = true
        }
        touched2 = true
      }
      sleep(50)
      f1.cancel()
      f1.awaitResult
      assertEquals(touched1, true)
      assertEquals(touched2, false)
  }

  test("future collector") {
    Async.blocking:
      val range = (0 to 10)
      val futs = range.map(i => Future { sleep(i * 100); i })
      val collector = Future.Collector(futs*)

      var sum = 0
      for i <- range do sum += collector.results.read().right.get.await
      assertEquals(sum, range.sum)
  }

  test("mutable collector") {
    Async.blocking:
      val range = (0 to 10)
      val futs = range.map(i => Future { sleep(i * 100); i })
      val collector = Future.MutableCollector(futs*)

      for i <- range do
        val r = Future { i }
        Future:
          sleep(i * 200)
          collector += r

      var sum = 0
      for i <- range do sum += collector.results.read().right.get.await
      for i <- range do sum += collector.results.read().right.get.await
      assertEquals(sum, 2 * range.sum)
  }

  test("future collection: awaitAll*") {
    Async.blocking:
      val range = (0 to 10)
      def futs = range.map(i => Future { sleep(i * 100); i })
      assertEquals(futs.awaitAll, range.toSeq)

      val exc = new Exception("a")
      def futsWithFail = futs ++ Seq(Future { throw exc })
      assertEquals(Try(futsWithFail.awaitAll), Failure(exc))

      var lastFutureFinished = false
      def futsWithSleepy = futsWithFail ++ Seq(Future { sleep(200000); lastFutureFinished = true; -1 })
      assertEquals(Try(futsWithSleepy.awaitAll), Failure(exc))
      assert(!lastFutureFinished)
  }

  test("future collection: awaitFirst*") {
    Async.blocking:
      val range = (0 to 10)
      def futs = range.map(i => Future { sleep(i * 100); i })
      assert(range contains futs.awaitFirst)

      val exc = new Exception("a")
      def futsWithFail = futs ++ Seq(Future { throw exc })
      assert(range contains futsWithFail.awaitFirst)

      val excs = range.map(i => new Exception(i.toString()))
      def futsAllFail = range.zip(excs).map((i, exc) => Future { sleep(i * 100); throw exc })
      assertEquals(Try(futsAllFail.awaitFirst), Failure(excs.last))

      var lastFutureFinished = false
      def futsWithSleepy = futsWithFail ++ Seq(Future { sleep(200000); lastFutureFinished = true; 0 })
      assert(range contains futsWithSleepy.awaitFirst)
      assert(!lastFutureFinished)
  }

  test("uninterruptible should continue even when Future is cancelled") {
    Async.blocking:
      val ch = gears.async.UnboundedChannel[Int]()
      val reader = Future:
        gears.async.uninterruptible:
          val i = ch.read().right.get
          println(i)
      reader.cancel()
      ch.sendImmediately(1)
      ch.sendImmediately(2)
      reader.awaitResult
      assertEquals(ch.read(), Right(2))
  }

  test("deferred futures") {
    Async.blocking:
      val counter = AtomicInteger(0)
      val a = new Array[Future.DeferredFuture[Int]](4)

      a(0) = Future.deferred:
        counter.incrementAndGet()
        a(1).await + a(2).await
      a(1) = Future.deferred:
        counter.incrementAndGet()
        a(3).await + 4
      a(2) = Future.deferred:
        counter.incrementAndGet()
        a(3).await + 2
      a(3) = Future.deferred:
        counter.incrementAndGet()
        1

      a.foreach(_.start())

      assertEquals(a(0).await, 8)
      assertEquals(counter.get(), 4)
  }
}
