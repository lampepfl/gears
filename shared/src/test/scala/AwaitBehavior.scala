import gears.async.Async
import gears.async.Async.Spawn
import gears.async.CancellationException
import gears.async.Future
import gears.async.Listener
import gears.async.SyncChannel
import gears.async.default.given

import scala.util.Success
import scala.util.Try

class AwaitBehavior extends munit.FunSuite:
  given munit.Assertions = this

  class FutHandle(using a: Async, sp: a.type & Async.Spawn):
    val source = TSource()
    private var res0 = Future.Promise[Int]()
    // cancelled Futures complete with CancellationException even if the body terminated -> store result externally
    private val fut = Future { res0.complete(Try(source.awaitResult)) }
    while (source.listener.isEmpty) do Thread.`yield`()
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
      Thread.sleep(100) // cannot detect when fut2 starts waiting for lock

      handle.cancel()
      handle.locker.release()
      assert(handle.res().failed.filter(_.isInstanceOf[CancellationException]).isSuccess)
      fut2.await
      handle.locker.quit()

  class AsyncLocker(l: Listener[Int], s: Async.Source[Int])(using a: Async, sp: a.type & Async.Spawn):
    private enum Message:
      case Lock
      case LockResult(success: Boolean)
      case Release
      case Complete(data: Int)
      case Done
      case Quit
    private val ch = SyncChannel[Message]()

    def lockAndWait()(using Async) =
      ch.send(Message.Lock)
      ch.read().right.get.asInstanceOf[Message.LockResult].success

    def release()(using Async): Unit =
      ch.send(Message.Release)
      assertEquals(ch.read().right.get, Message.Done)

    def complete(data: Int)(using Async) =
      ch.send(Message.Complete(data))
      assertEquals(ch.read().right.get, Message.Done)

    def completeNow(data: Int)(using Async) =
      if lockAndWait() then
        complete(data)
        true
      else false

    def quit()(using Async) =
      ch.send(Message.Quit)
      f.await

    val f = Future:
      var loop = true
      while loop && !Async.current.group.isCancelled do
        // on scnative, suspending and changing the carrier thread currently kills lock monitors
        ch.readSource.poll().map(_.right.get).foreach {
          case Message.Lock =>
            ch.send(Message.LockResult(l.acquireLock()))
          case Message.Release =>
            l.releaseLock()
            ch.send(Message.Done)
          case Message.Complete(data) =>
            l.complete(data, s)
            ch.send(Message.Done)
          case Message.Quit => loop = false
          case _            => ??? // does not happen
        }
    f.onComplete(Listener { (res, _) =>
      res.failed.foreach: err =>
        println("Async locker failed with:")
        err.printStackTrace()
      ch.close()
    }) // cancels waiting sends/reads
