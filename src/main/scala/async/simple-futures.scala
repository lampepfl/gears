package simpleFutures

import scala.collection.mutable.ListBuffer
import scala.util.boundary
import boundary.Label
import runtime.suspend

import scala.annotation.unchecked.uncheckedVariance

object Scheduler:
  def schedule(task: Runnable): Unit = ???

trait Async:
  def await[T](f: Future[T]): T

class Future[+T](body: Async ?=> T):
  private var result: Option[T] @uncheckedVariance = None
  private var waiting: ListBuffer[T => Unit] @uncheckedVariance = ListBuffer()
  private def addWaiting(k: T => Unit): Unit = waiting += k

  def await(using a: Async): T = a.await(this)

  private def complete(): Unit =
    Future.async:
      val value = body
      val result = Some(value)
      for k <- waiting do
        Scheduler.schedule(() => k(value))
      waiting.clear()

  Scheduler.schedule(() => complete())

object Future:

  // a handler for Async
  def async(body: Async ?=> Unit): Unit =
    boundary [Unit]:
      given Async with
        def await[T](f: Future[T]): T = f.result match
          case Some(x) => x
          case None => suspend[T, Unit](s => f.addWaiting(s.resume))
      body

end Future

def Test(x: Future[Int], xs: List[Future[Int]]) =
  Future:
    x.await + xs.map(_.await).sum








