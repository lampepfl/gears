package gears.async
import scala.collection.mutable
import mutable.{ArrayBuffer, ListBuffer}

import scala.util.{Failure, Success, Try}
import Async.await

import scala.util.control.Breaks.{break, breakable}
import gears.async.Async.Source
import gears.async.listeners.lockBoth
import gears.async.Listener.acceptingListener

/** Signals that the channel is closed. */
case object Closed

type Closed = Closed.type

private type Res[T] = Either[Closed, T]

/** The part of a channel one can send values to. Blocking behavior depends on the implementation.
  */
trait SendableChannel[-T]:
  /** Create an [[Async.Source]] representing the send action of value [[x]]. Note that *each* listener attached to and
    * accepting a [[Sent]] value corresponds to [[x]] being sent once.
    *
    * To create an [[Async.Source]] that sends the item exactly once regardless of listeners attached, wrap the [[send]]
    * operation inside a [[gears.async.Future]].
    */
  def sendSource(x: T): Async.Source[Res[Unit]]

  /** Send [[x]] over the channel, blocking (asynchronously with [[Async]]) until the item has been sent or, if the
    * channel is buffered, queued. Throws [[ChannelClosedException]] if the channel was closed.
    */
  def send(x: T)(using Async): Unit = Async.await(sendSource(x)) match
    case Right(_) => ()
    case Left(_)  => throw ChannelClosedException()
end SendableChannel

/** The part of a channel one can read values from. Blocking behavior depends on the implementation.
  */
trait ReadableChannel[+T]:
  /** An [[Async.Source]] corresponding to items being sent over the channel. Note that *each* listener attached to and
    * accepting a [[Read]] value corresponds to one value received over the channel.
    *
    * To create an [[Async.Source]] that reads *exactly one* item regardless of listeners attached, wrap the [[read]]
    * operation inside a [[gears.async.Future]].
    */
  val readSource: Async.Source[Res[T]]

  /** Read an item from the channel, blocking (asynchronously with [[Async]]) until the item has been received. Returns
    * `Failure(ChannelClosedException)` if the channel was closed.
    */
  def read()(using Async): Res[T] = await(readSource)
end ReadableChannel

/** A generic channel that can be sent to, received from and closed. */
trait Channel[T] extends SendableChannel[T], ReadableChannel[T], java.io.Closeable:
  inline final def asSendable: SendableChannel[T] = this
  inline final def asReadable: ReadableChannel[T] = this
  inline final def asCloseable: java.io.Closeable = this

  protected type Reader = Listener[Res[T]]
  protected type Sender = Listener[Res[Unit]]
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

/** UnboundedChannel are buffered channels that do not bound the number of items in the channel. In other words, the
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
      if checkClosed(readSource, r) then true
      else if !buf.isEmpty then
        if r.completeNow(Right(buf.head), readSource) then
          buf.dequeue()
          if cells.hasSender then
            val (src, s) = cells.nextSender
            cells.dequeue() // buf always has space available after dequeue
            senderToBuf(src, s)
        true
      else false

    // Try to add a sender to the buffer
    def senderToBuf(src: CanSend, s: Sender): Boolean =
      if buf.size < size then
        if s.completeNow(Right(()), src) then buf += src.item
        true
      else false
  end Impl
end BufferedChannel

object UnboundedChannel:
  def apply[T](): UnboundedChannel[T] = Impl[T]()

  private final class Impl[T]() extends Channel.Impl[T] with UnboundedChannel[T] {
    val buf = new mutable.Queue[T]()

    override def sendImmediately(x: T): Unit =
      var result: SendResult = Left(Closed)
      pollSend(CanSend(x), acceptingListener((r, _) => result = r))
      if result.isLeft then throw ChannelClosedException()

    override def pollRead(r: Reader): Boolean = synchronized:
      if checkClosed(readSource, r) then true
      else if !buf.isEmpty then
        if r.completeNow(Right(buf.head), readSource) then
          // there are never senders in the cells
          buf.dequeue()
        true
      else false

    override def pollSend(src: CanSend, s: Sender): Boolean = synchronized:
      if !checkClosed(src, s)
        && !cells.matchSender(src, s)
        && s.completeNow(Right(()), src)
      then buf += src.item
      true
  }
end UnboundedChannel

object Channel:
  private[async] abstract class Impl[T] extends Channel[T]:
    protected type ReadResult = Res[T]
    protected type SendResult = Res[Unit]

    var isClosed = false
    val cells = CellBuf()
    // Poll a reader, returning false if it should be put into queue
    def pollRead(r: Reader): Boolean
    // Poll a reader, returning false if it should be put into queue
    def pollSend(src: CanSend, s: Sender): Boolean

    protected final def checkClosed[T](src: Async.Source[Res[T]], l: Listener[Res[T]]): Boolean =
      if isClosed then
        l.completeNow(Left(Closed), src)
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
        if !isClosed then
          isClosed = true
          cells.cancel()

    /** Complete a pair of locked sender and reader. */
    protected final def complete(src: CanSend, reader: Listener[ReadResult], sender: Listener[SendResult]) =
      reader.complete(Right(src.item), readSource)
      sender.complete(Right(()), src)

    // Not a case class because equality should be referential, as otherwise
    // dependent on a (possibly odd) equality of T. Users do not expect that
    // cancelling a send of a given item might in fact cancel that of an equal one.
    protected final class CanSend(val item: T) extends Source[SendResult] {
      override def poll(k: Listener[SendResult]): Boolean = pollSend(this, k)
      override def onComplete(k: Listener[SendResult]): Unit = Impl.this.synchronized:
        if !pollSend(this, k) then cells.addSender(this, k)
      override def dropListener(k: Listener[SendResult]): Unit = Impl.this.synchronized:
        if !isClosed then cells.dropSender(this, k)
    }

    /** CellBuf is a queue of cells, which consists of a sleeping sender or reader. The queue always guarantees that
      * there are *only* all readers or all senders. It must be externally synchronized.
      */
    private[async] class CellBuf():
      type Cell = Reader | (CanSend, Sender)
      // reader == 0 || sender == 0 always
      private var reader = 0
      private var sender = 0

      private val pending = mutable.Queue[Cell]()

      /* Boring push/pop methods */

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
      def dropSender(src: CanSend, s: Sender): this.type =
        if sender > 0 then if pending.removeFirst(_ == (src, s)).isDefined then sender -= 1
        this

      /** Match a possible reader to a queue of senders: try to go through the queue with lock pairing, stopping when
        * finding a good pair.
        */
      def matchReader(r: Reader): Boolean =
        while hasSender do
          val (src, s) = nextSender
          tryComplete(src, s)(r) match
            case ()               => return true
            case listener: r.type => return true
            case _                => dequeue() // drop gone sender from queue
        false

      /** Match a possible sender to a queue of readers: try to go through the queue with lock pairing, stopping when
        * finding a good pair.
        */
      def matchSender(src: CanSend, s: Sender): Boolean =
        while hasReader do
          val r = nextReader
          tryComplete(src, s)(r) match
            case ()               => return true
            case listener: s.type => return true
            case _                => dequeue() // drop gone reader from queue
        false

      private inline def tryComplete(src: CanSend, s: Sender)(r: Reader): s.type | r.type | Unit =
        lockBoth(readSource, src)(r, s) match
          case Listener.Locked =>
            Impl.this.complete(src, r, s)
            dequeue() // drop completed reader/sender from queue
            ()
          case listener: (r.type | s.type) => listener

      def cancel() =
        pending.foreach {
          case (src, s)  => s.completeNow(Left(Closed), src)
          case r: Reader => r.completeNow(Left(Closed), readSource)
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
  * When a publisher or subscriber channel is closed, it will be removed from the multiplexer's set.
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
  def start()(using Async): Unit

  def addPublisher(c: ReadableChannel[T]): Unit
  def removePublisher(c: ReadableChannel[T]): Unit

  def addSubscriber(c: SendableChannel[Try[T]]): Unit
  def removeSubscriber(c: SendableChannel[Try[T]]): Unit
end ChannelMultiplexer

object ChannelMultiplexer:
  private enum Message:
    case Quit, Refresh

  def apply[T](): ChannelMultiplexer[T] = Impl[T]()

  private class Impl[T] extends ChannelMultiplexer[T]:
    private var isClosed = false
    private val publishers = ArrayBuffer[ReadableChannel[T]]()
    private val subscribers = ArrayBuffer[SendableChannel[Try[T]]]()
    private val infoChannel = UnboundedChannel[Message]()

    def start()(using Async) = {
      var shouldTerminate = false
      var publishersCopy: Seq[ReadableChannel[T]] = null
      var subscribersCopy: List[SendableChannel[Try[T]]] = null
      while (!shouldTerminate) {
        synchronized:
          publishersCopy = publishers.toSeq

        Async.select(
          (infoChannel.readSource ~~> {
            case Left(_) | Right(Message.Quit) =>
              synchronized:
                subscribersCopy = subscribers.toList
              for (s <- subscribersCopy) s.send(Failure(ChannelClosedException()))
              shouldTerminate = true
            case Right(Message.Refresh) => ()
          }) +:
            publishersCopy.map { pub =>
              pub.readSource ~~> {
                case Right(v) =>
                  synchronized:
                    subscribersCopy = subscribers.toList
                  var c = 0
                  for (s <- subscribersCopy) {
                    c += 1
                    try s.send(Success(v))
                    catch
                      case closedEx: ChannelClosedException =>
                        removeSubscriber(s)
                  }
                case Left(_) => removePublisher(pub)
              }
            }*
        )
      }
    }

    override def close(): Unit =
      val shouldStop = synchronized:
        if !isClosed then
          isClosed = true
          true
        else false
      if shouldStop then infoChannel.sendImmediately(Message.Quit)

    override def removePublisher(c: ReadableChannel[T]): Unit =
      synchronized:
        if isClosed then throw ChannelClosedException()
        publishers -= c
      infoChannel.sendImmediately(Message.Refresh)

    override def removeSubscriber(c: SendableChannel[Try[T]]): Unit = synchronized:
      if isClosed then throw ChannelClosedException()
      subscribers -= c

    override def addPublisher(c: ReadableChannel[T]): Unit =
      synchronized:
        if isClosed then throw ChannelClosedException()
        publishers += c
      infoChannel.sendImmediately(Message.Refresh)

    override def addSubscriber(c: SendableChannel[Try[T]]): Unit = synchronized:
      if isClosed then throw ChannelClosedException()
      subscribers += c

end ChannelMultiplexer
