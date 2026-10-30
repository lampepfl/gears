package gears.async.io
package testkit

import gears.async.Async
import gears.async.Future

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

/** What a backend with IP stream sockets must do, with or without IP datagram sockets. */
trait IpStreamContract extends ContractSuite {
  val backend: Backend & IpStreams

  /** A listener on `address`, listening, from the `listen` op, whose failure is thrown. */
  private def tcpListen(io: Io[backend.type], address: IpAddress)(using Async): backend.Listener = {
    val into = Slot[backend.Listener]()
    opening(io, into, io.backend.ops.listen(address, into))
  }

  /** A connection to `address`, from the `connect` op, whose failure is thrown. */
  private def tcpConnect(io: Io[backend.type], address: IpAddress)(using Async): backend.Connection = {
    val into = Slot[backend.Connection]()
    opening(io, into, io.backend.ops.connect(address, into))
  }

  /** A connection to the listener on the loopback at `port`: its client's end and the one `listener` accepted. */
  private def tcpPair(io: Io[backend.type], listener: backend.Listener, port: Int)(using
      Async.Spawn
  ): (backend.Connection, backend.Connection) = {
    val accepted = Slot[backend.Connection]()
    val accepting = Future(opening(io, accepted, io.backend.ops.accept(listener, accepted)))
    val client = tcpConnect(io, IpAddress.IPv4("127.0.0.1", port))
    (client, accepting.await)
  }

  /** Performs `read`, into `buf`, until it completes adding nothing, the end of the stream: what `buf` then holds. */
  private def tcpReadToEnd(io: Io[backend.type], read: io.Op, buf: ByteBuffer)(using Async): String = {
    var atEnd = false
    while !atEnd do {
      val before = buf.position()
      io.perform(read)
      atEnd = buf.position() == before
    }
    text(buf)
  }

  /** Performs `op` with a time limit, and returns its failure, or null if it completed. */
  private def tcpAttemptWithin(io: Io[backend.type], op: io.Op, limit: FiniteDuration)(using Async): Throwable | Null =
    try {
      io.perform(op, limit)
      null
    } catch case e: Exception => e

  private def textOf(received: Received): String = {
    val buf = received.buffer
    val out = new Array[Byte](buf.remaining())
    buf.get(out)
    new String(out, StandardCharsets.UTF_8)
  }

  test("an IPv4 stream socket accepts, connects and echoes") {
    running { io ?=>
      val (listener, port) = onFreePort(port => tcpListen(io, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val (client, server) = tcpPair(io, listener, port)
        try {
          io.perform(io.backend.ops.write(client, bytes("tcp ping")))
          val buf = allocate(16)
          io.perform(io.backend.ops.read(server, buf))
          assertEquals(text(buf), "tcp ping")
          buf.flip()
          io.perform(io.backend.ops.write(server, buf))
          val reply = allocate(16)
          io.perform(io.backend.ops.read(client, reply))
          assertEquals(text(reply), "tcp ping")
          close(io, server)
          assertEquals(tcpReadToEnd(io, io.backend.ops.read(client, reply), reply), "tcp ping")
        } finally close(io, client)
      } finally close(io, listener)
    }
  }

  test("a stream shut down for writing sends what was written, then its end, and still reads") {
    running { io ?=>
      val (listener, port) = onFreePort(port => tcpListen(io, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val (client, server) = tcpPair(io, listener, port)
        try {
          // The client's request, then its end: the server reads it to the end, as it would a closed peer's.
          assertEquals(attempt(io, io.backend.ops.write(client, bytes("request"))), null)
          assertEquals(attempt(io, io.backend.ops.shutdownOutput(client)), null)
          val buf = allocate(32)
          assertEquals(tcpReadToEnd(io, io.backend.ops.read(server, buf), buf), "request")
          // The reply goes back on the half-closed connection, which the client still reads, to the server's end.
          assertEquals(attempt(io, io.backend.ops.write(server, bytes("reply"))), null)
          assertEquals(attempt(io, io.backend.ops.shutdownOutput(server)), null)
          val reply = allocate(32)
          assertEquals(tcpReadToEnd(io, io.backend.ops.read(client, reply), reply), "reply")
          val late = attempt(io, io.backend.ops.write(client, bytes("late")))
          assert(late.isInstanceOf[IOException], s"a write after shutting down: $late")
        } finally {
          close(io, server)
          close(io, client)
        }
        // Shutting down a listener does not compile: `shutdownOutput` takes a `Connection`.
      } finally close(io, listener)
    }
  }

  test("a pooled read and a read into the caller's buffer take turns on one socket") {
    val pool = backend.pool(bufferSize = 64, buffers = 2)
    try
      running { io ?=>
        val (listener, port) = onFreePort(port => tcpListen(io, IpAddress.IPv4("127.0.0.1", port)))
        try {
          val (client, server) = tcpPair(io, listener, port)
          try {
            io.perform(io.backend.ops.write(client, bytes("first")))
            val into = Slot[Received]()
            assertEquals(attempt(io, io.backend.ops.read(server, pool, into)), null)
            val received = into.clear()
            assertEquals(textOf(received), "first")
            received.release()
            io.perform(io.backend.ops.write(client, bytes("second")))
            val buf = allocate(64)
            assertEquals(attempt(io, io.backend.ops.read(server, buf)), null)
            assertEquals(text(buf), "second")
          } finally {
            close(io, server)
            close(io, client)
          }
        } finally close(io, listener)
      }
    finally pool.close()
  }

  /** Runs `body` with `count` connections over loopback, each as its client and server ends, closed afterwards. */
  private def withConnections(io: Io[backend.type], count: Int)(
      body: Seq[(backend.Connection, backend.Connection)] => Unit
  )(using Async.Spawn): Unit = {
    val (listener, port) = onFreePort(port => tcpListen(io, IpAddress.IPv4("127.0.0.1", port)))
    val pairs = scala.collection.mutable.ArrayBuffer.empty[(backend.Connection, backend.Connection)]
    try {
      for _ <- 1 to count do pairs += tcpPair(io, listener, port)
      body(pairs.toSeq)
    } finally {
      for (client, server) <- pairs do {
        close(io, client)
        close(io, server)
      }
      close(io, listener)
    }
  }

  /** A pooled read of `handle` performed to its end: what it received, which the caller releases, or its failure. */
  private def readPooled(io: Io[backend.type], handle: Readable, pool: BufferPool)(using
      Async
  ): Received | Throwable = {
    val into = Slot[Received]()
    val failure = attempt(io, io.backend.ops.read(handle, pool, into))
    if failure != null then failure else into.clear()
  }

  test("a pooled read on a socket fails when the pool has no buffer free, and goes on once one is released") {
    val pool = backend.pool(bufferSize = 16, buffers = 1)
    try
      running { io ?=>
        withConnections(io, 1) { case Seq((client, server)) =>
          io.perform(io.backend.ops.write(client, bytes("one")))
          val held = readPooled(io, server, pool) match {
            case received: Received => received
            case failure: Throwable => fail("the first pooled read failed", failure)
          }
          assertEquals(textOf(held), "one")
          io.perform(io.backend.ops.write(client, bytes("two")))
          val refused = attempt(io, io.backend.ops.read(server, pool, Slot[Received]()))
          assert(refused.isInstanceOf[IOException], String.valueOf(refused))
          held.release()
          readPooled(io, server, pool) match {
            case received: Received =>
              assertEquals(textOf(received), "two", "the refused read left no read pending, and took nothing")
              received.release()
            case failure: Throwable => fail("the pooled read after the release failed", failure)
          }
        }
      }
    finally pool.close()
  }

  test("a pool's buffers go to the reads that take them: with a pool of two, two connections' reads hold both") {
    val pool = backend.pool(bufferSize = 16, buffers = 2)
    try
      running { io ?=>
        withConnections(io, 2) { case Seq((client1, server1), (client2, server2)) =>
          io.perform(io.backend.ops.write(client1, bytes("first")))
          io.perform(io.backend.ops.write(client2, bytes("second")))
          val held = readPooled(io, server1, pool) match {
            case received: Received => received
            case failure: Throwable => fail("the first connection's pooled read failed", failure)
          }
          readPooled(io, server2, pool) match {
            case received: Received =>
              assertEquals(textOf(received), "second")
              received.release()
            case failure: Throwable => fail("the second connection's read found no buffer free", failure)
          }
          assertEquals(textOf(held), "first")
          held.release()
          assertEquals(pool.available, 2, "no buffer is kept for reads not yet made")
        }
      }
    finally pool.close()
  }

  test("a pooled read that timed out holds no buffer: another connection's read takes the pool's one") {
    val pool = backend.pool(bufferSize = 16, buffers = 1)
    try
      running { io ?=>
        withConnections(io, 2) { case Seq((client1, server1), (client2, server2)) =>
          val idle = tcpAttemptWithin(io, io.backend.ops.read(server1, pool, Slot[Received]()), 20.millis)
          assert(idle.isInstanceOf[TimeoutException], String.valueOf(idle))
          assertEquals(pool.available, 1, "the read that timed out holds no buffer")
          io.perform(io.backend.ops.write(client2, bytes("busy")))
          readPooled(io, server2, pool) match {
            case received: Received =>
              assertEquals(textOf(received), "busy")
              received.release()
            case failure: Throwable => fail("the other connection's read found no buffer free", failure)
          }
          io.perform(io.backend.ops.write(client1, bytes("late")))
          readPooled(io, server1, pool) match {
            case received: Received =>
              assertEquals(textOf(received), "late", "what came for the read that timed out is the next read's")
              received.release()
            case failure: Throwable => fail("the read after the timeout failed", failure)
          }
          assertEquals(pool.available, 1)
        }
      }
    finally pool.close()
  }

  test("a write that timed out, its peer not reading, wrote nothing: the buffer is as it was") {
    running { io ?=>
      withConnections(io, 1) { case Seq((client, _)) =>
        val big = allocate(16 << 20)
        val write = io.backend.ops.write(client, big)
        var timedOut = false
        while !timedOut && big.hasRemaining() do {
          val before = big.position()
          val failure = tcpAttemptWithin(io, write, 50.millis)
          if failure != null then {
            assert(failure.isInstanceOf[TimeoutException], String.valueOf(failure))
            assertEquals(big.position(), before, "a write that timed out wrote nothing")
            timedOut = true
          }
        }
        assert(timedOut, "the peer's buffers took everything")
      }
    }
  }

  test("closing a connection whose peer stopped reading completes at once, though bytes written have not gone") {
    running { io ?=>
      val (listener, port) = onFreePort(port => tcpListen(io, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val (client, server) = tcpPair(io, listener, port)
        try {
          // More than the kernel's and the backend's buffers hold, to a peer that never reads.
          val big = allocate(16 << 20)
          val write = io.backend.ops.write(client, big)
          val writing = Future(while big.hasRemaining() do io.perform(write))
          io.perform(io.backend.ops.timer(200))
          assert(big.hasRemaining(), "the peer's buffers took everything")
          assertEquals(attempt(io, io.backend.ops.close(client)), null)
          // The write waiting when the connection closed has failed, as an op on a closed handle.
          val written = writing.awaitResult
          assert(
            written.failed.toOption.exists(_.isInstanceOf[ClosedChannelException]),
            s"the pending write: $written"
          )
        } finally close(io, server)
      } finally close(io, listener)
    }
  }

  // ---- Opening and closing as ops

  test("a listener listens once its listen op completes, and accepts") {
    running { io ?=>
      val (listener, port) = onFreePort(port => tcpListen(io, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val (client, server) = tcpPair(io, listener, port)
        close(io, server)
        close(io, client)
      } finally close(io, listener)
    }
  }

  test("listening on an address in use fails the listen op, leaving its slot empty") {
    running { io ?=>
      val (taken, port) = onFreePort(port => tcpListen(io, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val into = Slot[backend.Listener]()
        val failure = attempt(io, io.backend.ops.listen(IpAddress.IPv4("127.0.0.1", port), into))
        assert(failure.isInstanceOf[IOException], String.valueOf(failure))
        intercept[IllegalStateException](into.clear())
      } finally close(io, taken)
    }
  }

  test("acceptAll takes the pending connections, at most max at a time, until each is accepted") {
    running { io ?=>
      val (listener, port) = onFreePort(port => tcpListen(io, IpAddress.IPv4("127.0.0.1", port)))
      try {
        // Connected before any accept: they wait in the listener's queue.
        val clients = (1 to 3).map(_ => tcpConnect(io, IpAddress.IPv4("127.0.0.1", port)))
        val accepted = Slot[Array[backend.Connection]]()
        val accept = io.backend.ops.acceptAll(listener, accepted, max = 2)
        val servers = scala.collection.mutable.ArrayBuffer.empty[Handle]
        while servers.length < 3 do {
          val failure = attempt(io, accept)
          assert(failure == null, String.valueOf(failure))
          val batch = accepted.clear()
          assert(batch.length >= 1 && batch.length <= 2, s"accepted ${batch.length} at once with max 2")
          servers ++= batch
        }
        assertEquals(servers.distinct.length, 3)
        (servers ++ clients).foreach(close(io, _))
      } finally close(io, listener)
    }
  }

  test("a connect to a port nothing listens on fails, leaving its slot empty") {
    running { io ?=>
      val (listener, port) = onFreePort(port => tcpListen(io, IpAddress.IPv4("127.0.0.1", port)))
      close(io, listener)
      val into = Slot[backend.Connection]()
      val failure = attempt(io, io.backend.ops.connect(IpAddress.IPv4("127.0.0.1", port), into))
      assert(failure.isInstanceOf[IOException], String.valueOf(failure))
      intercept[IllegalStateException](into.clear())
    }
  }

  test("a port whose connection lingers in TIME_WAIT can be listened on again at once") {
    running { io ?=>
      val (listener, port) = onFreePort(port => tcpListen(io, IpAddress.IPv4("127.0.0.1", port)))
      val (client, server) = tcpPair(io, listener, port)
      close(io, server) // the server's side closes first, so its end of the connection is left in TIME_WAIT
      val buf = allocate(8)
      assertEquals(attempt(io, io.backend.ops.read(client, buf)), null) // the server's end
      close(io, client)
      close(io, listener)
      close(io, tcpListen(io, IpAddress.IPv4("127.0.0.1", port)))
    }
  }

  test("an accept on a closed listener fails, as does one waiting when it is closed") {
    running { io ?=>
      val (listener, _) = onFreePort(port => tcpListen(io, IpAddress.IPv4("127.0.0.1", port)))
      val waiting = Future(io.perform(io.backend.ops.accept(listener, Slot[backend.Connection]())))
      io.perform(io.backend.ops.timer(20)) // for the accept to be waiting
      close(io, listener)
      val failure = waiting.awaitResult.failed.toOption.orNull
      assert(failure.isInstanceOf[ClosedChannelException], String.valueOf(failure))
      val after = attempt(io, io.backend.ops.accept(listener, Slot[backend.Connection]()))
      assert(after.isInstanceOf[ClosedChannelException], String.valueOf(after))
    }
  }

  test("a closed listener's port can be listened on again once its close op completes") {
    running { io ?=>
      val (first, port) = onFreePort(port => tcpListen(io, IpAddress.IPv4("127.0.0.1", port)))
      assertEquals(attempt(io, io.backend.ops.close(first)), null)
      close(io, tcpListen(io, IpAddress.IPv4("127.0.0.1", port)))
    }
  }

  test("a close op on a connection ends its peer's stream") {
    running { io ?=>
      val (listener, port) = onFreePort(port => tcpListen(io, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val (client, server) = tcpPair(io, listener, port)
        try {
          assertEquals(attempt(io, io.backend.ops.close(client)), null)
          val buf = allocate(16)
          assertEquals(tcpReadToEnd(io, io.backend.ops.read(server, buf), buf), "")
        } finally close(io, server)
      } finally close(io, listener)
    }
  }

  test("sockets know their addresses: a listener on port 0 its port, a connection both ends, as its peer sees them") {
    running { io ?=>
      val listener = tcpListen(io, IpAddress.IPv4("127.0.0.1", 0))
      try {
        val local = io.backend.ops.localAddress(listener)
        val port = local match {
          case IpAddress.IPv4("127.0.0.1", port) if port > 0 => port
          case other                                         => fail(s"a listener on port 0's address: $other")
        }
        val (client, server) = tcpPair(io, listener, port)
        try {
          assertEquals(io.backend.ops.peerAddress(client), local)
          assertEquals(io.backend.ops.localAddress(server), local)
          assertEquals(
            io.backend.ops.peerAddress(server),
            io.backend.ops.localAddress(client),
            "the client, as the server sees it"
          )
          io.backend.ops.localAddress(client) match {
            case IpAddress.IPv4("127.0.0.1", port) => assert(port > 0)
            case other                             => fail(s"a client's address: $other")
          }
        } finally {
          close(io, client)
          close(io, server)
        }
      } finally close(io, listener)
      intercept[IOException](io.backend.ops.localAddress(listener))
    }
  }

  test("an IPv6 listener on port 0 reports an IPv6 address") {
    running { io ?=>
      val listener = tcpListen(io, IpAddress.IPv6("::1", 0))
      try
        io.backend.ops.localAddress(listener) match {
          case IpAddress.IPv6("::1", port) => assert(port > 0)
          case other                       => fail(s"an IPv6 listener's address: $other")
        }
      finally close(io, listener)
    }
  }

  /** A connection to a listener opened with `listening`, the client opening with `connecting`: the client's and the
    * server's ends, closed after `body`.
    */
  private def connected(io: Io[backend.type], listening: SocketOptions, connecting: SocketOptions)(
      body: (backend.Connection, backend.Connection) => Unit
  )(using Async.Spawn): Unit = {
    val into = Slot[backend.Listener]()
    val listener = opening(io, into, io.backend.ops.listen(IpAddress.IPv4("127.0.0.1", 0), listening, into))
    try {
      val address = io.backend.ops.localAddress(listener).asInstanceOf[IpAddress]
      val accepted = Slot[backend.Connection]()
      val accepting = Future(opening(io, accepted, io.backend.ops.accept(listener, accepted)))
      val clientInto = Slot[backend.Connection]()
      val client = opening(io, clientInto, io.backend.ops.connect(address, connecting, clientInto))
      val server = accepting.await
      try body(client, server)
      finally {
        close(io, client)
        close(io, server)
      }
    } finally close(io, listener)
  }

  test("a connection has NoDelay on at both ends unless it is set off, given to the client or to the listener") {
    assert(backend.ops.supports(SocketOption.NoDelay, SocketOption.Kind.Connection), "every backend has NoDelay")
    running { io ?=>
      connected(io, SocketOptions.default, SocketOptions.default) { (client, server) =>
        assertEquals(io.backend.ops.option(client, SocketOption.NoDelay), true)
        assertEquals(io.backend.ops.option(server, SocketOption.NoDelay), true)
      }
      val off = SocketOptions.default.set(SocketOption.NoDelay, false)
      connected(io, off, off) { (client, server) =>
        assertEquals(io.backend.ops.option(client, SocketOption.NoDelay), false)
        assertEquals(io.backend.ops.option(server, SocketOption.NoDelay), false, "the listener's, on what it accepts")
      }
    }
  }

  test("keepalive given as a socket opens is the connection's, and the listener's is each accepted one's") {
    assume(
      backend.ops.supports(SocketOption.KeepAlive, SocketOption.Kind.Connection),
      "this backend has no keepalive option"
    )
    val on = SocketOption.KeepAlive.On(30, 5, 3)
    running { io ?=>
      connected(io, SocketOptions.default.set(SocketOption.KeepAlive, on), SocketOptions.default) { (client, server) =>
        assertEquals(
          io.backend.ops.option(client, SocketOption.KeepAlive),
          SocketOption.KeepAlive.Off,
          "the system's, off"
        )
        assertEquals(io.backend.ops.option(server, SocketOption.KeepAlive), on)
      }
      connected(io, SocketOptions.default, SocketOptions.default.set(SocketOption.KeepAlive, on)) { (client, _) =>
        assertEquals(io.backend.ops.option(client, SocketOption.KeepAlive), on)
      }
    }
  }

  test("a buffer size given as a socket opens is a request the system meets at least") {
    for
      option <- List(SocketOption.ReceiveBuffer, SocketOption.SendBuffer)
      if backend.ops.supports(option, SocketOption.Kind.Connection)
    do
      running { io ?=>
        connected(io, SocketOptions.default, SocketOptions.default.set(option, 65536)) { (client, _) =>
          val size = io.backend.ops.option(client, option)
          assert(size >= 65536, s"$option: $size")
        }
      }
  }

  test("an option that can change is changed on an open connection; one that cannot, or one missing, is refused") {
    running { io ?=>
      connected(io, SocketOptions.default, SocketOptions.default) { (client, _) =>
        def check[V](option: SocketOption[V], value: V, readsBack: Boolean)(using munit.Location): Unit =
          if !io.backend.ops.supports(option, SocketOption.Kind.Connection) then {
            intercept[IllegalArgumentException](io.backend.ops.option(client, option))
            intercept[IllegalArgumentException](
              io.backend.ops.connect(IpAddress.IPv4("127.0.0.1", 9), SocketOptions.default.set(option, value), Slot())
            )
          } else if !io.backend.ops.canChange(option, SocketOption.Kind.Connection) then
            intercept[IllegalArgumentException](io.backend.ops.setOption(client, option, value))
          else {
            io.backend.ops.setOption(client, option, value)
            if readsBack then assertEquals(io.backend.ops.option(client, option), value, s"$option")
          }
        check(SocketOption.NoDelay, false, readsBack = true)
        check(SocketOption.KeepAlive, SocketOption.KeepAlive.On(30, 5, 3), readsBack = true)
        check(SocketOption.ReceiveBuffer, 65536, readsBack = false) // a request, which the system may round or double
        check(SocketOption.SendBuffer, 65536, readsBack = false)
      }
    }
  }

  test("an option's value out of range is refused as the options are made") {
    intercept[IllegalArgumentException](SocketOptions.default.set(SocketOption.ReceiveBuffer, 0))
    intercept[IllegalArgumentException](
      SocketOptions.default.set(SocketOption.KeepAlive, SocketOption.KeepAlive.On(0, 5, 3))
    )
  }

  test("ReusePort lets listeners that set it share a port, refusing one that does not; a connection takes none") {
    assume(
      backend.ops.supports(SocketOption.ReusePort, SocketOption.Kind.Listener),
      "this backend has no reuse-port option"
    )
    val reuse = SocketOptions.default.set(SocketOption.ReusePort, true)
    intercept[IllegalArgumentException](backend.ops.connect(IpAddress.IPv4("127.0.0.1", 9), reuse, Slot()))
    running { io ?=>
      val into = Slot[backend.Listener]()
      val first = opening(io, into, io.backend.ops.listen(IpAddress.IPv4("127.0.0.1", 0), reuse, into))
      try {
        assertEquals(io.backend.ops.option(first, SocketOption.ReusePort), true)
        val address = io.backend.ops.localAddress(first).asInstanceOf[IpAddress]
        val secondInto = Slot[backend.Listener]()
        val second = opening(io, secondInto, io.backend.ops.listen(address, reuse, secondInto))
        try {
          val refused = Slot[backend.Listener]()
          val failure = attempt(io, io.backend.ops.listen(address, refused))
          assert(failure.isInstanceOf[IOException], s"one without it: $failure")
        } finally close(io, second)
      } finally close(io, first)
    }
  }

  test("a stream socket takes no DontFragment, which is for datagram sockets") {
    assert(!backend.ops.supports(SocketOption.DontFragment, SocketOption.Kind.Connection))
    intercept[IllegalArgumentException](
      backend.ops.connect(
        IpAddress.IPv4("127.0.0.1", 9),
        SocketOptions.default.set(SocketOption.DontFragment, true),
        Slot()
      )
    )
  }

  test("a connection's gathering write and scattering read carry a header and its body in order") {
    running { io ?=>
      connected(io, SocketOptions.default, SocketOptions.default) { (client, server) =>
        val header = allocate(4)
        header.putInt(5).flip()
        assertEquals(attempt(io, io.backend.ops.write(client, Array(header, bytes("hello")))), null)
        val into = Array(allocate(4), allocate(5))
        while into(1).hasRemaining() do assertEquals(attempt(io, io.backend.ops.read(server, into)), null)
        assertEquals(into(0).getInt(0), 5)
        into(1).flip()
        assertEquals(StandardCharsets.UTF_8.decode(into(1)).toString, "hello")
      }
    }
  }

  test("accepts pending at once on a listener take turns, from any future, each its own connection") {
    running { io ?=>
      val (listener, port) = onFreePort(port => tcpListen(io, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val accepts = List.fill(3) {
          val into = Slot[backend.Connection]()
          (Future(io.perform(io.backend.ops.accept(listener, into))), into)
        }
        io.perform(io.backend.ops.timer(20))
        assert(accepts.forall(_._1.poll().isEmpty), "all three wait")
        val clients = accepts.map(_ => tcpConnect(io, IpAddress.IPv4("127.0.0.1", port)))
        for (accepting, _) <- accepts do assertEquals(accepting.awaitResult.failed.toOption, None)
        val accepted = accepts.map(_._2.clear())
        assertEquals(accepted.distinct.size, 3, "each accept took its own connection")
        for connection <- accepted ++ clients do close(io, connection)
      } finally close(io, listener)
    }
  }
}
