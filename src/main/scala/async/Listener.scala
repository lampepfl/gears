package gears.async

import gears.async.Listener.Locked
import gears.async.Listener.Failed
import gears.async.Listener.SemiLock
import scala.annotation.tailrec
import gears.async.Async.Source

object Listener:
  sealed trait LockResult

  object Locked extends LockResult

  object Failed extends LockResult

  trait SemiLock extends LockResult:
    val nextNumber: Long
    def lockNext(): LockResult

  type ReleaseBoundary = SemiLock | Locked.type

  trait TopLock:
    val selfNumber: Long
    def lockSelf(source: Async.Source[?]): LockResult

  // TODO this should only be used for adapting the source
  inline def mappingTopLock(inner: TopLock | Null, inline body: (Async.Source[?]) => LockResult) =
    if inner != null then
      new TopLock:
        val selfNumber = inner.selfNumber
        def lockSelf(source: Source[?]) = body(source)

  def acceptingListener[T](consumer: T => Unit) =
    new Listener[T]:
      val topLock = null
      def complete(data: T, source: Source[T]) = consumer(data)
      def release(to: ReleaseBoundary) = null

trait Listener[-T]:
  val topLock: Listener.TopLock | Null

  def complete(data: T, source: Async.Source[T]): Unit
  def release(to: Listener.ReleaseBoundary): Listener[?] | Null

  @tailrec
  final def releaseAll(until: Listener.ReleaseBoundary): Unit =
    val rest = release(until)
    if rest != null then rest.releaseAll(until)

  @tailrec
  private def lock(l: Listener.SemiLock): Locked.type | Failed.type =
    l.lockNext() match
      case Locked => Locked
      case Failed =>
        this.releaseAll(l)
        Failed
      case inner: SemiLock => lock(inner)

  def lockCompletely(source: Async.Source[T]): Locked.type | Failed.type =
    this.topLock match
      case topLock: Listener.TopLock =>
        topLock.lockSelf(source) match
          case Locked => Locked
          case Failed => Failed
          case inner: SemiLock => lock(inner)
      case _ => Locked

  def completeNow(data: T, source: Async.Source[T]): Boolean =
    lockCompletely(source) match
      case Locked =>
        this.complete(data, source)
        true
      case Failed => false
