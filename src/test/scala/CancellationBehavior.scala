import gears.async.{Async, Future, AsyncFoundations}
import scala.util.boundary
import boundary.break
import scala.concurrent.duration.{Duration, DurationInt}
import java.util.concurrent.CancellationException
import scala.util.Success

class CancellationBehavior extends munit.FunSuite:
  override def munitTimeout: Duration = 5.seconds

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

  private def startFuture(info: Info, body: Async ?=> Unit = {})(using Async) =
    val f = Future:
      info.run()
      try
        body
        Async.current.sleep(10 * 1000)
        info.state = State.Completed
      catch
        case e: (InterruptedException | CancellationException) =>
          info.state = State.Cancelled
          throw e
        case e =>
          info.state = State.Failed(e)
          throw e
    info.initialize(f)

  test("no cancel"):
    var x = 0
    Async.blocking:
      Future:
        x = 1
      Thread.sleep(400)
    assertEquals(x, 1)

  test("link group"):
    val info = Info()
    Async.blocking:
      val promise = Future.Promise[Unit]()
      Async.group:
        startFuture(info, promise.complete(Success(())))
        Async.await(promise.future)
      info.assertCancelled()

  test("nested link group"):
    val (info1, info2) = (Info(), Info())
    val (promise1, promise2) = (Future.Promise[Unit](), Future.Promise[Unit]())
    Async.blocking:
      Async.group:
        startFuture(info1, {
            Async.group:
                startFuture(info2, promise2.complete(Success(())))
                Async.await(promise2.future)
            info2.assertCancelled()
            promise1.complete(Success(()))
        })
        Async.await(promise1.future)
      info1.assertCancelled()
      info2.assertCancelled()
