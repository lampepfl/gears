package gears.async
import scala.collection.mutable
import mutable.{ArrayBuffer, ListBuffer}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import Async.{Listener, await}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.util.control.Breaks.{break, breakable}

/** The part of a channel one can send values to. Blocking behavior depends on the implementation.
 *  Note that while sending is a special (potentially) blocking operation similar to await, reading is
 *  done using Async.Sources of channel values.
 */
trait SendableChannel[T]:
  def send(x: T)(using Async): Unit
end SendableChannel

/** The part of a channel one can read values from. Blocking behavior depends on the implementation.
 *  Note that while sending is a special (potentially) blocking operation similar to await, reading is
 *  done using Async.Sources of channel values.
 */
trait ReadableChannel[T]:

  val canRead: Async.Source[Try[T]]
  def read()(using Async): Try[T] = await(canRead)
end ReadableChannel

trait Channel[T] extends SendableChannel[T], ReadableChannel[T], java.io.Closeable:
  def asSendable(): SendableChannel[T] = this
  def asReadable(): ReadableChannel[T] = this
  def asCloseable(): java.io.Closeable = this

end Channel

/** SyncChannel, sometimes called a rendez-vous channel has the following semantics:
 *  - send to an unclosed channel blocks until a reader willing to accept this value (which is indicated by the
 *    reader's listener returning true after sampling the value) is found and this reader reads the value.
 *  - reading is done via the canRead async source of potential values (wrapped in a Try). Note that only reading
 *    is represented as an async source, sending is a blocking operations that is implemented similarly to how await is implemented.
 */
trait SyncChannel[T] extends Channel[T]

/** BufferedChannel(size: Int) is a version of a channel with an internal value buffer (represented internally as an
 *  array with positive size). It has the following semantics:
 *  - send() if the buffer is not full appends the value to the buffer and returns immediately.
 *  - send() if the buffer is full sleeps until some buffer slot is freed, then writes the value there
 *    and immediately returns.
 *  - reading is done via the canRead async source that awaits the buffer being nonempty and the reader accepting
 *    the first value in the buffer. Because readers can refuse a value, it is possible that many readers await
 *    on canRead while the buffer is non-empty if all of them refused the first value in the buffer. At no point
 *    a reader is allowed to sample/read anything but the first entry in the buffer.
 */
trait BufferedChannel[T] extends Channel[T]

/** This exception is being raised by send()s on closed channel, it is also returned wrapped in Failure
 *  when reading form a closed channel. ChannelMultiplexer sends Failure(ChannelClosedException) to all
 *  subscribers when it receives a close() signal.
 */
class ChannelClosedException extends Exception
private val channelClosedException = ChannelClosedException()


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
      def poll(k: Listener[Try[T]]): Boolean =
        SyncChannel.this.synchronized:
          if (isClosed) k(Failure(channelClosedException))
          else obj.isDefined && testListenerValuePair(k, obj.get)

      def addListener(k: Listener[Try[T]]): Unit =
        SyncChannel.this.synchronized:
          if (isClosed) k(Failure(channelClosedException))
          pendingReads += k
          if (obj.isDefined) testListenerValuePair(k, obj.get)

      def dropListener(k: Listener[Try[T]]): Unit =
        SyncChannel.this.synchronized:
          pendingReads -= k

    def send(v: T)(using Async): Unit =
      if (isClosed) throw channelClosedException
      @volatile var waitingForReadDone = false

      var plantedValue = false
      while (!plantedValue) {
        var haveToWait = false
        SyncChannel.this.synchronized:
          if (isClosed) throw channelClosedException
          if (obj.isEmpty) {
            obj = Some(v)
            senderAwaitingRead = Some(() =>
              SyncChannel.this.synchronized:
                senderAwaitingRead = None
                waitingForReadDone = true
                SyncChannel.this.notifyAll()
            )
            plantedValue = true
          } else {
            haveToWait = true
          }

        if (haveToWait) {
          sendersWaitingSync.synchronized:
            if (isClosed) throw channelClosedException
            sendersWaitingSync.wait()
        }
      }

      SyncChannel.this.synchronized:
        if (isClosed) throw channelClosedException
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
          if (isClosed) throw channelClosedException
          if (!waitingForReadDone) {
            SyncChannel.this.wait()
          }
      }

      sendersWaitingSync.synchronized:
        if (isClosed) throw channelClosedException
        sendersWaitingSync.notifyAll()

    def close(): Unit =
      SyncChannel.this.synchronized:
        isClosed = true
        if (senderAwaitingRead.isDefined) {
          val f = senderAwaitingRead.get
          f()
          senderAwaitingRead = None
        }
        pendingReads.foreach(_(Failure(channelClosedException)))
        pendingReads.clear()

end SyncChannel

object BufferedChannel:

  def apply[T](size: Int = 10): BufferedChannel[T] = new BufferedChannel[T]:
    if (size <= 0) throw IllegalArgumentException("Buffer size must be positive")

    private var isClosed = false
    private val buffer = new Array[Object](size)
    for (i <- 0 until size) buffer(i) = null;
    private var start = 0 // start, always in 0..size-1
    private var end = 0 // one after the end, always in 0..size-1
    private val pendingReads = ArrayBuffer[Listener[Try[T]]]()

    private def testListenerValuePair(l: Listener[Try[T]], v: T): Boolean =
      if (l(Success(v))) {
        buffer(start) = null
        start = (start + 1) % size
        pendingReads -= l
        BufferedChannel.this.notifyAll() // wake up a waiting sender
        true
      } else false

    private def flushListeners(): Boolean =
      var didSomethingInThisIteration = true
      var didSomethingEver = false
      while (didSomethingInThisIteration && (start != end || buffer(0) != null)) {
        didSomethingInThisIteration = false
        breakable {
          for (r <- pendingReads) {
            if (testListenerValuePair(r, buffer(start).asInstanceOf[T]))
              didSomethingInThisIteration = true
              didSomethingEver = true
              break
          }
        }
      }
      didSomethingEver

    val canRead = new Async.OriginalSource[Try[T]]:
      def poll(k: Listener[Try[T]]): Boolean =
        BufferedChannel.this.synchronized:
          if (isClosed) k(Failure(channelClosedException))
          else flushListeners()

      def addListener(k: Listener[Try[T]]): Unit =
        BufferedChannel.this.synchronized:
          if (isClosed) k(Failure(channelClosedException))
          else {
            pendingReads += k
            flushListeners()
          }

      def dropListener(k: Listener[Try[T]]): Unit =
        BufferedChannel.this.synchronized:
          pendingReads -= k

    def send(v: T)(using Async): Unit =
      if (isClosed) throw channelClosedException

      var haveToTryAgain = true
      while (haveToTryAgain) {
        BufferedChannel.this.synchronized:
          if (isClosed) throw channelClosedException
          if (start == end && buffer(0) != null) {
            BufferedChannel.this.wait()
          } else {
            val vo: Object = v.asInstanceOf[Object]
            buffer(end) = vo
            end = (end + 1) % size
            haveToTryAgain = false
            flushListeners()
          }
      }

    def close(): Unit =
      BufferedChannel.this.synchronized:
        isClosed = true
        for (r <- pendingReads) r(Failure(channelClosedException))
        pendingReads.clear()

end BufferedChannel


/** Channel multiplexer is an object where one can register publisher and subscriber channels.
 *  Internally a multiplexer has a thread that continuously races the set of publishers and once it reads
 *  a value, it sends a copy to each subscriber.
 *
 *  For an unchanging set of publishers and subscribers and assuming that the multiplexer is the only reader of
 *  the publisher channels, every subscriber will receive the same set of messages, in the same order and it will be
 *  exactly all messages sent by the publishers. The only guarantee on the order of the values the subscribers see is
 *  that values from the same publisher will arrive in order.
 *
 *  Channel multiplexer can also be closed, in that case all subscribers will receive Failure(ChannelClosedException)
 *  but no attempt at closing either publishers or subscribers will be made.
 */
trait ChannelMultiplexer[T] extends java.io.Closeable:
  def addPublisher(c: ReadableChannel[T]): Unit
  def removePublisher(c: ReadableChannel[T]): Unit

  def addSubscriber(c: SendableChannel[Try[T]]): Unit

  def removeSubscriber(c: SendableChannel[Try[T]]): Unit
end ChannelMultiplexer


object ChannelMultiplexer:
  given ExecutionContext = ExecutionContext.global

  private enum Message:
    case Quit, Refresh

  def apply[T]()(using Async): ChannelMultiplexer[T] = new ChannelMultiplexer[T]:
    private var isClosed = false
    private val publishers = ArrayBuffer[ReadableChannel[T]]()
    private val subscribers = ArrayBuffer[SendableChannel[Try[T]]]()
    private val infoChannel: BufferedChannel[Message] = BufferedChannel[Message](1)
    AsyncFoundations.execute { () =>
      var shouldTerminate = false
      var publishersCopy: List[ReadableChannel[T]] = null
      var subscribersCopy: List[SendableChannel[Try[T]]] = null
      while (!shouldTerminate) {
        ChannelMultiplexer.this.synchronized:
          publishersCopy = publishers.toList

        val got = Async.await(Async.either(infoChannel.canRead, Async.race(publishersCopy.map(c => c.canRead) *)))
        got match {
          case Left(Success(Message.Quit)) => {
            ChannelMultiplexer.this.synchronized:
              subscribersCopy = subscribers.toList
            for (s <- subscribersCopy) s.send(Failure(channelClosedException))
            shouldTerminate = true
          }
          case Left(Success(Message.Refresh)) => ()
          case Left(Failure(_)) => shouldTerminate = true // ?
          case Right(v) => {
            ChannelMultiplexer.this.synchronized:
              subscribersCopy = subscribers.toList
            var c = 0
            for (s <- subscribersCopy) {
              c += 1
              s.send(v)
            }
          }
        }
      }
    }

    override def close(): Unit =
      var shouldStop = false
      ChannelMultiplexer.this.synchronized:
        if (!isClosed) {
          isClosed = true
          shouldStop = true
        }
      if (shouldStop) infoChannel.send(Message.Quit)

    override def removePublisher(c: ReadableChannel[T]): Unit =
      ChannelMultiplexer.this.synchronized:
        if (isClosed) throw channelClosedException
        publishers -= c
      infoChannel.send(Message.Refresh)

    override def removeSubscriber(c: SendableChannel[Try[T]]): Unit =
      ChannelMultiplexer.this.synchronized:
        if (isClosed) throw channelClosedException
        subscribers -= c

    override def addPublisher(c: ReadableChannel[T]): Unit =
      ChannelMultiplexer.this.synchronized:
        if (isClosed) throw channelClosedException
        publishers += c
      infoChannel.send(Message.Refresh)

    override def addSubscriber(c: SendableChannel[Try[T]]): Unit =
      ChannelMultiplexer.this.synchronized:
        if (isClosed) throw channelClosedException
        subscribers += c

end ChannelMultiplexer


@main def channelsMultipleSendersOneReader(): Unit =
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
      ac = true
    val f11 = Future:
      for (i <- 1 to 10000)
        c.send(i)
      aa = true
    val f12 = Future:
      for (i <- 1 to 10000)
          c.send(i)
      ab = true
    val f2 = Future:
      var r = 0
      for (i <- 1 to 30000)
        c.read()
        r += 1
      b = true

    f11.result
    f2.result
    f12.result
    f13.result
    println("all done? " + (aa && ab && ac && b))
