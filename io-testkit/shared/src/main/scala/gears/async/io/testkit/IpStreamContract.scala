package gears.async.io
package testkit

import gears.async.Async
import gears.async.Future
import gears.async.Future.awaitAll

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

/** What a backend with IP stream sockets must do, with or without IP datagram sockets. */
trait IpStreamContract extends IpStreamFixture {
  val backend: Backend { type Interface <: IpStreams }

  /** A listener on `address`, listening, from the `listen` op, whose failure is thrown. */
  private def tcpListen(io: Io[backend.Interface], address: IpSocketAddress)(using Async): io.ops.Listener = {
    opening(io)(slot => io.ops.listen(address, slot))
  }

  /** A connection to `address`, from the `connect` op, whose failure is thrown. */
  private def tcpConnect(io: Io[backend.Interface], address: IpSocketAddress)(using Async): io.ops.Connection = {
    opening(io)(slot => io.ops.connect(address, slot))
  }

  /** A connection to the listener on the loopback at `port`: its client's end and the one `listener` accepted. */
  private def tcpPair(io: Io[backend.Interface], listener: io.ops.Listener, port: Int)(using
      Async.Spawn
  ): (io.ops.Connection, io.ops.Connection) = {
    pair(io, listener)(tcpConnect(io, IpSocketAddress(IpAddress.IPv4Loopback, port)))
  }

  /** Performs `op` with a time limit, and returns its failure, or null if it completed. */
  private def tcpAttemptWithin(io: Io[backend.Interface], op: io.Op, limit: FiniteDuration)(using
      Async
  ): Throwable | Null =
    try {
      io.perform(op, limit)
      null
    } catch case e: Exception => e

  test("an IPv4 stream socket accepts, connects and echoes") {
    running { io ?=>
      val listener = tcpListen(io, IpSocketAddress(IpAddress.IPv4Loopback, 0))
      val port = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress].port
      try {
        val (client, server) = tcpPair(io, listener, port)
        try {
          writeAll(io, client, bytes("tcp ping"))
          val buf = allocate(16)
          io.perform(io.ops.read(server, buf))
          readExactly(io, io.ops.read(server, buf), buf, 8)
          assertEquals(text(buf), "tcp ping")
          buf.flip()
          writeAll(io, server, buf)
          val reply = allocate(16)
          io.perform(io.ops.read(client, reply))
          readExactly(io, io.ops.read(client, reply), reply, 8)
          assertEquals(text(reply), "tcp ping")
          close(io, server)
          assertEquals(readToEnd(io, io.ops.read(client, reply), reply), "tcp ping")
        } finally close(io, client)
      } finally close(io, listener)
    }
  }

  test("a stream shut down for writing sends what was written, then its end, and still reads") {
    running { io ?=>
      val listener = tcpListen(io, IpSocketAddress(IpAddress.IPv4Loopback, 0))
      val port = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress].port
      try {
        val (client, server) = tcpPair(io, listener, port)
        try {
          // The client's request, then its end: the server reads it to the end, as it would a closed peer's.
          writeAll(io, client, bytes("request"))
          io.perform(io.ops.shutdownOutput(client))
          val buf = allocate(32)
          assertEquals(readToEnd(io, io.ops.read(server, buf), buf), "request")
          // The reply goes back on the half-closed connection, which the client still reads, to the server's end.
          writeAll(io, server, bytes("reply"))
          io.perform(io.ops.shutdownOutput(server))
          val reply = allocate(32)
          assertEquals(readToEnd(io, io.ops.read(client, reply), reply), "reply")
          val late = attempt(io, io.ops.write(client, bytes("late")))
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
        val listener = tcpListen(io, IpSocketAddress(IpAddress.IPv4Loopback, 0))
        val port = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress].port
        try {
          val (client, server) = tcpPair(io, listener, port)
          try {
            writeAll(io, client, bytes("first"))
            val into = Slot[Received]()
            io.perform(io.ops.read(server, pool, into))
            assertEquals(readPooledExactly(io, io.ops.read(server, pool, into), into, 5), "first")
            writeAll(io, client, bytes("second"))
            val buf = allocate(64)
            io.perform(io.ops.read(server, buf))
            readExactly(io, io.ops.read(server, buf), buf, 6)
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
  private def withConnections(io: Io[backend.Interface], count: Int)(
      body: Seq[(io.ops.Connection, io.ops.Connection)] => Unit
  )(using Async.Spawn): Unit = {
    val listener = tcpListen(io, IpSocketAddress(IpAddress.IPv4Loopback, 0))
    val port = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress].port
    val pairs = scala.collection.mutable.ArrayBuffer.empty[(io.ops.Connection, io.ops.Connection)]
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
  private def readPooled(io: Io[backend.Interface], handle: io.ops.Readable, pool: BufferPool)(using
      Async
  ): Received | Throwable = {
    val into = Slot[Received]()
    val failure = attempt(io, io.ops.read(handle, pool, into))
    if failure != null then failure else into.clear()
  }

  test("a pool's buffers go to the reads that take them: with a pool of two, two connections' reads hold both") {
    val pool = backend.pool(bufferSize = 16, buffers = 2)
    try
      running { io ?=>
        withConnections(io, 2) { case Seq((client1, server1), (client2, server2)) =>
          writeAll(io, client1, bytes("a"))
          writeAll(io, client2, bytes("b"))
          val held = readPooled(io, server1, pool) match {
            case received: Received => received
            case failure: Throwable => fail("the first connection's pooled read failed", failure)
          }
          readPooled(io, server2, pool) match {
            case received: Received =>
              assertEquals(textOf(received), "b")
              received.release()
            case failure: Throwable => fail("the second connection's read found no buffer free", failure)
          }
          assertEquals(textOf(held), "a")
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
          val idle = tcpAttemptWithin(io, io.ops.read(server1, pool, Slot[Received]()), 20.millis)
          assert(idle.isInstanceOf[TimeoutException], String.valueOf(idle))
          assertEquals(pool.available, 1, "the read that timed out holds no buffer")
          writeAll(io, client2, bytes("c"))
          readPooled(io, server2, pool) match {
            case received: Received =>
              assertEquals(textOf(received), "c")
              received.release()
            case failure: Throwable => fail("the other connection's read found no buffer free", failure)
          }
          writeAll(io, client1, bytes("d"))
          readPooled(io, server1, pool) match {
            case received: Received =>
              assertEquals(textOf(received), "d", "what came for the read that timed out is the next read's")
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
        val write = io.ops.write(client, big)
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
      val listener = tcpListen(io, IpSocketAddress(IpAddress.IPv4Loopback, 0))
      val port = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress].port
      try {
        val (client, server) = tcpPair(io, listener, port)
        try {
          // More than the kernel's and the backend's buffers hold, to a peer that never reads.
          val big = allocate(16 << 20)
          val write = io.ops.write(client, big)
          val writing = Future(while big.hasRemaining() do io.perform(write))
          io.perform(io.ops.timer(200))
          assert(big.hasRemaining(), "the peer's buffers took everything")
          io.perform(io.ops.close(client))
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
      val listener = tcpListen(io, IpSocketAddress(IpAddress.IPv4Loopback, 0))
      val port = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress].port
      try {
        val (client, server) = tcpPair(io, listener, port)
        close(io, server)
        close(io, client)
      } finally close(io, listener)
    }
  }

  test("listening on an address in use fails the listen op, leaving its slot empty") {
    running { io ?=>
      val taken = tcpListen(io, IpSocketAddress(IpAddress.IPv4Loopback, 0))
      val port = io.ops.localAddress(taken).asInstanceOf[IpSocketAddress].port
      try {
        val into = Slot[io.ops.Listener]()
        val failure = attempt(io, io.ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, port), into))
        assert(failure.isInstanceOf[IOException], String.valueOf(failure))
        intercept[IllegalStateException](into.clear())
      } finally close(io, taken)
    }
  }

  test("acceptAll takes the pending connections, at most max at a time, until each is accepted") {
    running { io ?=>
      val listener = tcpListen(io, IpSocketAddress(IpAddress.IPv4Loopback, 0))
      val port = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress].port
      try {
        // Connected before any accept: they wait in the listener's queue.
        val clients = (1 to 3).map(_ => tcpConnect(io, IpSocketAddress(IpAddress.IPv4Loopback, port)))
        val accepted = Slot[Array[io.ops.Connection]]()
        val accept = io.ops.acceptAll(listener, accepted, max = 2)
        val servers = scala.collection.mutable.ArrayBuffer.empty[io.ops.Handle]
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
      val listener = tcpListen(io, IpSocketAddress(IpAddress.IPv4Loopback, 0))
      val port = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress].port
      close(io, listener)
      val into = Slot[io.ops.Connection]()
      val failure = attempt(io, io.ops.connect(IpSocketAddress(IpAddress.IPv4Loopback, port), into))
      assert(failure.isInstanceOf[IOException], String.valueOf(failure))
      intercept[IllegalStateException](into.clear())
    }
  }

  test("a port whose connection lingers in TIME_WAIT can be listened on again at once") {
    running { io ?=>
      val listener = tcpListen(io, IpSocketAddress(IpAddress.IPv4Loopback, 0))
      val port = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress].port
      val (client, server) = tcpPair(io, listener, port)
      close(io, server) // the server's side closes first, so its end of the connection is left in TIME_WAIT
      val buf = allocate(8)
      io.perform(io.ops.read(client, buf)) // the server's end
      close(io, client)
      close(io, listener)
      close(io, tcpListen(io, IpSocketAddress(IpAddress.IPv4Loopback, port)))
    }
  }

  test("an accept on a closed listener fails, as does one waiting when it is closed") {
    running { io ?=>
      val listener = tcpListen(io, IpSocketAddress(IpAddress.IPv4Loopback, 0))
      val waiting = Future(io.perform(io.ops.accept(listener, Slot[io.ops.Connection]())))
      io.perform(io.ops.timer(20)) // for the accept to be waiting
      close(io, listener)
      val failure = waiting.awaitResult.failed.toOption.orNull
      assert(failure.isInstanceOf[ClosedChannelException], String.valueOf(failure))
      val after = attempt(io, io.ops.accept(listener, Slot[io.ops.Connection]()))
      assert(after.isInstanceOf[ClosedChannelException], String.valueOf(after))
    }
  }

  test("a closed listener's port can be listened on again once its close op completes") {
    running { io ?=>
      val first = tcpListen(io, IpSocketAddress(IpAddress.IPv4Loopback, 0))
      val port = io.ops.localAddress(first).asInstanceOf[IpSocketAddress].port
      io.perform(io.ops.close(first))
      close(io, tcpListen(io, IpSocketAddress(IpAddress.IPv4Loopback, port)))
    }
  }

  test("a close op on a connection ends its peer's stream") {
    running { io ?=>
      val listener = tcpListen(io, IpSocketAddress(IpAddress.IPv4Loopback, 0))
      val port = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress].port
      try {
        val (client, server) = tcpPair(io, listener, port)
        try {
          io.perform(io.ops.close(client))
          val buf = allocate(16)
          assertEquals(readToEnd(io, io.ops.read(server, buf), buf), "")
        } finally close(io, server)
      } finally close(io, listener)
    }
  }

  test("sockets know their addresses: a listener on port 0 its port, a connection both ends, as its peer sees them") {
    running { io ?=>
      val listener = tcpListen(io, IpSocketAddress(IpAddress.IPv4Loopback, 0))
      try {
        val local = io.ops.localAddress(listener)
        val port = local match {
          case IpSocketAddress(IpAddress.IPv4Loopback, port) if port > 0 => port
          case other => fail(s"a listener on port 0's address: $other")
        }
        val (client, server) = tcpPair(io, listener, port)
        try {
          assertEquals(io.ops.peerAddress(client), local)
          assertEquals(io.ops.localAddress(server), local)
          assertEquals(
            io.ops.peerAddress(server),
            io.ops.localAddress(client),
            "the client, as the server sees it"
          )
          io.ops.localAddress(client) match {
            case IpSocketAddress(IpAddress.IPv4Loopback, port) => assert(port > 0)
            case other                                         => fail(s"a client's address: $other")
          }
        } finally {
          close(io, client)
          close(io, server)
        }
      } finally close(io, listener)
      intercept[IOException](io.ops.localAddress(listener))
    }
  }

  test("an IPv6 listener on port 0 reports an IPv6 address") {
    running { io ?=>
      val listener = tcpListen(io, IpSocketAddress(IpAddress.IPv6Loopback, 0))
      try
        io.ops.localAddress(listener) match {
          case IpSocketAddress(IpAddress.IPv6Loopback, port) => assert(port > 0)
          case other                                         => fail(s"an IPv6 listener's address: $other")
        }
      finally close(io, listener)
    }
  }

  /** A connection to a listener opened with `listening`, the client opening with `connecting`: the client's and the
    * server's ends, closed after `body`.
    */
  private def connected(io: Io[backend.Interface], listening: SocketOptions, connecting: SocketOptions)(
      body: (io.ops.Connection, io.ops.Connection) => Unit
  )(using Async.Spawn): Unit = {
    val listener =
      opening(io)(slot => io.ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), listening, slot))
    try {
      val address = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress]
      val (client, server) = pair(io, listener)(opening(io)(slot => io.ops.connect(address, connecting, slot)))
      try body(client, server)
      finally {
        close(io, client)
        close(io, server)
      }
    } finally close(io, listener)
  }

  test("a connection has NoDelay on at both ends unless it is set off, given to the client or to the listener") {
    running { io ?=>
      assert(io.ops.supports(SocketOption.NoDelay, SocketOption.Kind.Connection), "every backend has NoDelay")
      locally {
        connected(io, SocketOptions.default, SocketOptions.default) { (client, server) =>
          assertEquals(io.ops.option(client, SocketOption.NoDelay), true)
          assertEquals(io.ops.option(server, SocketOption.NoDelay), true)
        }
        val off = SocketOptions.default.set(SocketOption.NoDelay, false)
        connected(io, off, off) { (client, server) =>
          assertEquals(io.ops.option(client, SocketOption.NoDelay), false)
          assertEquals(io.ops.option(server, SocketOption.NoDelay), false, "the listener's, on what it accepts")
        }
      }
    }
  }

  test("keepalive given as a socket opens is the connection's, and the listener's is each accepted one's") {
    running { io ?=>
      assume(
        io.ops.supports(SocketOption.KeepAlive, SocketOption.Kind.Connection),
        "this backend has no keepalive option"
      )
      val on = SocketOption.KeepAlive.On(30, 5, 3)
      locally {
        connected(io, SocketOptions.default.set(SocketOption.KeepAlive, on), SocketOptions.default) {
          (client, server) =>
            assertEquals(
              io.ops.option(client, SocketOption.KeepAlive),
              SocketOption.KeepAlive.Off,
              "the system's, off"
            )
            assertEquals(io.ops.option(server, SocketOption.KeepAlive), on)
        }
        connected(io, SocketOptions.default, SocketOptions.default.set(SocketOption.KeepAlive, on)) { (client, _) =>
          assertEquals(io.ops.option(client, SocketOption.KeepAlive), on)
        }
      }
    }
  }

  test("a buffer size given as a socket opens is a request the system meets at least") {
    running { io ?=>
      for
        option <- List(SocketOption.ReceiveBuffer, SocketOption.SendBuffer)
        if io.ops.supports(option, SocketOption.Kind.Connection)
      do
        locally {
          connected(io, SocketOptions.default, SocketOptions.default.set(option, 65536)) { (client, _) =>
            val size = io.ops.option(client, option)
            assert(size >= 65536, s"$option: $size")
          }
        }
    }
  }

  test("an option that can change is changed on an open connection; one that cannot, or one missing, is refused") {
    running { io ?=>
      connected(io, SocketOptions.default, SocketOptions.default) { (client, _) =>
        def check[V](option: SocketOption[V], value: V, readsBack: Boolean)(using munit.Location): Unit =
          if !io.ops.supports(option, SocketOption.Kind.Connection) then {
            intercept[IllegalArgumentException](io.ops.option(client, option))
            intercept[IllegalArgumentException](
              io.ops
                .connect(IpSocketAddress(IpAddress.IPv4Loopback, 9), SocketOptions.default.set(option, value), Slot())
            )
          } else if !io.ops.canChange(option, SocketOption.Kind.Connection) then
            intercept[IllegalArgumentException](io.ops.setOption(client, option, value))
          else {
            io.ops.setOption(client, option, value)
            if readsBack then assertEquals(io.ops.option(client, option), value, s"$option")
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
    running { io ?=>
      assume(
        io.ops.supports(SocketOption.ReusePort, SocketOption.Kind.Listener),
        "this backend has no reuse-port option"
      )
      val reuse = SocketOptions.default.set(SocketOption.ReusePort, true)
      intercept[IllegalArgumentException](io.ops.connect(IpSocketAddress(IpAddress.IPv4Loopback, 9), reuse, Slot()))
      locally {
        val first = opening(io)(slot => io.ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), reuse, slot))
        try {
          assertEquals(io.ops.option(first, SocketOption.ReusePort), true)
          val address = io.ops.localAddress(first).asInstanceOf[IpSocketAddress]
          val second = opening(io)(slot => io.ops.listen(address, reuse, slot))
          try {
            val refused = Slot[io.ops.Listener]()
            val failure = attempt(io, io.ops.listen(address, refused))
            assert(failure.isInstanceOf[IOException], s"one without it: $failure")
          } finally close(io, second)
        } finally close(io, first)
      }
    }
  }

  test("a stream socket takes no DontFragment, which is for datagram sockets") {
    running { io ?=>
      assert(!io.ops.supports(SocketOption.DontFragment, SocketOption.Kind.Connection))
      intercept[IllegalArgumentException](
        io.ops.connect(
          IpSocketAddress(IpAddress.IPv4Loopback, 9),
          SocketOptions.default.set(SocketOption.DontFragment, true),
          Slot()
        )
      )
    }
  }

  test("a connection's gathering write and scattering read carry a header and its body in order") {
    running { io ?=>
      connected(io, SocketOptions.default, SocketOptions.default) { (client, server) =>
        val header = allocate(4)
        header.putInt(5).flip()
        writeAll(io, client, Array(header, bytes("hello")))
        val into = Array(allocate(4), allocate(5))
        while into(1).hasRemaining() do io.perform(io.ops.read(server, into))
        assertEquals(into(0).getInt(0), 5)
        into(1).flip()
        assertEquals(StandardCharsets.UTF_8.decode(into(1)).toString, "hello")
      }
    }
  }

  test("accepts pending at once on a listener take turns, from any future, each its own connection") {
    running { io ?=>
      val listener = tcpListen(io, IpSocketAddress(IpAddress.IPv4Loopback, 0))
      val port = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress].port
      try {
        val accepts = List.fill(3) {
          val into = Slot[io.ops.Connection]()
          (Future(io.perform(io.ops.accept(listener, into))), into)
        }
        io.perform(io.ops.timer(20))
        assert(accepts.forall(_._1.poll().isEmpty), "all three wait")
        val clients = accepts.map(_ => tcpConnect(io, IpSocketAddress(IpAddress.IPv4Loopback, port)))
        for (accepting, _) <- accepts do assertEquals(accepting.awaitResult.failed.toOption, None)
        val accepted = accepts.map(_._2.clear())
        assertEquals(accepted.distinct.size, 3, "each accept took its own connection")
        for connection <- accepted ++ clients do close(io, connection)
      } finally close(io, listener)
    }
  }

  test(
    "an IPv6 listener on :: accepts an IPv4 client"
  ) {
    running { io ?=>
      val listener = tcpListen(io, IpSocketAddress(IpAddress.IPv6Any, 0))
      val port = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress].port
      try {
        val accepting = Future(opening(io)(slot => io.ops.accept(listener, slot)))
        val client = opening(io)(slot => io.ops.connect(IpSocketAddress(IpAddress.IPv4Loopback, port), slot))
        close(io, client)
        close(io, accepting.await)
      } finally close(io, listener)

    }
  }

  test("connect, listen and shutdownOutput are one-shot: performed again, they throw IllegalStateException") {
    running { io ?=>
      val into = Slot[io.ops.Listener]()
      val listening = io.ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), into)
      io.perform(listening)
      val listener = into.clear()
      val address = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress]
      intercept[IllegalStateException](io.perform(listening))
      val accepted = Slot[io.ops.Connection]()
      val accepting = Future(io.perform(io.ops.accept(listener, accepted)))
      val connected = Slot[io.ops.Connection]()
      val connecting = io.ops.connect(address, connected)
      io.perform(connecting)
      intercept[IllegalStateException](io.perform(connecting))
      val client = connected.clear()
      val shutting = io.ops.shutdownOutput(client)
      io.perform(shutting)
      intercept[IllegalStateException](io.perform(shutting))
      close(io, client)
      accepting.await
      close(io, accepted.clear())
      close(io, listener)
    }
  }

  test("clients and a server echo over stream sockets") {
    val clients = 16
    val trips = 200
    val echoed =
      running { io ?=>
        val (listener, connect) = listenAndConnect(io)
        try {
          val server = Future {
            val accepted = Slot[io.ops.Connection]()
            val accept = io.ops.accept(listener, accepted)
            val connections = (1 to clients).map { _ =>
              io.perform(accept)
              val socket = accepted.clear()
              Future {
                val buf = arena.allocate(64)
                val read = io.ops.read(socket, buf)
                val write = io.ops.write(socket, buf)
                try
                  for _ <- 1 to trips do {
                    buf.clear().limit(5)
                    while buf.hasRemaining do io.perform(read)
                    buf.flip()
                    while buf.hasRemaining do io.perform(write)
                  }
                finally io.perform(io.ops.close(socket))
              }
            }
            connections.awaitAll
          }
          val done = (1 to clients).map { _ =>
            Future {
              val socket = connect()
              try {
                val buf = arena.allocate(64)
                val read = io.ops.read(socket, buf)
                val write = io.ops.write(socket, buf)
                for _ <- 1 to trips do {
                  buf.clear().put("hello".getBytes(StandardCharsets.UTF_8)).flip()
                  while buf.hasRemaining do io.perform(write)
                  buf.clear().limit(5)
                  while buf.hasRemaining do io.perform(read)
                  assertEquals(text(buf), "hello")
                }
                1
              } finally io.perform(io.ops.close(socket))
            }
          }
          val echoed = done.awaitAll.sum
          server.await
          echoed
        } finally io.perform(io.ops.close(listener))
      }
    assertEquals(echoed, clients)
  }
}
