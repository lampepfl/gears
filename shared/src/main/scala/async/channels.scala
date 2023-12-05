package gears.async
import scala.collection.mutable
import mutable.{ArrayBuffer, ListBuffer}

import scala.util.{Failure, Success, Try}
import Async.await

import scala.util.control.Breaks.{break, breakable}
import gears.async.Async.Source
import gears.async.listeners.lockBoth
import gears.async.Async.OriginalSource
import gears.async.Listener.acceptingListener

trait BaseChannel[T]:
  /** The result of a channel operation. */
  sealed trait Result:
    def mustSent: Unit = this match
      case Sent => ()
      case _    => throw channelClosedException
    def get: Try[T] = this match
      case Read(item) => Success(item)
      case _          => Failure(channelClosedException)
    def mustRead: T = this match
      case Read(item) => item
      case _          => throw channelClosedException

  /** [[item]] was read from the channel. */
  case class Read(item: T) extends Result

  /** A value was sent to the channel. */
  case object Sent extends Result

  /** The channel was closed. */
  case object Closed extends Result

  /** Possible outcomes of a send operation. */
  type SendResult = Sent.type | Closed.type

  /** Possible outcomes of a read operation. */
  type ReadResult = Read | Closed.type

/** The part of a channel one can send values to. Blocking behavior depends on the implementation.
  */
trait SendableChannel[T] extends BaseChannel[T]:
  /** Create an [[Async.Source]] representing the send action of value [[x]]. Note that *each* listener attached to and
    * accepting a [[Sent]] value corresponds to [[x]] being sent once.
    *
    * To create an [[Async.Source]] that sends the item exactly once regardless of listeners attached, wrap the [[send]]
    * operation inside a [[gears.async.Future]].
    */
  def sendSource(x: T): Async.Source[SendResult]

  /** Send [[x]] over the channel, blocking (asynchronously with [[Async]]) until the item has been sent or, if the
    * channel is buffered, queued. Throws [[ChannelClosedException]] if the channel was closed.
    */
  def send(x: T)(using Async): Unit = Async.await(sendSource(x)) match
    case Sent   => ()
    case Closed => throw channelClosedException
end SendableChannel

/** The part of a channel one can read values from. Blocking behavior depends on the implementation.
  */
trait ReadableChannel[T] extends BaseChannel[T]:
  /** An [[Async.Source]] corresponding to items being sent over the channel. Note that *each* listener attached to and
    * accepting a [[Read]] value corresponds to one value received over the channel.
    *
    * To create an [[Async.Source]] that reads *exactly one* item regardless of listeners attached, wrap the [[read]]
    * operation inside a [[gears.async.Future]].
    */
  val readSource: Async.Source[ReadResult]

  /** Read an item from the channel, blocking (asynchronously with [[Async]]) until the item has been received. Returns
    * `Failure(ChannelClosedException)` if the channel was closed.
    */
  def read()(using Async): Try[T] = await(readSource) match
    case Read(item) => Success(item)
    case Closed     => Failure(channelClosedException)
end ReadableChannel

/** A generic channel that can be sent to, received from and closed. */
trait Channel[T] extends SendableChannel[T], ReadableChannel[T], java.io.Closeable:
  def asSendable: SendableChannel[T] = this
  def asReadable: ReadableChannel[T] = this
  def asCloseable: java.io.Closeable = this

  protected type Reader = Listener[ReadResult]
  protected type Sender = Listener[SendResult]
end Channel

/** SyncChannel, sometimes called a rendez-vous channel has the following semantics:
  *   - `send` to an unclosed channel blocks until a reader commits to receiving the value (via successfully locking).
  */
trait SyncChannel[T] extends Channel[T]

/** BufferedChannel(size: Int) is a version of a channel with an internal value buffer (represented internally as an
  * array with positive size). It has the following semantics:
  *   - `send` if the buffer is not full appends the value to the buffer and success immediately.
  *   - `send` if the buffer is full blocks until some buffer slot is freed and assigned to this sender.
  */
trait BufferedChannel[T] extends Channel[T]

/** UnboundedChannel are buffered channels that does not bound the number of items in the channel. In other words, the
  * buffer is treated as never being full and will expand as needed.
  */
trait UnboundedChannel[T] extends BufferedChannel[T]:
  /** Send the item immediately. Throws [[ChannelClosedException]] if the channel is closed. */
  def sendImmediately(x: T): Unit

/** This exception is being raised by [[Channel.send]] on closed [[Channel]], it is also returned wrapped in `Failure`
  * when reading form a closed channel. [[ChannelMultiplexer]] sends `Failure(ChannelClosedException)` to all
  * subscribers when it receives a `close()` signal.
  */
class ChannelClosedException extends Exception
private val channelClosedException = ChannelClosedException()

object SyncChannel:
  def apply[T](): SyncChannel[T] = Impl()

  private class Impl[T] extends Channel.Impl[T] with SyncChannel[T]:
    override def pollRead(r: Reader): Boolean = synchronized:
      // match reader with buffer of senders
      checkClosed(readSource, r) || cells.matchReader(r)

    override def pollSend(src: CanSend, s: Sender): Boolean = synchronized:
      // match reader with buffer of senders
      checkClosed(src, s) || cells.matchSender(src, s)
  end Impl
end SyncChannel

object BufferedChannel:
  /** Create a new buffered channel with the given buffer size. */
  def apply[T](size: Int = 10): BufferedChannel[T] = Impl(size)
  private class Impl[T](size: Int) extends Channel.Impl[T] with BufferedChannel[T]:
    require(size > 0, "Buffered channels must have a buffer size greater than 0")
    val buf = new mutable.Queue[T](size)

    // Match a reader -> check space in buf -> fail
    override def pollSend(src: CanSend, s: Sender): Boolean = synchronized:
      checkClosed(src, s) || cells.matchSender(src, s) || senderToBuf(src, s)

    // Check space in buf -> fail
    // If we can pop from buf -> try to feed a sender
    override def pollRead(r: Reader): Boolean = synchronized:
      if checkClosed(readSource, r) then return true
      if !buf.isEmpty then
        if r.completeNow(Read(buf.head), readSource) then
          buf.dequeue()
          if cells.hasSender then
            val (src, s) = cells.nextSender
            if senderToBuf(src, s) then cells.dequeue()
        true
      else false

    // Try to add a sender to the buffer
    def senderToBuf(src: CanSend, s: Sender): Boolean =
      if buf.size < size then
        buf += src.item
        s.completeNow(Sent, src)
        true
      else false
  end Impl
end BufferedChannel

object UnboundedChannel:
  def apply[T](): UnboundedChannel[T] = Impl[T]()

  private final class Impl[T]() extends Channel.Impl[T] with UnboundedChannel[T] {
    val buf = new mutable.Queue[T]()

    override def sendImmediately(x: T): Unit =
      var result: SendResult = Closed
      pollSend(CanSend(x), acceptingListener((r, _) => result = r))
      if result == Closed then throw channelClosedException

    override def pollRead(r: Reader): Boolean = synchronized:
      if checkClosed(readSource, r) then true
      else if !buf.isEmpty then
        if r.completeNow(Read(buf.head), readSource) then
          // there are never senders in the cells
          buf.dequeue()
        true
      else false

    override def pollSend(src: CanSend, s: Sender): Boolean = synchronized:
      checkClosed(src, s) || cells.matchSender(src, s) || {
        buf += src.item
        s.completeNow(Sent, src)
        true
      }
  }
end UnboundedChannel

object Channel:
  private[async] abstract class Impl[T] extends Channel[T]:
    var isClosed = false
    val cells = CellBuf()
    def pollRead(r: Reader): Boolean
    def pollSend(src: CanSend, s: Sender): Boolean

    protected final def checkClosed(src: Async.Source[?], l: Listener[Closed.type]): Boolean =
      if isClosed then
        l.completeNow(Closed, src.asInstanceOf[Async.Source[Closed.type] @unchecked /* TODO fix later */ ])
        true
      else false

    override val readSource: Source[ReadResult] = new Source {
      override def poll(k: Reader): Boolean = pollRead(k)
      override def onComplete(k: Reader): Unit = Impl.this.synchronized:
        if !pollRead(k) then cells.addReader(k)
      override def dropListener(k: Reader): Unit = Impl.this.synchronized:
        if !isClosed then cells.dropReader(k)
    }
    override final def sendSource(x: T): Source[SendResult] = CanSend(x)
    override final def close(): Unit =
      synchronized:
        if isClosed then return
        isClosed = true
        cells.cancel()

    protected final def complete(src: CanSend, reader: Listener[ReadResult], sender: Listener[SendResult]) =
      reader.complete(Read(src.item), readSource)
      sender.complete(Sent, src)

    protected final case class CanSend(item: T) extends OriginalSource[SendResult] {
      override def poll(k: Listener[SendResult]): Boolean = pollSend(this, k)
      override protected def addListener(k: Listener[SendResult]): Unit = Impl.this.synchronized:
        if !pollSend(this, k) then cells.addSender(this, k)
      override def dropListener(k: Listener[SendResult]): Unit = Impl.this.synchronized:
        if !isClosed then cells.dropSender(item, k)
    }

    private[async] class CellBuf():
      type Cell = Reader | (CanSend, Sender)
      private var reader = 0
      private var sender = 0
      private val pending = mutable.Queue[Cell]()

      def hasReader = reader > 0
      def hasSender = sender > 0
      def nextReader =
        require(reader > 0)
        pending.head.asInstanceOf[Reader]
      def nextSender =
        require(sender > 0)
        pending.head.asInstanceOf[(CanSend, Sender)]
      def dequeue() =
        pending.dequeue()
        if reader > 0 then reader -= 1 else sender -= 1
      def addReader(r: Reader): this.type =
        require(sender == 0)
        reader += 1
        pending.enqueue(r)
        this
      def addSender(src: CanSend, s: Sender): this.type =
        require(reader == 0)
        sender += 1
        pending.enqueue((src, s))
        this
      def dropReader(r: Reader): this.type =
        if reader > 0 then if pending.removeFirst(_ == r).isDefined then reader -= 1
        this
      def dropSender(item: T, s: Sender): this.type =
        if sender > 0 then if pending.removeFirst(_ == (item, s)).isDefined then sender -= 1
        this

      def matchReader(r: Reader): Boolean =
        while hasSender do
          val (canSend, sender) = nextSender
          lockBoth(readSource, canSend)(r, sender) match
            case Listener.Locked =>
              Impl.this.complete(canSend, r, sender)
              dequeue()
              return true
            case listener: (r.type | sender.type) =>
              if listener == r then return true
              else dequeue()
        false

      def matchSender(src: CanSend, s: Sender): Boolean =
        while hasReader do
          val reader = nextReader
          lockBoth(readSource, src)(reader, s) match
            case Listener.Locked =>
              Impl.this.complete(src, reader, s)
              dequeue()
              return true
            case listener: (reader.type | s.type) =>
              if listener == s then return true
              else dequeue()
        false

      def cancel() =
        pending.foreach {
          case (src, s)  => s.completeNow(Closed, src)
          case r: Reader => r.completeNow(Closed, readSource)
        }
        pending.clear()
        reader = 0
        sender = 0
    end CellBuf
  end Impl
end Channel

/** Channel multiplexer is an object where one can register publisher and subscriber channels. Internally a multiplexer
  * has a thread that continuously races the set of publishers and once it reads a value, it sends a copy to each
  * subscriber.
  *
  * For an unchanging set of publishers and subscribers and assuming that the multiplexer is the only reader of the
  * publisher channels, every subscriber will receive the same set of messages, in the same order and it will be exactly
  * all messages sent by the publishers. The only guarantee on the order of the values the subscribers see is that
  * values from the same publisher will arrive in order.
  *
  * Channel multiplexer can also be closed, in that case all subscribers will receive Failure(ChannelClosedException)
  * but no attempt at closing either publishers or subscribers will be made.
  */
trait ChannelMultiplexer[T] extends java.io.Closeable:
  def addPublisher(c: ReadableChannel[T]): Unit
  def removePublisher(c: ReadableChannel[T]): Unit

  def addSubscriber(c: SendableChannel[Try[T]]): Unit

  def removeSubscriber(c: SendableChannel[Try[T]]): Unit
end ChannelMultiplexer

object ChannelMultiplexer:
  private enum Message:
    case Quit, Refresh

  def apply[T]()(using ac: Async): ChannelMultiplexer[T] = new ChannelMultiplexer[T]:
    private var isClosed = false
    private val publishers = ArrayBuffer[ReadableChannel[T]]()
    private val subscribers = ArrayBuffer[SendableChannel[Try[T]]]()
    private val infoChannel: BufferedChannel[Message] = BufferedChannel[Message](1)
    ac.scheduler.execute { () =>
      var shouldTerminate = false
      var publishersCopy: List[ReadableChannel[T]] = null
      var subscribersCopy: List[SendableChannel[Try[T]]] = null
      while (!shouldTerminate) {
        ChannelMultiplexer.this.synchronized:
          publishersCopy = publishers.toList

        val got =
          Async.await(Async.race(infoChannel.readSource, Async.race(publishersCopy.map(c => c.readSource.map(_.get))*)))
        got match {
          case infoChannel.Read(Message.Quit) => {
            ChannelMultiplexer.this.synchronized:
              subscribersCopy = subscribers.toList
            for (s <- subscribersCopy) s.send(Failure(channelClosedException))
            shouldTerminate = true
          }
          case infoChannel.Read(Message.Refresh) => ()
          case infoChannel.Closed                => shouldTerminate = true // ?
          case v: Try[T] => {
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
