package gears.async.io
package testkit

import gears.async.io.internal.ReactorBackend

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeoutException

/** What a backend with IP stream sockets must do (see `ReactorContract`), with or without IP datagram sockets. */
trait IpStreamContract extends ReactorContract {
  val backend: ReactorBackend & Blocking & Pipes & IpStreams

  /** A listener on `address`, listening, from the `listen` op, whose failure is thrown. */
  protected def listen(r: R, address: IpAddress)(using munit.Location): Listener = {
    val into = Slot[Listener]()
    opened(r, into, backend.ops.listen(address, into))
  }

  /** Submits a `connect` op to `address` on `r`: what it reports, and the slot the socket is written into. */
  protected def startConnect(r: R, address: IpAddress): (Outcome, Slot[Connection]) = {
    val into = Slot[Connection]()
    val connected = Outcome()
    r.submit(backend.ops.connect(address, into), connected)
    (connected, into)
  }

  test("an IPv4 stream socket accepts, connects and echoes") {
    withReactor { r =>
      val (listener, port) = onFreePort(port => listen(r, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val accepted = Slot[Connection]()
        val acceptDone = Outcome()
        r.submit(backend.ops.accept(listener, accepted), acceptDone)
        val (connected, into) = startConnect(r, IpAddress.IPv4("127.0.0.1", port))
        turnUntil(r)(acceptDone.finished && connected.finished)
        assertEquals(connected.completed, 1, String.valueOf(connected.failure))
        val client = into.clear()
        try {
          val server = accepted.clear()
          val sent = Outcome()
          r.submit(backend.ops.write(client, bytes("tcp ping")), sent)
          val buf = allocate(16)
          val got = Outcome()
          r.submit(backend.ops.read(server, buf), got)
          turnUntil(r)(sent.finished && got.finished)
          assertEquals(text(buf), "tcp ping")
          buf.flip()
          val echoed = Outcome()
          r.submit(backend.ops.write(server, buf), echoed)
          val reply = allocate(16)
          val replied = Outcome()
          r.submit(backend.ops.read(client, reply), replied)
          turnUntil(r)(echoed.finished && replied.finished)
          assertEquals(text(reply), "tcp ping")
          close(r, server)
          assertEquals(readToEnd(r, backend.ops.read(client, reply), reply), "tcp ping")
        } finally close(r, client)
      } finally close(r, listener)
    }
  }

  test("a stream shut down for writing sends what was written, then its end, and still reads") {
    withReactor { r =>
      val (listener, port) = onFreePort(port => listen(r, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val accepted = Slot[Connection]()
        val acceptDone = Outcome()
        r.submit(backend.ops.accept(listener, accepted), acceptDone)
        val (connected, into) = startConnect(r, IpAddress.IPv4("127.0.0.1", port))
        turnUntil(r)(acceptDone.finished && connected.finished)
        assertEquals(connected.completed, 1, String.valueOf(connected.failure))
        val client = into.clear()
        val server = accepted.clear()
        try {
          // The client's request, then its end: the server reads it to the end, as it would a closed peer's.
          assertEquals(run(r, backend.ops.write(client, bytes("request"))), null)
          assertEquals(run(r, backend.ops.shutdownOutput(client)), null)
          val buf = allocate(32)
          assertEquals(readToEnd(r, backend.ops.read(server, buf), buf), "request")
          // The reply goes back on the half-closed connection, which the client still reads, to the server's end.
          assertEquals(run(r, backend.ops.write(server, bytes("reply"))), null)
          assertEquals(run(r, backend.ops.shutdownOutput(server)), null)
          val reply = allocate(32)
          assertEquals(readToEnd(r, backend.ops.read(client, reply), reply), "reply")
          val late = run(r, backend.ops.write(client, bytes("late")))
          assert(late.isInstanceOf[IOException], s"a write after shutting down: $late")
        } finally {
          close(r, server)
          close(r, client)
        }
        // Shutting down a listener does not compile: `shutdownOutput` takes a `Connection`.
      } finally close(r, listener)
    }
  }

  test("a pooled read and a read into the caller's buffer take turns on one socket") {
    val pool = backend.pool(bufferSize = 64, buffers = 2)
    try
      withReactor { r =>
        val (listener, port) = onFreePort(port => listen(r, IpAddress.IPv4("127.0.0.1", port)))
        try {
          val accepted = Slot[Connection]()
          r.submit(backend.ops.accept(listener, accepted), Outcome())
          val (connected, into) = startConnect(r, IpAddress.IPv4("127.0.0.1", port))
          turnUntil(r)(connected.finished)
          assertEquals(connected.completed, 1, String.valueOf(connected.failure))
          val client = into.clear()
          try {
            val server = {
              var h: Connection | Null = null
              turnUntil(r) {
                try { h = accepted.clear(); true }
                catch case _: IllegalStateException => false
              }
              h.nn
            }
            try {
              r.submit(backend.ops.write(client, bytes("first")), Outcome())
              val into = Slot[Received]()
              val pooled = Outcome()
              r.submit(backend.ops.read(server, pool, into), pooled)
              turnUntil(r)(pooled.finished)
              assertEquals(pooled.completed, 1, String.valueOf(pooled.failure))
              val received = into.clear()
              assertEquals(textOf(received), "first")
              received.release()
              r.submit(backend.ops.write(client, bytes("second")), Outcome())
              val buf = allocate(64)
              val plain = Outcome()
              r.submit(backend.ops.read(server, buf), plain)
              turnUntil(r)(plain.finished)
              assertEquals(plain.completed, 1, String.valueOf(plain.failure))
              assertEquals(text(buf), "second")
            } finally close(r, server)
          } finally close(r, client)
        } finally close(r, listener)
      }
    finally pool.close()
  }

  /** Runs `body` with `count` connections over loopback, each as its client and server ends, closed afterwards. */
  private def textOf(received: Received): String = {
    val buf = received.buffer
    val out = new Array[Byte](buf.remaining())
    buf.get(out)
    new String(out, StandardCharsets.UTF_8)
  }

  private def withConnections(r: R, count: Int)(body: Seq[(Connection, Connection)] => Unit): Unit = {
    val (listener, port) = onFreePort(port => listen(r, IpAddress.IPv4("127.0.0.1", port)))
    val pairs = scala.collection.mutable.ArrayBuffer.empty[(Connection, Connection)]
    try {
      for _ <- 1 to count do {
        val accepted = Slot[Connection]()
        val acceptedOutcome = Outcome()
        r.submit(backend.ops.accept(listener, accepted), acceptedOutcome)
        val (connected, into) = startConnect(r, IpAddress.IPv4("127.0.0.1", port))
        turnUntil(r)(connected.finished && acceptedOutcome.finished)
        assertEquals(connected.completed, 1, String.valueOf(connected.failure))
        assertEquals(acceptedOutcome.completed, 1, String.valueOf(acceptedOutcome.failure))
        pairs += ((into.clear(), accepted.clear()))
      }
      body(pairs.toSeq)
    } finally {
      for (client, server) <- pairs do {
        close(r, client)
        close(r, server)
      }
      close(r, listener)
    }
  }

  /** A pooled read of `handle` run to its end: what it received, which the caller releases, or its failure. */
  private def readPooled(r: R, handle: Readable, pool: BufferPool)(using munit.Location): Received | Throwable = {
    val into = Slot[Received]()
    val outcome = Outcome()
    r.submit(backend.ops.read(handle, pool, into), outcome)
    turnUntil(r)(outcome.finished)
    if outcome.failure != null then outcome.failure.nn else into.clear()
  }

  test("a pooled read on a socket fails when the pool has no buffer free, and goes on once one is released") {
    val pool = backend.pool(bufferSize = 16, buffers = 1)
    try
      withReactor { r =>
        withConnections(r, 1) { case Seq((client, server)) =>
          r.submit(backend.ops.write(client, bytes("one")), Outcome())
          val held = readPooled(r, server, pool) match {
            case received: Received => received
            case failure: Throwable => fail("the first pooled read failed", failure)
          }
          assertEquals(textOf(held), "one")
          val refused = Slot[Received]()
          val refusedOutcome = Outcome()
          r.submit(backend.ops.read(server, pool, refused), refusedOutcome)
          r.submit(backend.ops.write(client, bytes("two")), Outcome())
          turnUntil(r)(refusedOutcome.finished)
          assert(refusedOutcome.failure.isInstanceOf[IOException], String.valueOf(refusedOutcome.failure))
          held.release()
          readPooled(r, server, pool) match {
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
      withReactor { r =>
        withConnections(r, 2) { case Seq((client1, server1), (client2, server2)) =>
          r.submit(backend.ops.write(client1, bytes("first")), Outcome())
          r.submit(backend.ops.write(client2, bytes("second")), Outcome())
          val held = readPooled(r, server1, pool) match {
            case received: Received => received
            case failure: Throwable => fail("the first connection's pooled read failed", failure)
          }
          readPooled(r, server2, pool) match {
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
      withReactor { r =>
        withConnections(r, 2) { case Seq((client1, server1), (client2, server2)) =>
          val idle = Outcome()
          r.submit(backend.ops.read(server1, pool, Slot[Received]()), idle, timeoutMillis = 20)
          turnUntil(r)(idle.finished)
          assert(idle.failure.isInstanceOf[TimeoutException], String.valueOf(idle.failure))
          assertEquals(pool.available, 1, "the read that timed out holds no buffer")
          r.submit(backend.ops.write(client2, bytes("busy")), Outcome())
          readPooled(r, server2, pool) match {
            case received: Received =>
              assertEquals(textOf(received), "busy")
              received.release()
            case failure: Throwable => fail("the other connection's read found no buffer free", failure)
          }
          r.submit(backend.ops.write(client1, bytes("late")), Outcome())
          readPooled(r, server1, pool) match {
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
    withReactor { r =>
      withConnections(r, 1) { case Seq((client, _)) =>
        val big = allocate(16 << 20)
        val write = backend.ops.write(client, big)
        var timedOut = false
        while !timedOut && big.hasRemaining() do {
          val before = big.position()
          val outcome = Outcome()
          r.submit(write, outcome, timeoutMillis = 50)
          turnUntil(r)(outcome.finished)
          if outcome.failure != null then {
            assert(outcome.failure.isInstanceOf[TimeoutException], String.valueOf(outcome.failure))
            assertEquals(big.position(), before, "a write that timed out wrote nothing")
            timedOut = true
          }
        }
        assert(timedOut, "the peer's buffers took everything")
      }
    }
  }

  test("closing a connection whose peer stopped reading completes at once, though bytes written have not gone") {
    withReactor { r =>
      val (listener, port) = onFreePort(port => listen(r, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val accepted = Slot[Connection]()
        val acceptedOutcome = Outcome()
        r.submit(backend.ops.accept(listener, accepted), acceptedOutcome)
        val (connected, into) = startConnect(r, IpAddress.IPv4("127.0.0.1", port))
        turnUntil(r)(connected.finished && acceptedOutcome.finished)
        val (client, server) = (into.clear(), accepted.clear())
        try {
          // More than the kernel's and the framework's buffers hold, to a peer that never reads.
          val big = allocate(16 << 20)
          big.limit(big.capacity())
          val write = backend.ops.write(client, big)
          var written = Outcome()
          r.submit(write, written)
          val until = System.nanoTime() + 200_000_000L
          while System.nanoTime() < until do {
            r.runOnce(block = false)
            if written.finished && written.failure == null && big.hasRemaining() then {
              written = Outcome()
              r.submit(write, written)
            }
          }
          assert(big.hasRemaining(), "the peer's buffers took everything")
          val closed = Outcome()
          r.submit(backend.ops.close(client), closed)
          turnUntil(r)(closed.finished && written.finished)
          assertEquals(closed.completed, 1, String.valueOf(closed.failure))
        } finally close(r, server)
      } finally close(r, listener)
    }
  }

  // ---- Opening and closing as ops

  test("a listener listens once its listen op completes, and accepts") {
    withReactor { r =>
      val (listener, port) = onFreePort(port => listen(r, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val accepted = Slot[Connection]()
        val acceptDone = Outcome()
        r.submit(backend.ops.accept(listener, accepted), acceptDone)
        val (connected, into) = startConnect(r, IpAddress.IPv4("127.0.0.1", port))
        turnUntil(r)(acceptDone.finished && connected.finished)
        assertEquals(acceptDone.completed, 1, String.valueOf(acceptDone.failure))
        assertEquals(connected.completed, 1, String.valueOf(connected.failure))
        close(r, accepted.clear())
        close(r, into.clear())
      } finally close(r, listener)
    }
  }

  test("listening on an address in use fails the listen op, leaving its slot empty") {
    withReactor { r =>
      val (taken, port) = onFreePort(port => listen(r, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val into = Slot[Listener]()
        val failure = run(r, backend.ops.listen(IpAddress.IPv4("127.0.0.1", port), into))
        assert(failure.isInstanceOf[IOException], String.valueOf(failure))
        intercept[IllegalStateException](into.clear())
      } finally close(r, taken)
    }
  }

  test("acceptAll takes the pending connections, at most max at a time, until each is accepted") {
    withReactor { r =>
      val (listener, port) = onFreePort(port => listen(r, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val connecting = (1 to 3).map(_ => startConnect(r, IpAddress.IPv4("127.0.0.1", port)))
        turnUntil(r)(connecting.forall(_._1.finished))
        val clients = connecting.map { (connected, into) =>
          assertEquals(connected.completed, 1, String.valueOf(connected.failure))
          into.clear()
        }
        val accepted = Slot[Array[Connection]]()
        val accept = backend.ops.acceptAll(listener, accepted, max = 2)
        val servers = scala.collection.mutable.ArrayBuffer.empty[Handle]
        while servers.length < 3 do {
          val failure = run(r, accept)
          assert(failure == null, String.valueOf(failure))
          val batch = accepted.clear()
          assert(batch.length >= 1 && batch.length <= 2, s"accepted ${batch.length} at once with max 2")
          servers ++= batch
        }
        assertEquals(servers.distinct.length, 3)
        (servers ++ clients).foreach(close(r, _))
      } finally close(r, listener)
    }
  }

  test("a connect to a port nothing listens on fails, leaving its slot empty") {
    withReactor { r =>
      val (listener, port) = onFreePort(port => listen(r, IpAddress.IPv4("127.0.0.1", port)))
      close(r, listener)
      val (connected, into) = startConnect(r, IpAddress.IPv4("127.0.0.1", port))
      turnUntil(r)(connected.finished)
      assert(connected.failure.isInstanceOf[IOException], String.valueOf(connected.failure))
      intercept[IllegalStateException](into.clear())
    }
  }

  test("a port whose connection lingers in TIME_WAIT can be listened on again at once") {
    withReactor { r =>
      val (listener, port) = onFreePort(port => listen(r, IpAddress.IPv4("127.0.0.1", port)))
      val (connected, into) = startConnect(r, IpAddress.IPv4("127.0.0.1", port))
      val accepted = Slot[Connection]()
      val acceptedOutcome = Outcome()
      r.submit(backend.ops.accept(listener, accepted), acceptedOutcome)
      turnUntil(r)(connected.finished && acceptedOutcome.finished)
      val client = into.clear()
      close(r, accepted.clear()) // the server's side closes first, so its end of the connection is left in TIME_WAIT
      val buf = allocate(8)
      assertEquals(run(r, backend.ops.read(client, buf)), null) // the server's end
      close(r, client)
      close(r, listener)
      close(r, listen(r, IpAddress.IPv4("127.0.0.1", port)))
    }
  }

  test("an accept on a closed listener fails, as does one waiting when it is closed") {
    withReactor { r =>
      val (listener, _) = onFreePort(port => listen(r, IpAddress.IPv4("127.0.0.1", port)))
      val waiting = Outcome()
      r.submit(backend.ops.accept(listener, Slot[Connection]()), waiting)
      r.runOnce(block = false)
      close(r, listener)
      turnUntil(r)(waiting.finished)
      assert(waiting.failure.isInstanceOf[java.nio.channels.ClosedChannelException], String.valueOf(waiting.failure))
      val after = run(r, backend.ops.accept(listener, Slot[Connection]()))
      assert(after.isInstanceOf[java.nio.channels.ClosedChannelException], String.valueOf(after))
    }
  }

  test("a closed listener's port can be listened on again once its close op completes") {
    withReactor { r =>
      val (first, port) = onFreePort(port => listen(r, IpAddress.IPv4("127.0.0.1", port)))
      val closed = Outcome()
      r.submit(backend.ops.close(first), closed)
      turnUntil(r)(closed.finished)
      assertEquals(closed.completed, 1, String.valueOf(closed.failure))
      close(r, listen(r, IpAddress.IPv4("127.0.0.1", port)))
    }
  }

  test("a close op on a connection ends its peer's stream") {
    withReactor { r =>
      val (listener, port) = onFreePort(port => listen(r, IpAddress.IPv4("127.0.0.1", port)))
      try {
        val accepted = Slot[Connection]()
        val acceptDone = Outcome()
        r.submit(backend.ops.accept(listener, accepted), acceptDone)
        val (connected, into) = startConnect(r, IpAddress.IPv4("127.0.0.1", port))
        turnUntil(r)(acceptDone.finished && connected.finished)
        val client = into.clear()
        val server = accepted.clear()
        try {
          val closed = Outcome()
          r.submit(backend.ops.close(client), closed)
          turnUntil(r)(closed.finished)
          assertEquals(closed.completed, 1, String.valueOf(closed.failure))
          val buf = allocate(16)
          assertEquals(readToEnd(r, backend.ops.read(server, buf), buf), "")
        } finally close(r, server)
      } finally close(r, listener)
    }
  }

  test("sockets know their addresses: a listener on port 0 its port, a connection both ends, as its peer sees them") {
    withReactor { r =>
      val listener = listen(r, IpAddress.IPv4("127.0.0.1", 0))
      try {
        val local = backend.ops.localAddress(listener)
        val port = local match {
          case IpAddress.IPv4("127.0.0.1", port) if port > 0 => port
          case other                                         => fail(s"a listener on port 0's address: $other")
        }
        val (connected, into) = startConnect(r, IpAddress.IPv4("127.0.0.1", port))
        val accepted = Slot[Connection]()
        assertEquals(run(r, backend.ops.accept(listener, accepted)), null)
        turnUntil(r)(connected.finished)
        assertEquals(connected.failure, null)
        val client = into.clear()
        val server = accepted.clear()
        try {
          assertEquals(backend.ops.peerAddress(client), local)
          assertEquals(backend.ops.localAddress(server), local)
          assertEquals(
            backend.ops.peerAddress(server),
            backend.ops.localAddress(client),
            "the client, as the server sees it"
          )
          backend.ops.localAddress(client) match {
            case IpAddress.IPv4("127.0.0.1", port) => assert(port > 0)
            case other                             => fail(s"a client's address: $other")
          }
        } finally {
          close(r, client)
          close(r, server)
        }
      } finally close(r, listener)
      intercept[IOException](backend.ops.localAddress(listener))
    }
  }

  test("an IPv6 listener on port 0 reports an IPv6 address") {
    withReactor { r =>
      val listener = listen(r, IpAddress.IPv6("::1", 0))
      try
        backend.ops.localAddress(listener) match {
          case IpAddress.IPv6("::1", port) => assert(port > 0)
          case other                       => fail(s"an IPv6 listener's address: $other")
        }
      finally close(r, listener)
    }
  }

  /** A connection to a listener opened with `listening`, the client opening with `connecting`: the client's and the
    * server's ends, closed after `body`.
    */
  private def connected(r: R, listening: SocketOptions, connecting: SocketOptions)(
      body: (Connection, Connection) => Unit
  )(using munit.Location): Unit = {
    val into = Slot[Listener]()
    val listener = opened(r, into, backend.ops.listen(IpAddress.IPv4("127.0.0.1", 0), listening, into))
    try {
      val address = backend.ops.localAddress(listener).asInstanceOf[IpAddress]
      val clientInto = Slot[Connection]()
      val connecting_ = Outcome()
      r.submit(backend.ops.connect(address, connecting, clientInto), connecting_)
      val accepted = Slot[Connection]()
      assertEquals(run(r, backend.ops.accept(listener, accepted)), null)
      turnUntil(r)(connecting_.finished)
      assertEquals(connecting_.failure, null)
      val client = clientInto.clear()
      val server = accepted.clear()
      try body(client, server)
      finally {
        close(r, client)
        close(r, server)
      }
    } finally close(r, listener)
  }

  test("a connection has NoDelay on at both ends unless it is set off, given to the client or to the listener") {
    assert(backend.ops.supports(SocketOption.NoDelay, SocketOption.Kind.Connection), "every backend has NoDelay")
    withReactor { r =>
      connected(r, SocketOptions.default, SocketOptions.default) { (client, server) =>
        assertEquals(backend.ops.option(client, SocketOption.NoDelay), true)
        assertEquals(backend.ops.option(server, SocketOption.NoDelay), true)
      }
      val off = SocketOptions.default.set(SocketOption.NoDelay, false)
      connected(r, off, off) { (client, server) =>
        assertEquals(backend.ops.option(client, SocketOption.NoDelay), false)
        assertEquals(backend.ops.option(server, SocketOption.NoDelay), false, "the listener's, on what it accepts")
      }
    }
  }

  test("keepalive given as a socket opens is the connection's, and the listener's is each accepted one's") {
    assume(
      backend.ops.supports(SocketOption.KeepAlive, SocketOption.Kind.Connection),
      "this backend has no keepalive option"
    )
    val on = SocketOption.KeepAlive.On(30, 5, 3)
    withReactor { r =>
      connected(r, SocketOptions.default.set(SocketOption.KeepAlive, on), SocketOptions.default) { (client, server) =>
        assertEquals(
          backend.ops.option(client, SocketOption.KeepAlive),
          SocketOption.KeepAlive.Off,
          "the system's, off"
        )
        assertEquals(backend.ops.option(server, SocketOption.KeepAlive), on)
      }
      connected(r, SocketOptions.default, SocketOptions.default.set(SocketOption.KeepAlive, on)) { (client, _) =>
        assertEquals(backend.ops.option(client, SocketOption.KeepAlive), on)
      }
    }
  }

  test("a buffer size given as a socket opens is a request the system meets at least") {
    for
      option <- List(SocketOption.ReceiveBuffer, SocketOption.SendBuffer)
      if backend.ops.supports(option, SocketOption.Kind.Connection)
    do
      withReactor { r =>
        connected(r, SocketOptions.default, SocketOptions.default.set(option, 65536)) { (client, _) =>
          val size = backend.ops.option(client, option)
          assert(size >= 65536, s"$option: $size")
        }
      }
  }

  test("an option that can change is changed on an open connection; one that cannot, or one missing, is refused") {
    withReactor { r =>
      connected(r, SocketOptions.default, SocketOptions.default) { (client, _) =>
        def check[V](option: SocketOption[V], value: V, readsBack: Boolean)(using munit.Location): Unit =
          if !backend.ops.supports(option, SocketOption.Kind.Connection) then {
            intercept[IllegalArgumentException](backend.ops.option(client, option))
            intercept[IllegalArgumentException](
              backend.ops.connect(IpAddress.IPv4("127.0.0.1", 9), SocketOptions.default.set(option, value), Slot())
            )
          } else if !backend.ops.canChange(option, SocketOption.Kind.Connection) then
            intercept[IllegalArgumentException](backend.ops.setOption(client, option, value))
          else {
            backend.ops.setOption(client, option, value)
            if readsBack then assertEquals(backend.ops.option(client, option), value, s"$option")
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
    withReactor { r =>
      val into = Slot[Listener]()
      val first = opened(r, into, backend.ops.listen(IpAddress.IPv4("127.0.0.1", 0), reuse, into))
      try {
        assertEquals(backend.ops.option(first, SocketOption.ReusePort), true)
        val address = backend.ops.localAddress(first).asInstanceOf[IpAddress]
        val secondInto = Slot[Listener]()
        val second = opened(r, secondInto, backend.ops.listen(address, reuse, secondInto))
        try {
          val refused = Slot[Listener]()
          assert(run(r, backend.ops.listen(address, refused)).isInstanceOf[java.io.IOException], "one without it")
        } finally close(r, second)
      } finally close(r, first)
    }
  }

  test("a stream socket takes no DontFragment, which is for datagram sockets") {
    assert(!backend.ops.supports(SocketOption.DontFragment, SocketOption.Kind.Connection))
    if backend.ops.supports(SocketOption.DontFragment, SocketOption.Kind.BoundDatagram) then
      intercept[IllegalArgumentException](
        backend.ops.connect(
          IpAddress.IPv4("127.0.0.1", 9),
          SocketOptions.default.set(SocketOption.DontFragment, true),
          Slot()
        )
      )
  }

  test("a connection's gathering write and scattering read carry a header and its body in order") {
    withReactor { r =>
      connected(r, SocketOptions.default, SocketOptions.default) { (client, server) =>
        val header = allocate(4)
        header.putInt(5).flip()
        assertEquals(run(r, backend.ops.write(client, Array(header, bytes("hello")))), null)
        val into = Array(allocate(4), allocate(5))
        while into(1).hasRemaining() do assertEquals(run(r, backend.ops.read(server, into)), null)
        assertEquals(into(0).getInt(0), 5)
        into(1).flip()
        assertEquals(StandardCharsets.UTF_8.decode(into(1)).toString, "hello")
      }
    }
  }

  test("accepts pending at once on a listener take turns, on any reactor, each its own connection") {
    withReactor { a =>
      withReactor { b =>
        val (listener, port) = onFreePort(port => listen(a, IpAddress.IPv4("127.0.0.1", port)))
        try {
          val accepts = List(a, b, a).map { r =>
            val into = Slot[Connection]()
            val outcome = Outcome()
            r.submit(backend.ops.accept(listener, into), outcome)
            r.runOnce(block = false)
            (outcome, into)
          }
          assert(accepts.forall(!_._1.finished), "all three wait")
          val clients = accepts.map(_ => startConnect(a, IpAddress.IPv4("127.0.0.1", port)))
          turnUntil(a, b)(accepts.forall(_._1.finished) && clients.forall(_._1.finished))
          for (outcome, _) <- accepts ++ clients do assertEquals(outcome.completed, 1, String.valueOf(outcome.failure))
          val accepted = accepts.map(_._2.clear())
          assertEquals(accepted.distinct.size, 3, "each accept took its own connection")
          for connection <- accepted ++ clients.map(_._2.clear()) do close(a, connection)
        } finally close(a, listener)
      }
    }
  }
}
