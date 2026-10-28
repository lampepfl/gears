package gears.async.io
package testkit

import gears.async.Async
import gears.async.AsyncOperations
import gears.async.AsyncOperations.sleep
import gears.async.Future
import gears.async.Future.awaitAll
import gears.async.io.Backend
import gears.async.io.Io
import gears.async.io.IpAddress
import gears.async.io.IpSockets
import gears.async.io.Pipes
import gears.async.io.ResolvedAddress
import gears.async.io.Resolving
import gears.async.io.Slot
import gears.async.io.UnixAddress
import gears.async.io.UnixStreams
import gears.async.io.testkit.BufferArena
import gears.async.withTimeout

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

import munit.FunSuite

/** A suite of programs over one backend: the backend under test, `running`, which starts a program over it the
  * platform's way (`Io.run`), and what the checks share, buffers from an arena and a listener to connect to.
  * `FuturesContract` and the higher level's checks extend it.
  */
trait ProgramSuite extends FunSuite {

  /** The backend under test: one with sockets, pipes and name resolution, which the checks use. */
  val backend: Backend & IpSockets & Pipes & Resolving

  /** The backend's handles. */
  protected type Handle = backend.Handle
  protected type Readable = backend.Readable
  protected type Writable = backend.Writable
  protected type Connection = backend.Connection
  protected type Listener = backend.Listener
  protected type BoundDatagram = backend.BoundDatagram
  protected type ConnectedDatagram = backend.ConnectedDatagram
  protected type File = backend.File

  /** Performs `op`, which opens a handle into `into`, and returns the handle. */
  protected def opening[K <: Handle](io: Io[backend.type], into: Slot[K], op: io.Op)(using Async): K = {
    io.perform(op)
    into.clear()
  }

  /** Opens a pipe: its read end and its write end. */
  protected def pipe(io: Io[backend.type])(using Async): (Readable, Writable) = {
    val read = Slot[Readable]()
    val write = Slot[Writable]()
    io.perform(io.backend.ops.pipe(read, write))
    (read.clear(), write.clear())
  }

  /** Connects a client to a test's server: each call performs a connect op, and returns a new socket. */
  protected trait Connect {
    def apply()(using Async): Connection
  }

  /** A listener for a test's server, and a way to connect to it: on a Unix socket in `dir` where the backend has them,
    * and on TCP at a free port of the loopback otherwise.
    */
  protected def listenAndConnect(io: Io[backend.type], dir: java.nio.file.Path)(using
      Async
  ): (Listener, Connect) = io.backend match {
    case unix: (gears.async.io.UnixStreams & io.backend.type) =>
      val address = UnixAddress(dir.resolve("echo.sock").toString)
      val listening = Slot[Listener]()
      val listener = opening(io, listening, unix.ops.listen(address, listening))
      val connect = new Connect {
        def apply()(using Async): Connection = {
          val into = Slot[Connection]()
          opening(io, into, unix.ops.connect(address, into))
        }
      }
      (listener, connect)
    case _ =>
      // A port nothing listens on: the first the listen op does not fail on.
      val random = new java.util.Random()
      var attempts = 0
      var found: (Listener, Connect) | Null = null
      while found == null do {
        val address = IpAddress.IPv4("127.0.0.1", 20000 + random.nextInt(30000))
        val listening = Slot[Listener]()
        try {
          val listener = opening(io, listening, io.backend.ops.listen(address, listening))
          val connect = new Connect {
            def apply()(using Async): Connection = {
              val into = Slot[Connection]()
              opening(io, into, io.backend.ops.connect(address, into))
            }
          }
          found = (listener, connect)
        } catch {
          case e: IOException =>
            attempts += 1
            if attempts > 10 then throw e
        }
      }
      found.nn
  }

  /** Runs `body` as a program over `backend`, the platform's way, given the backend's `Io`, and returns its result. */
  protected def running[T](body: Io[backend.type] ?=> (Async.Spawn, AsyncOperations) ?=> T): T

  /** A port nothing listens on, found by listening on it with the backend and letting it go. */
  protected def freePort(): Int = running { io ?=>
    var port = -1
    val random = new java.util.Random()
    var attempts = 0
    while port < 0 do {
      val candidate = 20000 + random.nextInt(30000)
      val into = Slot[Listener]()
      try {
        val listener = opening(io, into, io.backend.ops.listen(IpAddress.IPv4("127.0.0.1", candidate), into))
        io.perform(io.backend.ops.close(listener))
        port = candidate
      } catch {
        case e: IOException =>
          attempts += 1
          if attempts > 10 then throw e
      }
    }
    port
  }

  // The buffers of every test, which their futures share across threads.
  protected lazy val arena: BufferArena = BufferArena.open()

  override def afterAll(): Unit = {
    arena.close()
    super.afterAll()
  }

  protected def withTempDir[T](body: java.nio.file.Path => T): T = {
    val dir = Files.createTempDirectory("gears")
    try body(dir)
    finally
      if Files.exists(dir) then {
        Files.list(dir).forEach(Files.deleteIfExists(_): Unit)
        Files.deleteIfExists(dir): Unit
      }
  }

  protected def text(buf: ByteBuffer): String = {
    val copy = buf.duplicate().flip()
    val out = new Array[Byte](copy.remaining())
    copy.get(out)
    new String(out, StandardCharsets.UTF_8)
  }
}
