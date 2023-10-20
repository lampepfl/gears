import gears.async.{Async, Future, Task, TaskSchedule, alt, altC, uninterruptible, given}
import gears.async.default.given
import gears.async.Future.{*:, Promise, zip}
import gears.async.AsyncOperations.*

import java.util.concurrent.CancellationException
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import scala.util.Random
import scala.collection.mutable.Set

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
        val res = c.alt(Future {
          val res = a.value + b.value
          res
        }).alt(c).value
        res
      val y = Future:
        val a = Future {
          22
        }
        val b = Future {
          11
        }
        val res = a.zip(b).value
        res
      val z = Future:
        val a = Future {
          22
        }
        val b = Future {
          true
        }
        val res = a.alt(b).value
        res
      val _: Future[Int | Boolean] = z
      assertEquals(x.value, 33)
      assertEquals(y.value, (22, 11))
  }

  test("Constant returns") {
    Async.blocking:
      for (i <- -5 to 5)
        val f1 = Future { i }
        val f2 = Future.now(Success(i))
        assertEquals(f1.value, i)
        assertEquals(f1.value, f2.value)
  }

  test("Future.now returning") {
    Async.blocking:
      val f = Future.now(Success(116))
      assertEquals(f.value, 116)
  }

  test("Constant future with timeout") {
    Async.blocking:
      val f = Future {
        sleep(50)
        55
      }
      assertEquals(f.value, 55)
  }

  test("alt") {
    Async.blocking:
      val error = new AssertionError()
      val fail = Future.now(Failure(error))
      val fail1 = Future.now(Failure(error))
      val succeed = Future.now(Success(13))

      assert(Set(10, 20).contains(Future {10}.alt(Future {20}).value))
      assertEquals(fail.alt(succeed).value, 13)
      assertEquals(succeed.alt(fail).value, 13)
      assertEquals(fail.alt(fail1).result, Failure(error))
  }

  test("altC of 2 futures") {
    Async.blocking:
      var touched = 0
      Future {
        sleep(200)
        touched += 1
      }.alt(Future {
        10
      }).result
      sleep(300)
      assertEquals(touched, 1)
    Async.blocking:
      var touched = 0
      Future {
        sleep(200)
        touched += 1
      }.altC(Future { 10 }).result
      sleep(300)
      assertEquals(touched, 0)
  }

  test("altC of multiple futures") {
    Async.blocking {
      var touched = java.util.concurrent.atomic.AtomicInteger(0)
      alt(
        Future {
          sleep(100)
          touched.incrementAndGet()
        },
        Future {
          sleep(100)
          touched.incrementAndGet()
        },
        Future {
          5
        }
      )
      .result
      sleep(200)
      assertEquals(touched.get(), 2)
    }
    Async.blocking:
      var touched = 0
      altC(Future { sleep(100); touched += 1 }, Future { sleep(100); touched += 1 }, Future { 5 }).result
      sleep(200)
      assertEquals(touched, 0)
  }

  test("zip") {
    Async.blocking:
      val error = new AssertionError()
      val fail = Future.now(Failure(error))
      val fail1 = Future.now(Failure(error))
      val succeed = Future.now(Success(13))

      assertEquals(Future {10}.zip(Future {20}).value, (10, 20))
      assertEquals(fail.zip(succeed).result, Failure(error))
      assertEquals(succeed.zip(fail).result, Failure(error))
      assertEquals(fail.zip(fail1).result, Failure(error))
  }

  test("result wraps exceptions") {
    Async.blocking:
      for (i <- -5 to 5)
        val error = new AssertionError(i)
        assertEquals(Future { throw error }.result, Failure(error))
  }

  test("result wraps values") {
    Async.blocking:
      for (i <- -5 to 5)
        assertEquals(Future {i}.result, Success(i))
  }

  test("value propagates exceptions exceptions through futures") {
    Async.blocking:
      val e = AssertionError(1151)
      val f = Future {
        val f1 = Future {
          throw e
        }
        (f1.value, f1.value)
      }
      try
        f.value
      catch
        case e1: AssertionError => assertEquals(e, e1)
        case z =>
          fail(String.valueOf(z))
  }

  test("futures can be nested") {
    Async.blocking:
      val z = Future{
        sleep(Random.between(0, 15L))
        Future{
          sleep(Random.between(0, 15L))
          Future{
            sleep(Random.between(0, 15L))
            10
          }.value
        }.value
      }.value
      assertEquals(z, 10)
  }

  test("future are cancelled via .interrupt() and not checking the flag in async") {
    Async.blocking:
      val f = Future {
        sleep(150L)
        10
      }
      f.cancel()
      f.result match
        case _: Failure[CancellationException] => ()
        case _ => assert(false)
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
      assertEquals(f.value, 10)
      assertEquals(zombieModifiedThis, false)
    Thread.sleep(300)
    assertEquals(zombieModifiedThis, true)
  }

  test("n-ary alt") {
    Async.blocking:
      assert(Set(10, 20, 30).contains(alt(Future {
        10
      }, Future {
        20
      }, Future {
        30
      }).value))
  }

  test("zip on tuples with EmptyTuple") {
    Async.blocking:
      val z1 = Future{ sleep(500); 10} *: Future {sleep(10); 222} *: Future{sleep(150); 333} *: Future{EmptyTuple}
      assertEquals(
        z1.value, (10, 222, 333))
  }

  test("zip on tuples with last zip") {
    Async.blocking:
      val z1 = Future {10} *: Future {222}.zip(Future {333})
      assertEquals(
        z1.value, (10, 222, 333))
  }

  test("zip(3) first error") {
    for (_ <- 1 to 20)
      Async.blocking:
        val e1 = AssertionError(111)
        val e2 = AssertionError(211)
        val e3 = AssertionError(311)
        assertEquals(
          (Future {
            sleep(Random.between(200, 300));
            throw e1
          } *:  Future {
              sleep(Random.between(200, 300));
              throw e2
            } *: Future {
              sleep(Random.between(50, 100));
              throw e3
            } *: Future{EmptyTuple}).result, Failure(e3))
  }

  test("cancelled futures return the same constant CancellationException with no stack attached".ignore) {
    Async.blocking:
      val futures = List(
        Future{sleep(100); 1},
        Future{sleep(100); 1},
        Future{sleep(100); 1},
        Future{sleep(100); 1},
        Future{sleep(100); 1}
      )
      futures.foreach(_.cancel())
      val exceptionSet = mutable.Set[Throwable]()
      for (f <- futures) {
        f.result match {
          case Failure(e) => exceptionSet.add(e)
          case _ => assert(false)
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
        assertEquals(j, f2.value)
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
      f.result
      assertEquals(touched, true)
      f.result match
        case Failure(ex) if ex.isInstanceOf[CancellationException] => ()
        case _ => assert(false)
  }

  test("Promise can be cancelled") {
    Async.blocking:
      val p = Promise[Int]()
      p.complete(Success(10))
      val f = p.future
      f.cancel()
      f.result match
        case Failure(ex) if ex.isInstanceOf[CancellationException] => ()
        case _ => assert(false)
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
      f1.result
      assertEquals(touched1, true)
      assertEquals(touched2, false)
  }

  test("n-ary alt first success") {
    Async.blocking:
      for (i <- 1 to 20)
        assertEquals(
          alt(Future {
            sleep(Random.between(200, 300)); 10000 * i + 111
          },
            Future {
              sleep(Random.between(200, 300)); 10000 * i + 222
            },
            Future {
              sleep(Random.between(30, 50)); 10000 * i + 333
            }
          ).result, Success(10000 * i + 333))
  }

  test("n-ary alt last failure") {
    Async.blocking:
      for (_ <- 1 to 20)
        val e1 = AssertionError(111)
        val e2 = AssertionError(211)
        val e3 = AssertionError(311)

        assertEquals(
          alt(Future {
            sleep(Random.between(0, 250));
            throw e1
          },
            Future {
              sleep(Random.between(500, 1000));
              throw e2
            },
            Future {
              sleep(Random.between(0, 250));
              throw e3
            }).result, Failure(e2))
  }

}
