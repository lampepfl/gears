import concurrent.{Async, Future, alt, zip}

import java.util.concurrent.CancellationException
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import scala.util.Random

class FuturesBehavior extends munit.FunSuite {
  given ExecutionContext = ExecutionContext.global

  test("Constant returns") {
    Async.blocking:
      for (i <- -5 to 5)
        val f1 = Future { i }
        val f2 = Future.now(Success(i))
        assertEquals(f1.value, f2.value)
        assertEquals(f1.value, i)
  }

  test("Future.now returning") {
    Async.blocking:
      val f = Future.now(Success(116))
      assertEquals(f.value, 116)
  }

  test("Constant future with timeout") {
    Async.blocking:
      val f = Future {
        Thread.sleep(50)
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
      try f.value
      catch
        case e1: AssertionError => assertEquals(e, e1)
        case _ => assert(false)
  }

  test("futures can be nested") {
    Async.blocking:
      val z = Future{
        Thread.sleep(Random.between(0, 15L))
        Future{
          Thread.sleep(Random.between(0, 15L))
          Future{
            Thread.sleep(Random.between(0, 15L))
            10
          }.value
        }.value
      }.value
      assertEquals(z, 10)
  }

  test("future are not cancellable in Async.blocking (non-virtual behavior)") {
    Async.blocking:
      val f = Future {
        Thread.sleep(150L)
        10
      }
      f.cancel()
      assertEquals(f.result, Success(10))
  }

  test("future are cancelled via .interrupt() and not checking the flag in async (virtual behavior)") {
    Async.blocking:
      val f = Future {
        Thread.sleep(150L)
        10
      }
      f.cancel()
      f.result match
        case _: Failure[CancellationException] => ()
        case _ => assert(false)
  }

  test("zombie threads exist (virtual behavior)") {
    Async.blocking:
      var zombieModifiedThis = false
      val f = Future {
        Future {
          Thread.sleep(200)
          zombieModifiedThis = true
        }
        10
      }
      assertEquals(f.value, 10)
      assertEquals(zombieModifiedThis, false)
      Thread.sleep(210)
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

    test("n-ary alt last failure") {
      Async.blocking:
        for (_ <- 1 to 20)
          val e1 = AssertionError(111)
          val e2 = AssertionError(211)
          val e3 = AssertionError(311)

          assertEquals(
            alt(Future {
              Thread.sleep(Random.between(0, 250));
              throw e1
            },
            Future {
              Thread.sleep(Random.between(500, 1000));
              throw e2
            },
            Future {
              Thread.sleep(Random.between(0, 250));
              throw e3
            }).result, Failure(e2))
    }

  test("n-ary alt first success") {
    Async.blocking:
      for (_ <- 1 to 20)
        assertEquals(
          alt(Future {
            Thread.sleep(Random.between(200, 300));
            111
          },
            Future {
              Thread.sleep(Random.between(200, 300));
              222
            },
            Future {
              Thread.sleep(Random.between(50, 100));
              333
            }).result, Success(333))
  }

  test("zip(3) working") {
    Async.blocking:
      assertEquals(
        zip(Future {
          Thread.sleep(Random.between(200, 300));
          111
        },
        Future {
          Thread.sleep(Random.between(200, 300));
          222
        },
        Future {
          Thread.sleep(Random.between(50, 100));
          333
        }).value, (111, 222, 333))
  }

  test("zip(3) first error") {
    for (_ <- 1 to 20)
      Async.blocking:
        val e1 = AssertionError(111)
        val e2 = AssertionError(211)
        val e3 = AssertionError(311)
        assertEquals(
          zip(Future {
            Thread.sleep(Random.between(200, 300));
            throw e1
          },
            Future {
              Thread.sleep(Random.between(200, 300));
              throw e2
            },
            Future {
              Thread.sleep(Random.between(50, 100));
              throw e3
            }).result, Failure(e3))
  }

}
