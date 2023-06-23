package concurrent
import scala.collection.mutable
import mutable.{ArrayBuffer, ListBuffer}
import fiberRuntime.boundary
import boundary.Label
import fiberRuntime.suspend

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import Async.{Listener, await}

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import javax.management.ListenerNotFoundException
import scala.util.control.Breaks.{break, breakable}
import java.util.concurrent.locks.{Condition, Lock}


trait Channel[T]:
  def read()(using Async): Try[T]
  def send(x: T)(using Async): Unit
  def close(): Unit

class ChannelClosedException extends Exception

trait SyncChannel[T] extends Channel[T]:

  val canRead: Async.Source[Try[T]]
  def read()(using Async): Try[T] = await(canRead)


object SyncChannel:

  def apply[T](): SyncChannel[T] = new SyncChannel[T]:

    private var obj: Option[T] = None
    private val pendingReads = ArrayBuffer[Listener[Try[T]]]()
    private var senderAwaitingRead: Option[() => Unit] = None
    private val sendersWaitingSync = new Object() // TODO maybe this should this be a queue of lock objects

    private var isClosed = false

    private def testListenerValuePair(l: Listener[Try[T]], v: T): Boolean =
      if (l(Success(v))) {
        pendingReads -= l
        obj = None
        val f = senderAwaitingRead.get
        f()
        true
      } else false

    val canRead = new Async.OriginalSource[Try[T]]:
      def poll(k: Listener[Try[T]]): Boolean = SyncChannel.this.synchronized:
          obj.isDefined && testListenerValuePair(k, obj.get)

      def addListener(k: Listener[Try[T]]): Unit =
        SyncChannel.this.synchronized:
          pendingReads += k
          if (obj.isDefined) testListenerValuePair(k, obj.get)

      def dropListener(k: Listener[Try[T]]): Unit =
        SyncChannel.this.synchronized:
          pendingReads -= k

    def send(v: T)(using Async): Unit =
      if (isClosed) throw ChannelClosedException()
      @volatile var waitingForReadDone = false

      var plantedValue = false
      while (!plantedValue) {
        var haveToWait = false
        SyncChannel.this.synchronized:
          if (isClosed) throw ChannelClosedException()
          if (obj.isEmpty) {
            obj = Some(v)
            senderAwaitingRead = Some(() =>
              SyncChannel.this.synchronized:
                senderAwaitingRead = None
                waitingForReadDone = true
                SyncChannel.this.notify()
            )
            plantedValue = true
          } else {
            haveToWait = true
          }

        if (haveToWait) {
          sendersWaitingSync.synchronized:
            if (isClosed) throw ChannelClosedException()
            sendersWaitingSync.wait()
        }
      }

      SyncChannel.this.synchronized:
        if (isClosed) throw ChannelClosedException()
        breakable {
          if (obj.isDefined) {
            for (r <- pendingReads) {
              if (testListenerValuePair(r, obj.get)) {
                break
              }
            }
          }
        }

      while (!waitingForReadDone) {
        SyncChannel.this.synchronized:
          if (isClosed) throw ChannelClosedException()
          if (!waitingForReadDone) SyncChannel.this.wait()
      }

      sendersWaitingSync.synchronized:
        if (isClosed) throw ChannelClosedException()
        sendersWaitingSync.notify()

    def close(): Unit =
      SyncChannel.this.synchronized:
        isClosed = true
        if (senderAwaitingRead.isDefined) {
          val f = senderAwaitingRead.get
          f()
          senderAwaitingRead = None
        }
        pendingReads.foreach(_(Failure(ChannelClosedException())))
        pendingReads.clear()

end SyncChannel

@main def miniTest(): Unit =
  given ExecutionContext = ExecutionContext.global
  Async.blocking:
    var aa = false
    var ab = false
    var ac = false
    var b = false
    val c = SyncChannel[Int]()
    val f13 = Future:
      for (i <- 1 to 10000)
        c.send(i)
      println("THREAD SENDING 3 DONE")
      ac = true
    val f11 = Future:
      for (i <- 1 to 10000)
        try {
          c.send(i)
        } catch {
          case e => println("!!!! " + e)
        }
      println("THREAD SENDING 1 DONE")
      aa = true
    val f12 = Future:
      for (i <- 1 to 10000)
        try {
          c.send(i)
        } catch {
          case e => println("!!!! " + e)
        }
      println("THREAD SENDING 2 DONE")
      ab = true
    val f2 = Future:
      var r = 0
      for (i <- 1 to 30000)
        r += 1
        println("RECEIVED " + c.read() + ", so far: " + r)
      println("THREAD RECEIVING DONE")
      b = true

    f11.result
    f2.result
    f12.result
    f13.result
    println("done after wait " + aa + ab + ac + b)
