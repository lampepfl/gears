import gears.async.*
import gears.async.AsyncOperations.*
import gears.async.default.given

import java.util.concurrent.CancellationException
import scala.concurrent.duration.{Duration, DurationInt}
import scala.util.Success
import scala.util.boundary

import boundary.break

class CancellationBehavior extends munit.FunSuite:
  enum State:
    case Ready
    case Initialized(f: Future[?])
    case RunningEarly
    case Running(f: Future[?])
    case Failed(t: Throwable)
    case Cancelled
    case Completed
  private class Info(var state: State = State.Ready):
    def assertCancelled() =
      synchronized:
        assertEquals(state, State.Cancelled)
    def ended = state match
      case State.Failed(_) | State.Cancelled | State.Completed => true
      case _                                                   => false
    def run() =
      synchronized:
        state match
          case State.Ready =>
            state = State.RunningEarly
          case State.Initialized(f) =>
            state = State.Running(f)
          case _ => fail(s"running failed, state is $state")
    def initialize(f: Future[?]) =
      synchronized:
        state match
          case State.Ready =>
            state = State.Initialized(f)
          case State.RunningEarly =>
            state = State.Running(f)
          case _ => fail(s"initializing failed, state is $state")

  private def startFuture(info: Info, body: Async ?=> Unit = {})(using a: Async, s: Async.Spawn & a.type) =
    val f = Future:
      info.run()
      try
        body
        sleep(10 * 1000)
        info.state = State.Completed
      catch
        case e: (InterruptedException | CancellationException) =>
          info.state = State.Cancelled
          throw e
        case e =>
          info.state = State.Failed(e)
          throw e
    info.synchronized:
      if !info.ended then info.initialize(f)
    f

  test("no cancel"):
    var x = 0
    Async.blocking:
      Future:
        x = 1
      AsyncOperations.sleep(400)
      assertEquals(x, 1)

  test("group cancel"):
    var x = 0
    Async.blocking:
      Async.group:
        Future:
          sleep(400)
          x = 1
    assertEquals(x, 0)

  test("link group"):
    val info = Info()
    Async.blocking:
      val promise = Future.Promise[Unit]()
      Async.group:
        startFuture(info, promise.complete(Success(())))
        promise.await
      info.assertCancelled()

  test("nested link group"):
    val (info1, info2) = (Info(), Info())
    val (promise1, promise2) = (Future.Promise[Unit](), Future.Promise[Unit]())
    Async.blocking:
      Async.group:
        startFuture(
          info1, {
            Async.group:
              startFuture(info2, promise2.complete(Success(())))
              promise2.await
            info2.assertCancelled()
            Future.now(Success(())).await // check cancellation
            promise1.complete(Success(()))
          }
        )
        promise1.await
      info1.assertCancelled()
      info2.assertCancelled()

  test("link to already cancelled"):
    var x1 = 0
    var x2 = 0
    Async.blocking:
      val f = Future:
        uninterruptible:
          sleep(500)
          x1 = 1
        x2 = 1
      Async.group:
        Async.current.group.cancel() // cancel now
        f.link()
        assertEquals(x1, 0)
      assert(f.poll().isDefined) // future finished
      assertEquals(x1, 1)
      assertEquals(x2, 0)

  test("link to already cancelled awaited"):
    val info = Info()
    Async.blocking:
      val promise = Future.Promise[Unit]()
      Async.group:
        Async.current.group.cancel() // cancel now
        val f = startFuture(info, promise.complete(Success(())))
        promise.awaitResult
        f.awaitResult
        info.assertCancelled()
