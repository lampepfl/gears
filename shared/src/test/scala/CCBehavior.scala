import language.experimental.captureChecking

import gears.async.AsyncOperations.*
import gears.async.default.given
import gears.async.{Async, AsyncSupport, Future, uninterruptible}

import java.util.concurrent.CancellationException
import scala.annotation.capability
import scala.concurrent.duration.{Duration, DurationInt}
import scala.util.Success
import scala.util.boundary

type Result[+T, +E] = Either[E, T]
object Result:
  opaque type Label[-T, -E] = boundary.Label[Result[T, E]]
  // ^ doesn't work?

  def apply[T, E](body: Label[T, E]^ ?=> T): Result[T, E] =
    boundary(Right(body))

  extension [U, E](r: Result[U, E])(using Label[Nothing, E]^)
    def ok: U = r match
      case Left(value)  => boundary.break(Left(value))
      case Right(value) => value

class CaptureCheckingBehavior extends munit.FunSuite:
  import Result.*
  import caps.unbox

  test("good") {
    // don't do this in real code! capturing Async.blocking's Async context across functions is hard to track
    Async.blocking: async ?=>
      def good1[T, E](@unbox frs: List[Future[Result[T, E]]^]): Future[Result[List[T], E]]^{frs*, async} =
        Future: fut ?=>
          Result: ret ?=>
            frs.map(_.await.ok)

      def good2[T, E](@unbox rf: Result[Future[T]^, E]): Future[Result[T, E]]^{rf*, async} =
        Future:
          Result:
            rf.ok.await // OK, Future argument has type Result[T]

      def useless4[T, E](fr: Future[Result[T, E]]^) =
        fr.await.map(Future(_))
  }

  test("very bad") {
    Async.blocking: async ?=>
      def fail3[T, E](fr: Future[Result[T, E]]^) =
        Result: label ?=>
          Future: fut ?=>
            fr.await.ok // error, escaping label from Result

      val fut = Future(Left(5))
      val res = fail3(fut)
      println(res.right.get.asInstanceOf[Future[Any]].awaitResult)
  }

  // test("bad") {
  //   Async.blocking: async ?=>
  //     def fail3[T, E](fr: Future[Result[T, E]]^): Result[Future[T]^{async}, E] =
  //       Result: label ?=>
  //         Future: fut ?=>
  //           fr.await.ok // error, escaping label from Result
  // }
