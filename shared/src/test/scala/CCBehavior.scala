import language.experimental.captureChecking

import gears.async.AsyncOperations.*
import gears.async.default.given
import gears.async.{Async, AsyncSupport, Future, uninterruptible}

import java.util.concurrent.CancellationException
import scala.annotation.capability
import scala.concurrent.duration.{Duration, DurationInt}
import scala.util.Success
import gears.async.Channel
import gears.async.SyncChannel

type Result[+T, +E] = Either[E, T]
object Result:
  import scala.util.boundary
  type Label[-T, -E] = boundary.Label[Result[T, E]]
  // ^ opaque doesn't work?

  inline def apply[T, E](body: Label[T, E] ?=> T): Result[T, E] =
    boundary(lbl ?=> Right(body(using lbl)))

  extension [U, E](r: Result[U, E])(using Label[Nothing, E])
    inline def ok: U = r match
      case Left(value)  => boundary.break(Left(value))
      case Right(value) => value

class CaptureCheckingBehavior extends munit.FunSuite:
  import Result.*
  import caps.use
  import scala.collection.mutable

  test("good") {
    // don't do this in real code! capturing Async.blocking's Async context across functions is hard to track
    Async.fromSync: async ?=>
      def good1[T, E, C^](frs: List[Future[Result[T, E]]^{C}]): Future[Result[List[T], E]]^{C, async} =
        Future: fut ?=>
          Result[List[T], E]: ret ?=>
            frs.map(_.await.ok)

      def good2[T, E, C^](rf: Result[Future[T]^{C}, E]): Future[Result[T, E]]^{C, async} =
        Future:
          Result[T, E]:
            rf.ok.await // OK, Future argument has type Result[T]

      def useless4[T, E](fr: Future[Result[T, E]]^) =
        fr.await.map(Future(_))
  }

  // test("bad - collectors") {
  //   val futs: Seq[Future[Int]^] = Async.blocking: async ?=>
  //     val fs: Seq[Future[Int]^{async}] = (0 to 10).map(i => Future { i })
  //     fs
  //   Async.blocking:
  //     futs.awaitAll // should not compile
  // }

  test("future withResolver capturing") {
    class File():
      def close() = ()
      def read(callback: Int => Unit) = ()
    val f: File^ = File()
    val read  = Future.withResolver[Int, caps.CapSet^{f}]: r =>
      f.read(r.resolve)
      r.onCancel(f.close)
  }

  test("awaitAll/awaitFirst") {
    trait File:
      def readFut(): Future[Int]^{this}
    object File:
      def open[T](filename: String)(body: File^ => T)(using Async): T = body:
        new File:
          def readFut(): Future[Int]^{this} = Future.resolved(0)

    def readAll[C^](files: (File^{C})*): Seq[Future[Int]^{C}] = files.map(f => f.readFut())

    Async.fromSync:
      File.open("a.txt"): a =>
          val aa = a // TODO workaround
          File.open("b.txt"): b =>
            val bb = b
            val futs: Seq[Future[Int]^{aa, bb}] = readAll(aa, bb)
            val allFuts = Future(futs.awaitAll)
            allFuts
              .await // uncomment to leak
  }

  // test("channel") {
  //   trait File extends caps.Capability:
  //     def read(): Int = ???
  //   Async.blocking:
  //     val ch = SyncChannel[File]()
  //     // Sender
  //     val sender = Future:
  //       val f = new File {}
  //       ch.send(f)
  //     val recv = Future:
  //       val f = ch.read().right.get
  //       f.read()
  // }

  test("very bad") {
    Async.fromSync: async ?=>
      def fail3[T, E](fr: Future[Result[T, E]]^): Result[Any, Any] =
        Result: label ?=>
          Future: fut ?=>
            fr.await.ok // error, escaping label from Result

      // val fut = Future(Left(5))
      // val res = fail3(fut)
      // println(res.right.get.asInstanceOf[Future[Any]].awaitResult)
  }

  // test("bad") {
  //   Async.blocking: async ?=>
  //     def fail3[T, E](fr: Future[Result[T, E]]^): Result[Future[T]^{async}, E] =
  //       Result: label ?=>
  //         Future: fut ?=>
  //           fr.await.ok // error, escaping label from Result
  // }
