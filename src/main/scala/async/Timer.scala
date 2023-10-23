package gears.async

import Future.Promise
import AsyncOperations.sleep

import java.util.concurrent.TimeoutException
import scala.collection.mutable
import scala.concurrent.TimeoutException
import scala.util.{Failure, Success, Try}

type TimerRang = Boolean

/** A timer that has to be explicitly started via `start()` to begin counting time.
 *  Can be used only once per instance.
 */
class StartableTimer(val millis: Long) extends Async.OriginalSource[TimerRang], Cancellable {
  private enum TimerState(val future: Option[Future[Unit]]):
    case Ready extends TimerState(None)
    case Ticking(val f: Future[Unit]) extends TimerState(Some(f))
    case RangAlready extends TimerState(None)
    case Cancelled extends TimerState(None)

  private val waiting: mutable.Set[Listener[TimerRang]] = mutable.Set()
  @volatile private var state = TimerState.Ready


    def start(): Unit =
      state match
        case TimerState.Cancelled => throw new IllegalStateException("Timers cannot be started after being cancelled.")
        case TimerState.RangAlready => throw new IllegalStateException("Timers cannot be started after they rang already.")
        case TimerState.Ticking(_) => throw new IllegalStateException("Timers cannot be started once they have already been started.")
        case TimerState.Ready =>
          Async.blocking:
            val f = Future:
              sleep(millis)
              var toNotify = List[Listener[TimerRang]]()
              synchronized:
                toNotify = waiting.toList
                waiting.clear()
                state match
                  case TimerState.Ticking(_) =>
                    state = TimerState.RangAlready
                  case _ =>
                    toNotify = List()
              for listener <- toNotify do listener.completeNow(true)
            state = TimerState.Ticking(f)

    def cancel(): Unit =
      state match
        case TimerState.Cancelled | TimerState.Ready | TimerState.RangAlready => ()
        case TimerState.Ticking(f: Future[Unit]) =>
          f.cancel()
          val toNotify = synchronized:
            val ws = waiting.toList
            waiting.clear()
            ws
          for listener <- toNotify do listener.completeNow(false)
      state = TimerState.Cancelled

    def poll(k: Listener[TimerRang]): Boolean =
      state match
        case TimerState.Ready | TimerState.Ticking(_) => false
        case TimerState.RangAlready => k.completeNow(true)
        case TimerState.Cancelled => k.completeNow(false)

    def addListener(k: Listener[TimerRang]): Unit = synchronized:
      waiting += k

    def dropListener(k: Listener[TimerRang]): Unit = synchronized:
      waiting -= k
  }

/** Exactly like `StartableTimer` except it starts immediately upon instance creation.
 */
class Timer(millis: Long) extends StartableTimer(millis) {
  this.start()
}


@main def TimerSleep1Second(): Unit =
  Async.blocking:
        println("start of 1 second")
        assert(Async.await(Timer(1000)))
        println("end of 1 second")


def timeoutCancellableFuture[T](millis: Long, f: Future[T]): Future[T] =
  val p = Promise[T]()
  val t = Timer(millis)
  Async.blocking:
    val g = Async.await(Async.either(t, f))
    g match
      case Left(_) =>
        f.cancel()
        p.complete(Failure(TimeoutException()))
      case Right(v) =>
        t.cancel()
        p.complete(v)
  p.future


@main def testTimeoutFuture(): Unit =
  var touched = false
  Async.blocking:
    val t = timeoutCancellableFuture(250, Future:
        Async.await(Timer(1000))
        touched = true)
    Async.await(t)
    assert(!touched)
    sleep(2000)
    assert(!touched)

