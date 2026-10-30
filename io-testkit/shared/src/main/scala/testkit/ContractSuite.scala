package gears.async.io
package testkit

import gears.async.Async
import gears.async.AsyncOperations

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import munit.FunSuite

/** What every contract suite shares: the backend under test, `running`, which runs a check as a program over it, and
  * the helpers the checks use, all through `perform`, so that a check runs the same on every backend. A backend's suite
  * extends the contracts of the capabilities its backend has (`OpsContract`, `IpStreamContract` and the rest), with the
  * backend; each contract asks for the capabilities it checks, by its `backend`'s type.
  */
trait ContractSuite extends FunSuite {

  /** The backend under test. */
  val backend: Backend

  /** The backend's handles, and the kinds every backend has. */
  protected type Handle = backend.Handle
  protected type Readable = backend.Readable
  protected type Writable = backend.Writable

  /** Runs `body` as a program over `backend`, given the backend's `Io`, and returns its result: `Io.run`, unless a
    * backend's suite runs its programs another way.
    */
  protected def running[T](body: Io[backend.type] ?=> (Async.Spawn, AsyncOperations) ?=> T): T =
    Io.run(using backend)(body)

  /** Checks this backend is known to fail, by name: they are expected to fail, so one that starts passing is reported,
    * to be taken off the list.
    */
  def knownFailures: Set[String] = Set.empty

  override def test(options: munit.TestOptions)(body: => Any)(using loc: munit.Location): Unit =
    super.test(if knownFailures(options.name) then options.fail else options)(body)

  // The buffers of every check, which its futures share across threads.
  protected lazy val arena: BufferArena = BufferArena.open()

  /** A buffer of `capacity` bytes that the backend's ops can fill and drain, live until the suite ends. */
  protected def allocate(capacity: Int): ByteBuffer = arena.allocate(capacity)

  override def afterAll(): Unit = {
    arena.close()
    super.afterAll()
  }

  /** Performs `op` and returns its failure, or null if it completed. */
  protected def attempt(io: Io[backend.type], op: io.Op)(using Async): Throwable | Null =
    try {
      io.perform(op)
      null
    } catch case e: Exception => e

  /** Performs `op`, which opens a handle into `into`, and returns the handle. */
  protected def opening[K <: Handle](io: Io[backend.type], into: Slot[K], op: io.Op)(using Async): K = {
    io.perform(op)
    into.clear()
  }

  /** Closes `handle`, as the interface has it. */
  protected def close(io: Io[backend.type], handle: Handle)(using Async): Unit =
    io.perform(io.backend.ops.close(handle))

  /** Opens something on a free port: no interface tells which port is free, so it tries a few. */
  protected def onFreePort[K](open: Int => K): (K, Int) = {
    val random = new java.util.Random()
    var attempts = 0
    var result: (K, Int) | Null = null
    while result == null do {
      val port = 20000 + random.nextInt(30000)
      try result = (open(port), port)
      catch {
        case e: IOException =>
          attempts += 1
          if attempts > 10 then throw e
      }
    }
    result
  }

  /** A buffer holding `text`, flipped and ready to drain. */
  protected def bytes(text: String): ByteBuffer = {
    val encoded = text.getBytes(StandardCharsets.UTF_8)
    val buf = allocate(math.max(encoded.length, 1))
    buf.put(encoded).flip()
    buf
  }

  /** What `buf` has been filled with, from 0 to its position. */
  protected def text(buf: ByteBuffer): String = {
    val copy = buf.duplicate().flip()
    val out = new Array[Byte](copy.remaining())
    copy.get(out)
    new String(out, StandardCharsets.UTF_8)
  }

  /** Runs `body` with a temporary directory, deleted with what is in it once `body` returns. */
  protected def withTempDir[T](body: Path => T): T = {
    val dir = Files.createTempDirectory("gears-io")
    try body(dir)
    finally
      if Files.exists(dir) then {
        val entries = Files.list(dir)
        try entries.forEach(Files.deleteIfExists(_): Unit)
        finally entries.close()
        Files.deleteIfExists(dir): Unit
      }
  }
}
