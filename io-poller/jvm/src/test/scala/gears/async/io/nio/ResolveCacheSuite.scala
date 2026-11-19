package gears.async.io
package nio

import gears.async.io.shareable.ResolveCache

import scala.concurrent.duration.*

/** The shared layer's cache of resolved names, and that a resolve op is answered from it. */
class ResolveCacheSuite extends munit.FunSuite {
  override def munitTimeout: Duration = 60.seconds

  private val loopback = List(IpAddress.IPv4Loopback)
  private inline val Second = 1_000_000_000L

  override def beforeEach(context: BeforeEach): Unit = ResolveCache.clear()

  test("a name is kept for as long as configured from its lookup, and no longer") {
    ResolveCache.put("kept.test", loopback, now = 0L, kept = 30 * Second)
    assertEquals(ResolveCache.get("kept.test", now = 29 * Second, kept = 30 * Second), loopback)
    // Reading it did not renew it.
    assertEquals(ResolveCache.get("kept.test", now = 30 * Second, kept = 30 * Second), null)
    assertEquals(ResolveCache.get("kept.test", now = 1L, kept = 30 * Second), null, "an expired entry is dropped")
  }

  test("with nothing kept, nothing is answered") {
    ResolveCache.put("off.test", loopback, now = 0L, kept = 0L)
    assertEquals(ResolveCache.get("off.test", now = 1L, kept = 30 * Second), null)
    ResolveCache.put("off.test", loopback, now = 0L, kept = 30 * Second)
    assertEquals(ResolveCache.get("off.test", now = 1L, kept = 0L), null)
  }

  test("a cache at its limit drops what has expired, and everything if nothing has") {
    for i <- 0 until ResolveCache.MaxEntries do ResolveCache.put(s"old-$i.test", loopback, now = 0L, kept = 30 * Second)
    ResolveCache.put("new.test", loopback, now = 31 * Second, kept = 30 * Second)
    assertEquals(ResolveCache.get("new.test", now = 31 * Second, kept = 30 * Second), loopback)
    assertEquals(ResolveCache.get("old-0.test", now = 31 * Second, kept = 30 * Second), null)
  }

  private val both: List[(String, Backend { type Interface = NioOps.type })] =
    List("on the JDK's threads" -> NioBackend, "on the pool" -> NioPoolBackend)

  for (where, backend) <- both do
    test(s"$where, a resolve op is answered from the cache with no lookup, and a lookup fills it") {
      // A name no resolver knows, which only the cache can answer.
      val name = "only-in-the-cache.invalid"
      ResolveCache.put(name, loopback)
      val (cached, looked) = Io.run(using backend) { io ?=>
        val into = Slot[List[IpAddress]]()
        io.perform(io.ops.resolve(name, into))
        val cached = into.clear()
        io.perform(io.ops.resolve("localhost", into))
        (cached, into.clear())
      }
      assertEquals(cached, loopback)
      assertEquals(ResolveCache.get("localhost"), looked)
      // And a name that fails is not kept.
      ResolveCache.clear()
      val failure = Io.run(using backend) { io ?=>
        try { io.perform(io.ops.resolve(name, Slot[List[IpAddress]]())); null }
        catch case failed: java.io.IOException => failed
      }
      assert(failure != null)
      assertEquals(ResolveCache.get(name), null)
    }
}
