package gears.async.io
package node

import gears.async.Future
import gears.async.io.node.host.Addresses
import gears.async.io.node.internal.Os
import gears.async.io.testkit.*
import gears.io.testkit.SimpleIOChecks
import gears.io.testkit.SimpleIOIpStreamChecks
import gears.io.testkit.SimpleIOResolveChecks

import scala.scalajs.js

/** The testkit's contracts over the Node.js backend. Each suite sees only the capabilities required by its contracts.
  *
  * Four contracts are not run, none of which Node.js can meet:
  *
  *   - `PipeContract`: the backend has no `Pipes`. Node makes no pipe of the system's but for a child process, and one
  *     held in memory is not a capability. The stream contract it would run over a pipe runs over TCP, and over a
  *     stream held in memory that is the suites' own (`NodeMemoryStreamSuite`); what it checks of every op, that a
  *     one-shot op is performed once, by one future, and that an opener refuses an occupied slot, `NodeOneShotSuite`
  *     checks on listeners.
  *   - `BlockingContract`: the backend has no `Blocking` ops. A blocking body must run off the thread that runs the
  *     program's futures, and Scala.js has that one thread alone; its checks also block on latches and `Thread.sleep`,
  *     which Scala.js has not. What it checks of every op besides, `NodeOneShotSuite` checks here.
  *   - `UnixDatagramContract`: the backend has no `UnixDatagrams`. Node's `dgram` opens UDP sockets only.
  *   - `ThreadedTimerContract`: it moves deadlines from threads it starts, and Scala.js starts none.
  *     `NodeDeadlineSuite` makes the same checks with the moves coming from callbacks of the event loop's.
  *
  * A program that asks for a capability the backend lacks is refused when compiling, which `NodeCapabilitySuite`
  * checks. Within the contracts that run, six checks skip themselves, by the contracts' own `assume`, each for a socket
  * option Node does not have as the interface means it (see `Options`): keepalive's timings and reuse-port for a stream
  * socket, don't-fragment (two checks) and reuse-port for a datagram socket, and the buffer sizes of a Unix stream
  * socket, the rest of that last check running.
  */
class NodeContractSuite extends OpsContract with NodeSuite {
  val backend: Backend = NodeBackend
}

/** What the backend lacks is refused when compiling, not when running. */
class NodeCapabilitySuite extends munit.FunSuite {
  test("a program over the Node.js backend cannot ask for pipes, blocking ops or Unix datagram sockets") {
    assert(compileErrors("val backend: Backend { type Interface <: Pipes } = NodeBackend").nonEmpty)
    assert(compileErrors("val backend: Backend { type Interface <: Blocking } = NodeBackend").nonEmpty)
    assert(compileErrors("val backend: Backend { type Interface <: UnixDatagrams } = NodeBackend").nonEmpty)
    assert(compileErrors("val backend: Backend { type Interface <: Fifos & RegularFiles } = NodeBackend").isEmpty)
  }
}

/** The stream contracts over a stream held in memory (see `MemoryStreamFixture`), where nothing waits on the system. */
class NodeMemoryStreamSuite extends StreamContract with MemoryStreamFixture with NodeSuite {
  val backend: NodeBackend.type = NodeBackend

  test("a second write pending on a stream fails with IllegalArgumentException") {
    running { io ?=>
      val (in, out) = stream(io)
      try {
        val writing = Future(writeAll(io, out, allocate(1 << 20))) // more than the stream holds: it waits
        settle(io)
        assert(writing.poll().isEmpty, "the stream took the whole payload")
        val second = attempt(io, io.ops.write(out, bytes("z")))
        assert(second.isInstanceOf[IllegalArgumentException], s"a second write: $second")
        writing.cancel()
        writing.awaitResult
      } finally {
        close(io, in)
        close(io, out)
      }
    }
  }
}

/** What `PipeContract` checks of every op, on ops this backend has: one-shot ops, and slots not yet cleared. */
class NodeOneShotSuite extends NodeSuite {
  val backend: Backend { type Interface <: IpStreams } = NodeBackend
  private val loopback = IpSocketAddress(IpAddress.IPv4Loopback, 0)

  test("an opener refuses an occupied slot, opening nothing") {
    running { io ?=>
      val held = Slot[io.ops.Listener]()
      io.perform(io.ops.listen(loopback, held))
      val refused = attempt(io, io.ops.listen(loopback, held)) // `held` still holds the first listener
      assert(refused.isInstanceOf[IllegalArgumentException], String.valueOf(refused))
      val kept = held.clear()
      intercept[IllegalStateException](held.clear()) // nothing more was opened into it
      close(io, kept)
    }
  }

  test("an op that opens or closes is one-shot: performed again, it throws IllegalStateException") {
    running { io ?=>
      val into = Slot[io.ops.Listener]()
      val listening = io.ops.listen(loopback, into)
      io.perform(listening)
      val listener = into.clear()
      intercept[IllegalStateException](io.perform(listening))
      val closing = io.ops.close(listener)
      io.perform(closing)
      intercept[IllegalStateException](io.perform(closing))
    }
  }

  test("a one-shot op performed by two futures at once opens once, the other refused") {
    running { io ?=>
      for _ <- 1 to 100 do {
        val into = Slot[io.ops.Listener]()
        val listening = io.ops.listen(loopback, into)
        val start = Future.Promise[Unit]()
        val both = List.fill(2)(Future {
          start.await
          io.perform(listening)
        })
        start.complete(scala.util.Success(()))
        val failures = both.map(failureOf)
        assertEquals(failures.count(_ == null), 1, failures.mkString(", "))
        assert(failures.exists(_.isInstanceOf[IllegalStateException]), failures.mkString(", "))
        close(io, into.clear())
      }
    }
  }
}

/** Runs the same stream contracts using TCP. */
class NodeSocketStreamSuite extends StreamContract with IpStreamFixture with NodeSuite {
  val backend: Backend { type Interface <: IpStreams } = NodeBackend
}

class NodeIpStreamSuite extends IpStreamContract with NodeSuite {
  val backend: Backend { type Interface <: IpStreams } = NodeBackend

  // Node says a stream has ended only to a read that finds nothing, not to one that takes exactly what was left.
  test("a read that takes exactly the bytes left, the peer's end already in, is followed by a read at the end") {
    running { io ?=>
      val (listener, connect) = listenAndConnect(io)
      try {
        val (client, server) = pair(io, listener)(connect())
        try {
          writeAll(io, client, bytes("abcd"))
          close(io, client)
          io.perform(io.ops.timer(50)) // for the bytes and the end to have reached Node
          val buf = allocate(4)
          readExactly(io, io.ops.read(server, buf), buf, 4)
          assertEquals(text(buf), "abcd")
          val after = allocate(4)
          io.perform(io.ops.read(server, after), scala.concurrent.duration.FiniteDuration(5, "s"))
          assertEquals(after.position(), 0, "the end of the stream")
        } finally close(io, server)
      } finally close(io, listener)
    }
  }
}

class NodeSimpleIOSocketSuite extends SimpleIOChecks with IpStreamFixture with NodeSuite {
  val backend: Backend { type Interface <: IpStreams } = NodeBackend
}

class NodeSimpleIOIpStreamSuite extends SimpleIOIpStreamChecks with NodeSuite {
  val backend: Backend { type Interface <: IpStreams } = NodeBackend
}

class NodeIpDatagramSuite extends IpDatagramContract with NodeSuite {
  val backend: Backend { type Interface <: IpDatagrams } = NodeBackend
}

class NodeUnixStreamSuite extends UnixStreamContract with NodeSuite {
  val backend: Backend { type Interface <: UnixStreams } = NodeBackend
}

class NodeFileSuite extends FileContract with NodeSuite {
  val backend: Backend { type Interface <: RegularFiles } = NodeBackend
}

class NodeResolveSuite extends ResolveContract with NodeSuite {
  val backend: Backend { type Interface <: Resolving } = NodeBackend

  /** A link-local address of an interface of the host's, written with the interface's name as its zone. */
  override protected def namedScopedAddresses: List[(String, IpAddress)] =
    (for
      (name, addresses) <- Os.networkInterfaces().iterator
      address <- addresses.iterator
      if js.typeOf(address.scopeid) == "number" && address.scopeid.asInstanceOf[Int] > 0
      unscoped <- Option(Addresses.parse(address.address.asInstanceOf[String]))
      if unscoped.protocol == Protocol.IPv6
    yield (name, IpAddress(Protocol.IPv6, unscoped.bytes, address.scopeid.asInstanceOf[Int])))
      .nextOption()
      .toList
      .flatMap { (name, expected) =>
        val compressed = IpAddress(Protocol.IPv6, expected.bytes).toString
        val expanded =
          expected.bytes.grouped(2).map(pair => f"${pair(0) & 0xff}%02x${pair(1) & 0xff}%02x").mkString(":")
        List(s"$compressed%$name", s"$expanded%$name", s"[$compressed%$name]").map(_ -> expected)
      }
}

class NodeSimpleIOResolveSuite extends SimpleIOResolveChecks with NodeSuite {
  val backend: Backend { type Interface <: Resolving } = NodeBackend
}

class NodeFifoSuite extends FifoContract with NodeSuite {
  val backend: Backend { type Interface <: Fifos } = NodeBackend

  // What the interface says of a FIFO's end, which this backend reads without a stream of Node's to keep to it.
  test("a FIFO is at its end before a writer has opened it, and after each writer, going on to the next one's bytes") {
    withTempDir { dir =>
      running { io ?=>
        val fifo = dir.resolve("fifo")
        mkfifo(fifo)
        val reader = openFifoForReading(io, fifo.toString)
        try {
          val buf = allocate(16)
          val read = io.ops.read(reader, buf)
          io.perform(read)
          assertEquals(buf.position(), 0, "before any writer")
          for word <- List("first", "second") do {
            val writer = openFifoForWriting(io, fifo.toString)
            writeAll(io, writer, bytes(word))
            close(io, writer)
            buf.clear()
            assertEquals(readToEnd(io, read, buf), word)
          }
        } finally close(io, reader)
      }
    }
  }
}

class NodeFifoFileSuite extends FifoFileContract with NodeSuite {
  val backend: Backend { type Interface <: Fifos & RegularFiles } = NodeBackend
}
