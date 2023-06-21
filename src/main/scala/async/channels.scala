package concurrent
import scala.collection.mutable
import mutable.{ArrayBuffer, ListBuffer}
import fiberRuntime.boundary
import boundary.Label
import fiberRuntime.suspend

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Try}
import Async.{Listener, await}

import java.util.concurrent.atomic.AtomicBoolean
import javax.management.ListenerNotFoundException

/** A common interface for channels */
trait Channel[T]:
  def read()(using Async): T
  def send(x: T)(using Async): Unit
  protected def shutDown(finalValue: T): Unit

object Channel:

  extension [T](c: Channel[Try[T]])
    def close(): Unit =
      c.shutDown(Failure(ChannelClosedException()))

class ChannelClosedException extends Exception

/** An unbuffered, synchronous channel. Senders and readers both block
 *  until a communication between them happens. The channel provides two
 *  async sources, one for reading and one for sending. If a send operation
 *  encounters some waiting readers, or a read operation encounters some
 *  waiting sender the data is transmitted directly. Otherwise we add
 *  the operation to the corresponding pending set.
 */
trait SyncChannel[T] extends Channel[T]:

  val canRead: Async.Source[T]
  val canSend: Async.Source[Listener[T]]

  def send(x: T)(using Async): Unit = await(canSend)(x)

  def read()(using Async): T = await(canRead)

object SyncChannel:

  def apply[T](): SyncChannel[T] = new SyncChannel[T]:

    private val pendingReads = ArrayBuffer[Listener[T]]()
    private val pendingSends = ArrayBuffer[Listener[Listener[T]]]()
    private var isClosed = false

    private def ensureOpen() =
      if isClosed then throw ChannelClosedException()

    private def link[T](pending: ArrayBuffer[T], op: T => Boolean): Boolean =
      ensureOpen()
      // Since sources are filterable, we have to match all pending readers or writers
      // against the incoming request
      pending.iterator.find(op) match
        case Some(elem) => pending -= elem; true
        case None => false

    private def p[T](x: T): Boolean = { println(x); true }

    private def collapse[T](k2: Listener[Listener[T]]): Option[T] =
      println("--")
      var r: Option[T] = None
      var rr = if k2 {
        x => r = Some(x);
          println("setting r to " + r);
          true } then r else None
      println("returning " + rr)
      rr


    val canRead = new Async.OriginalSource[T]:
      def poll(k: Listener[T]): Boolean =
        link(pendingSends, sender => collapse(sender).map(k) == Some(true))
      def addListener(k: Listener[T]) =
        SyncChannel.this.synchronized:
          pendingReads += k
        var _pendingSends : List[Listener[Listener[T]]] = List()
        SyncChannel.this.synchronized:
          _pendingSends = pendingSends.toList
          println("canRead copied " + _pendingSends)
        var doNothingNow = false
        for (ps <- _pendingSends) {
          SyncChannel.this.synchronized:
            if (!doNothingNow && pendingSends.contains(ps) && p("here(canRead)") && collapse(ps).map(k) == Some(true)) {
              println("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX")
              pendingReads -= k
              pendingSends -= ps
              doNothingNow = true
            }
        }

      def dropListener(k: Listener[T]): Unit =
        SyncChannel.this.synchronized:
          pendingReads -= k

    val canSend = new Async.OriginalSource[Listener[T]]:
      def poll(k: Listener[Listener[T]]): Boolean =
        link(pendingReads, k(_))
      def addListener(k: Listener[Listener[T]]) =
        SyncChannel.this.synchronized:
          pendingSends += k
        var _pendingReads : List[Listener[T]] = List()
        SyncChannel.this.synchronized:
          _pendingReads = pendingReads.toList
          println("canSend copied " + _pendingReads)
        var doNothingNow = false
        for (pr <- _pendingReads) {
          SyncChannel.this.synchronized:
            if (!doNothingNow && pendingReads.contains(pr) && p("here(canSend)") && k(pr)) {
              println("YYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYY")
              pendingReads -= pr
              pendingSends -= k
              doNothingNow = true
            }
        }

      def dropListener(k: Listener[Listener[T]]): Unit =
        SyncChannel.this.synchronized:
          pendingSends -= k

    protected def shutDown(finalValue: T) =
      isClosed = true
      pendingReads.foreach(_(finalValue))

end SyncChannel

@main def miniTest(): Unit =
  given ExecutionContext = ExecutionContext.global
  Async.blocking:
    val c = SyncChannel[Int]()
    val f1 = Future:
      for (i <- 1 to 10)
        c.send(i)
      println("THREAD SENDING 1..10 DONE")
    val f2 = Future:
      for (i <- 1 to 10)
        println("RECEIVED " + c.read())
      println("RECEIVING THREAD DONE")

    Thread.sleep(3000)
    println("done after wait")

@main def TestChannel11() =
  given ExecutionContext = ExecutionContext.global
  Async.blocking:
    val c = SyncChannel[Option[Int]]()
    val f1 = Future:
      println("before sending")
      c.send(Some(17))
      println("after sending")
    val f2 = Future:
      val z = c.read()
      println(z)
    f2.result
  //  Thread.sleep(1000)
  println("done and done")

@main def TestChannel() =
  given ExecutionContext = ExecutionContext.global
  Async.blocking:
    val c = SyncChannel[Option[Int]]()
    val f1 = Future:
      for i <- 0 to 100 do
        c.send(Some(i))
      c.send(None)
    val f2 = Future:
      var sum = 0
      def loop(): Unit =
        val got = c.read()
        got match
          case Some(x) => sum += x; println("got " + x); loop()
          case None => println("so far sum: " + sum)
      loop()
    Thread.sleep(5000)
//    println("final state pending reads: " + c.pendingReads.size)
//    println("final state pending writes: " + c.pendingSends.size)
    println("done")
//    val chan = SyncChannel[Int]()
//    val allTasks = List(
//        Task:
//          println("task1")
//          chan.read(),
//        Task:
//          println("task2")
//          chan.read()
//      )
//    println("here already")
//
//    def start() = Future:
//      println("starting?")
//      val lastSum = allTasks.map(_.run.value).sum
//      println("lastSum is " + lastSum)
//
//    start()

def TestRace =
  val c1, c2 = SyncChannel[Int]()
  val s = c1.canSend
  val c3 = Async.race(c1.canRead, c2.canRead)
  val c4 = c3.filter(_ >= 0)
  val d0 = SyncChannel[Int]()
  val d1 = Async.race(c1.canRead, c2.canRead, d0.canRead)
  val d2 = d1.map(_ + 1)
  val c5 = Async.either(c1.canRead, c2.canRead)
    .map:
      case Left(x) => -x
      case Right(x) => x
    .filter(_ >= 0)

  val d5 = Async.either(c1.canRead, d2)
    .map:
      case Left(x) => -x
      case Right(x) => x

