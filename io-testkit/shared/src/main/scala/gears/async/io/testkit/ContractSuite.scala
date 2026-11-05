package gears.async.io
package testkit

import gears.async.Async
import gears.async.Future

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

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
  protected def running[T](body: Io[backend.type] ?=> Async.Spawn ?=> T): T =
    Io.run(using backend)(body)

  /** Checks this backend is known to fail, by name: they are expected to fail, so one that starts passing is reported,
    * to be taken off the list.
    */
  def knownFailures: Set[String] = Set.empty

  override def test(options: munit.TestOptions)(body: => Any)(using loc: munit.Location): Unit =
    super.test(if knownFailures(options.name) then options.fail else options)(body)

  // The buffers of every check, which its futures share across threads.
  private var openedArena: BufferArena | Null = null

  protected def arena: BufferArena = synchronized {
    if openedArena == null then openedArena = BufferArena.open()
    openedArena.nn
  }

  /** A buffer of `capacity` bytes that the backend's ops can fill and drain, live until the suite ends. */
  protected def allocate(capacity: Int): ByteBuffer = arena.allocate(capacity)

  override def afterAll(): Unit = {
    if openedArena != null then openedArena.nn.close()
    super.afterAll()
  }

  /** Performs `op` and returns its failure, or null if it completed. */
  protected def attempt(io: Io[backend.type], op: io.Op)(using Async): Throwable | Null =
    try {
      io.perform(op)
      null
    } catch case e: Exception => e

  /** Allocates the output slot, builds and performs its opening op, and returns the opened handle. */
  protected def opening[K <: Handle](io: Io[backend.type])(build: Slot[K] => io.Op)(using Async): K = {
    val into = Slot[K]()
    io.perform(build(into))
    into.clear()
  }

  /** Closes `handle`, as the interface has it. */
  protected def close(io: Io[backend.type], handle: Handle)(using Async): Unit =
    io.perform(io.backend.ops.close(handle))

  /** Gives a future just started the time to perform its op and wait in it. */
  protected def settle(io: Io[backend.type])(using Async): Unit = io.perform(io.backend.ops.timer(30))

  /** The failure `future` ended with, or null if it completed. */
  protected def failureOf(future: Future[?])(using Async): Throwable | Null =
    future.awaitResult.failed.toOption.orNull

  /** Drains every buffer, allowing each performance to make only partial progress. */
  protected def writeAll(io: Io[backend.type], out: Writable, buf: ByteBuffer)(using Async): Unit = {
    val write = io.backend.ops.write(out, buf)
    while buf.hasRemaining() do {
      val before = buf.position()
      io.perform(write)
      assert(buf.position() > before, "a nonempty write made no progress")
    }
  }

  protected def writeAll(io: Io[backend.type], out: Writable, bufs: Array[ByteBuffer])(using Async): Unit = {
    val write = io.backend.ops.write(out, bufs)
    while bufs.exists(_.hasRemaining()) do {
      val before = bufs.map(_.remaining().toLong).sum
      io.perform(write)
      assert(bufs.map(_.remaining().toLong).sum < before, "a nonempty write made no progress")
    }
  }

  /** Reads up to an absolute position without consuming bytes beyond it. EOF before that position is a failure. */
  protected def readExactly(io: Io[backend.type], read: io.Op, buf: ByteBuffer, until: Int)(using Async): Unit = {
    require(until >= buf.position() && until <= buf.limit())
    val limit = buf.limit()
    buf.limit(until)
    try
      while buf.hasRemaining() do {
        val before = buf.position()
        io.perform(read)
        if buf.position() == before then throw new EOFException(s"Expected $until bytes, received $before")
      }
    finally buf.limit(limit)
  }

  /** Reads a known byte count across pooled chunks, including a result already waiting in `into`. */
  protected def readPooledExactly(io: Io[backend.type], read: io.Op, into: Slot[Received], size: Int)(using
      Async
  ): String = {
    val bytes = new ByteArrayOutputStream()
    while bytes.size() < size do {
      if into.isEmpty then io.perform(read)
      val received = into.clear()
      try {
        val chunk = received.buffer
        if !chunk.hasRemaining() then throw new EOFException(s"Expected $size bytes, received ${bytes.size()}")
        val copy = new Array[Byte](chunk.remaining())
        chunk.get(copy)
        bytes.write(copy)
        assert(bytes.size() <= size, "the pooled read returned unexpected extra bytes")
      } finally received.release()
    }
    new String(bytes.toByteArray(), StandardCharsets.UTF_8)
  }

  /** Decodes a received chunk without changing its position or releasing it. */
  protected def textOf(received: Received): String = StandardCharsets.UTF_8.decode(received.buffer.duplicate()).toString

  /** Performs `read` until it completes without adding bytes, returning what `buf` then holds. */
  protected def readToEnd(io: Io[backend.type], read: io.Op, buf: ByteBuffer)(using Async): String = {
    var atEnd = false
    while !atEnd do {
      val before = buf.position()
      io.perform(read)
      atEnd = buf.position() == before
    }
    text(buf)
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

}
