import concurrent.{Async, Future}
import async.AsyncFoundations
import scala.util.boundary
import boundary.break
import scala.concurrent.duration.{Duration, DurationInt}
import java.util.concurrent.CancellationException

class CancellationBehavior extends munit.FunSuite:
  override def munitTimeout: Duration = 5.seconds

  enum State:
    case Ready
    case Initialized(f: Future[?])
    case RunningEarly
    case Running(f: Future[?])
    case Cancelled
    case Completed
  private class Info(var state: State = State.Ready):
    def assertCancelled() =
      Thread.sleep(100)
      synchronized:
        assertEquals(state, State.Cancelled)
    def assertRunning(): Future[?] =
      Thread.sleep(100)
      synchronized:
        state match
          case State.Running(f) => f
          case _ => fail(s"future should be running, is $state")
    def assertCompleted() =
      Thread.sleep(100)
      synchronized:
        assertEquals(state, State.Completed)
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
        case _: InterruptedException | _: CancellationException =>
          info.state = State.Cancelled
    info.initialize(f)

  test("no cancel"):
    var x = 0
    Async.blocking:
      Future:
        x = 1
      Thread.sleep(400)
    assertEquals(x, 1)

  test("link no group"):
    val info = Info()
    Async.blocking:
      startFuture(info)
      info.assertRunning()

    val fut = info.assertRunning()
    Async.blocking:
        fut.cancel()
    info.assertCancelled()

  test("link group"):
    val info = Info()
    Async.blocking:
      Async.group:
        startFuture(info)
        info.assertRunning()
      info.assertCancelled()

  test("nested link group"):
    val (info1, info2) = (Info(), Info())
    Async.blocking:
      Async.group:
        startFuture(info1, {
            Async.group:
                startFuture(info2)
                info2.assertRunning()
            info1.assertRunning()
            info2.assertCancelled()
        })
        info1.assertRunning()
      info1.assertCancelled()
      info2.assertCancelled()
