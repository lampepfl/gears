import gears.async.*
import gears.async.default.given

import scala.concurrent.duration.*
import scala.util.Success
import scala.util.Try

class AwaitBehavior extends munit.FunSuite:
  given munit.Assertions = this

  override val munitTimeout = 1.seconds

  class FutHandle(using a: Async, sp: a.type & Async.Spawn):
    val source = TSource()
    private var res0 = Future.Promise[Int]()
    // cancelled Futures complete with CancellationException even if the body terminated -> store result externally
    val fut = Future { res0.complete(Try(source.awaitResult)) }
    while (source.listener.isEmpty) do AsyncOperations.`yield`()
    val listener = source.listener.get
    val locker = AsyncLocker(listener, source)

    def cancel() = fut.cancel()
    def res()(using Async) = res0.awaitResult

  test("completion after cancellation"):
    Async.blocking:
      val handle = FutHandle()
      handle.cancel()
      assert(!handle.locker.completeNow(1))
      assert(handle.res().failed.filter(_.isInstanceOf[CancellationException]).isSuccess)
      handle.locker.quit()

  test("cancellation of await during completion"):
    Async.blocking:
      val handle = FutHandle()
      assert(handle.locker.lockAndWait())
      println(handle.listener)
      println(handle.fut)
      handle.cancel()
      handle.locker.complete(1)
      assertEquals(handle.res(), Success(1))
      handle.locker.quit()

  test("cancellation of await during lock+release"):
    Async.blocking:
      val handle = FutHandle()
      assert(handle.locker.lockAndWait())
      handle.cancel()
      handle.locker.release()
      assert(handle.res().failed.filter(_.isInstanceOf[CancellationException]).isSuccess)
      handle.locker.quit()

  test("cancellation of await with contending lock after release"):
    Async.blocking:
      val handle = FutHandle()
      assert(handle.locker.lockAndWait())
      val fut2 = Future(assert(!handle.listener.acquireLock()))
      AsyncOperations.sleep(100) // cannot detect when fut2 starts waiting for lock

      handle.cancel()
      handle.locker.release()
      assert(handle.res().failed.filter(_.isInstanceOf[CancellationException]).isSuccess)
      fut2.await
      handle.locker.quit()

  class AsyncLocker(l: Listener[Int], s: Async.Source[Int])(using a: Async, sp: a.type & Async.Spawn):
    private enum ReqMessage:
      case Lock
      case Release
      case Complete(data: Int)
      case Quit
    private enum ResMessage:
      case LockResult(success: Boolean)
      case Done

    private val reqCh = SyncChannel[ReqMessage]()
    private val resCh = UnboundedChannel[ResMessage]() // send never blocks

    def lockAndWait()(using Async) =
      reqCh.send(ReqMessage.Lock)
      resCh.read().right.get.asInstanceOf[ResMessage.LockResult].success

    def release()(using Async): Unit =
      reqCh.send(ReqMessage.Release)
      assertEquals(resCh.read().right.get, ResMessage.Done)

    def complete(data: Int)(using Async) =
      reqCh.send(ReqMessage.Complete(data))
      assertEquals(resCh.read().right.get, ResMessage.Done)

    def completeNow(data: Int)(using Async) =
      if lockAndWait() then
        complete(data)
        true
      else false

    def quit()(using Async) =
      reqCh.send(ReqMessage.Quit)
      f.await

    val f = Future:
      var loop = 0
      while loop >= 0 && !Async.current.group.isCancelled do
        if loop == 0 then AsyncOperations.sleep(1)
        loop = (loop + 1) % 10
        // on scnative, suspending and changing the carrier thread currently kills lock monitors
        reqCh.readSource.poll().map(_.right.get).foreach {
          case ReqMessage.Lock =>
            resCh.sendImmediately(ResMessage.LockResult(l.acquireLock()))
          case ReqMessage.Release =>
            l.releaseLock()
            resCh.sendImmediately(ResMessage.Done)
          case ReqMessage.Complete(data) =>
            l.complete(data, s)
            resCh.sendImmediately(ResMessage.Done)
          case ReqMessage.Quit => loop = -1
          case _               => ??? // does not happen
        }

    f.onComplete(Listener { (res, _) =>
      res.failed.foreach: err =>
        println("Async locker failed with:")
        err.printStackTrace()
      reqCh.close()
      resCh.close()
    }) // cancels waiting sends/reads
