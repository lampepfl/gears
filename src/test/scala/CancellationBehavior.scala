import scala.concurrent.ExecutionContext
import concurrent.{Async, Future}

class CancellationBehavior extends munit.FunSuite:
  given ExecutionContext = ExecutionContext.global

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
      assertEquals(state, State.Cancelled)
    def assertRunning(): Future[?] =
      Thread.sleep(100)
      state match
        case State.Running(f) => f
        case _ => fail("future not running")
    def assertCompleted() =
      Thread.sleep(100)
      assertEquals(state, State.Completed)
    def run() =
      state match
        case State.Ready =>
            state = State.RunningEarly
        case State.Initialized(f) =>
            state = State.Running(f)
        case _ => fail("running failed")
    def initialize(f: Future[?]) =
      state match
        case State.Ready =>
            state = State.Initialized(f)
        case State.RunningEarly =>
            state = State.Running(f)
        case _ => fail("initializing failed")

  private def startFuture(info: Info, body: Async ?=> Unit = {})(using Async) =
    val f = Future:
      info.run()
      try
        body
        Thread.sleep(60 * 1000)
        info.state = State.Completed
      catch
        case _: InterruptedException => info.state = State.Cancelled
    info.initialize(f)

  test("link no group"):
    val info = Info()
    Async.blocking:
      startFuture(info)
      info.assertRunning()

    val fut = info.assertRunning()
    Async.blocking:
        fut.cancel()

  /* add sleep in futures to ensure timing problem
    private val executor_thread: Thread = Thread.startVirtualThread: () =>
      async:
        Thread.sleep(500)
  */
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
