package gears.async

import Future.Promise
import AsyncOperations.sleep

import java.util.concurrent.TimeoutException
import scala.collection.mutable
import scala.concurrent.TimeoutException
import scala.util.{Failure, Success, Try}
import gears.async.Listener
import scala.concurrent.duration._
import scala.annotation.tailrec
import java.util.concurrent.CancellationException

/** 
 * Timer exposes a steady Async.Source of ticks that happens every `tickDuration` milliseconds.
 * Note that the timer does not start ticking until `start` is called (which is a blocking operation, until the timer is cancelled).
 * 
 * You might want to manually `cancel` the timer, so that it gets garbage collected (before the enclosing `Async` scope ends).
*/
class Timer(tickDuration: Duration) extends Cancellable {
  var isCancelled = false

  private class Source extends Async.OriginalSource[this.TimerEvent] {
    def tick() = synchronized {
      listeners.foreach(_.completeNow(TimerEvent.Tick, this))
    }
    val listeners = mutable.Set[Listener[TimerEvent]]()
    override def poll(k: Listener[TimerEvent]): Boolean = 
      if isCancelled then k.completeNow(TimerEvent.Cancelled, this) else false // subscribing to a timer always takes you to the next tick
    override def dropListener(k: Listener[TimerEvent]): Unit = listeners -= k
    override protected def addListener(k: Listener[TimerEvent]): Unit = 
      if isCancelled then k.completeNow(TimerEvent.Cancelled, this)
      else
        Timer.this.synchronized:
          if isCancelled then k.completeNow(TimerEvent.Cancelled, this)
          else listeners += k
  }
  private val _src = Source()

  /** Ticks of the timer are delivered through this source. Note that ticks are ephemeral. */
  val src: Async.Source[this.TimerEvent] = _src

  def start()(using Async, AsyncOperations): Unit =
    cancellationScope(this):
      loop()

  @tailrec private def loop()(using Async, AsyncOperations): Unit =
    if !isCancelled then
      try
        // println(s"Sleeping at ${new java.util.Date()}, ${isCancelled}, ${this}")
        sleep(tickDuration.toMillis)
      catch
        case _: CancellationException => cancel()
    if !isCancelled then
      _src.tick()
      loop()
    

  override def cancel(): Unit = 
    synchronized { isCancelled = true }
    src.synchronized {
      _src.listeners.foreach(_.completeNow(TimerEvent.Cancelled, src))
      _src.listeners.clear()
    }

  enum TimerEvent:
    case Tick
    case Cancelled
}

