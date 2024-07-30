import gears.async.Async
import gears.async.AsyncOperations.sleep
import gears.async.Future
import gears.async.Future.Promise
import gears.async.Listener
import gears.async.Resource
import gears.async.SyncChannel
import gears.async.default.given

import scala.concurrent.duration.FiniteDuration
import scala.util.Success

import concurrent.duration.DurationInt

class ResourceBehavior extends munit.FunSuite {

  def use(container: Container) =
    Async.blocking:
      container.assertInitial()
      container.res.use(_ => container.waitAcquired())
      container.waitReleased()

  def allocated(container: Container) =
    Async.blocking:
      container.assertInitial()
      val res = container.res.allocated
      try container.waitAcquired()
      finally res._2
      container.waitReleased()

  def mappedUse(container: Container) =
    Async.blocking:
      val res = container.res.map: _ =>
        container.waitAcquired()
        "a"
      container.assertInitial()
      res.use: str =>
        assertEquals(str, "a")
        container.waitAcquired()
      container.waitReleased()

  def mappedAllocated(container: Container) =
    Async.blocking:
      val res = container.res.map: _ =>
        container.waitAcquired()
        "a"
      container.assertInitial()

      val ress = res.allocated
      try
        assertEquals(ress._1, "a")
        container.waitAcquired()
      finally ress._2
      container.waitReleased()

  for
    (implName, impl) <- Seq(("apply", () => ResContainer()), ("Future", () => AsyncResContainer()))
    (testName, testCase) <- Seq(
      ("use", use),
      ("allocated", allocated),
      ("mappedUse", mappedUse),
      ("mappedAllocated", mappedAllocated)
    )
  do test(s"$implName - $testName")(testCase(impl()))

  test("leak future") {
    Async.blocking:
      val container = AsyncResContainer()
      val res = Async.group:
        container.res.allocated
      container.waitAcquired()
      res._2
      container.waitReleased()
  }

  abstract class Container:
    var acq = Promise[Unit]()
    var rel = Promise[Unit]()

    def assertInitial() =
      assert(acq.poll().isEmpty)
      assert(rel.poll().isEmpty)

    def waitAcquired()(using Async) =
      acq.await
      assert(rel.poll().isEmpty)

    def assertAcquiredNow() =
      assert(acq.poll().isDefined)
      assert(rel.poll().isEmpty)

    def setAcquired() = acq.complete(Success(()))
    def setReleased() = rel.complete(Success(()))

    def waitReleased()(using Async) =
      acq.await
      rel.await

    def res: Resource[Unit]
  end Container

  class ResContainer extends Container:
    // this should be synchronous
    override def waitAcquired()(using Async): Unit = assertAcquiredNow()
    override def waitReleased()(using Async): Unit =
      assert(acq.poll().isDefined)
      assert(rel.poll().isDefined)

    val res = Resource(
      { assertInitial(); setAcquired() },
      _ => { assertAcquiredNow(); setReleased() }
    )

  class AsyncResContainer extends Container:
    val ch = SyncChannel[Unit]()

    override def waitAcquired()(using Async): Unit = ch.read().right.get

    val res = Resource.spawning(Future {
      assertInitial()
      setAcquired()
      while true do ch.send(())
    }.onComplete(Listener.acceptingListener { (tryy, _) =>
      assert(tryy.isFailure)
      assertAcquiredNow()
      setReleased()
    }))

}
