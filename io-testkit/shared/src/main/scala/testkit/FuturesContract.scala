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
import scala.concurrent.duration.FiniteDuration

import munit.FunSuite

/** What gears programs over any backend must do, checked through `perform` alone, so that the same program runs the
  * same on every backend and every way of carrying its futures: a backend's suite extends this with the backend and
  * `running`, which starts a program the platform's way (`Io.run`); NIO's does so over `NioOnCaller`, with no reactor,
  * and over `NioBackend` on a pool of one and of several workers, kqueue's on a pool of one and of several workers,
  * Darwin's likewise. The checks with stream sockets use Unix ones on a backend that has them, and TCP on the loopback
  * otherwise.
  */
trait FuturesContract extends ProgramSuite {

  test("a program waits on a timer, and run returns what the program returns") {
    val waited = running { io ?=>
      val started = System.nanoTime()
      io.perform(io.backend.ops.timer(20))
      System.nanoTime() - started
    }
    assert(waited >= 20_000_000L, s"waited only ${waited / 1000} µs")
  }

  test("two futures stream bytes through a pipe") {
    val total = 1 << 20
    val received = running { io ?=>
      val (in, out) = pipe(io)
      try {
        val writer = Future {
          val buf = arena.allocate(8192)
          val write = io.backend.ops.write(out, buf)
          var sent = 0
          while sent < total do {
            buf.clear().limit(math.min(8192, total - sent))
            while buf.hasRemaining do io.perform(write)
            sent += buf.limit()
          }
          io.perform(io.backend.ops.close(out))
        }
        val reader = Future {
          val buf = arena.allocate(8192)
          val read = io.backend.ops.read(in, buf)
          var received = 0
          var atEnd = false
          while !atEnd do {
            buf.clear()
            io.perform(read)
            if buf.position() == 0 then atEnd = true else received += buf.position()
          }
          received
        }
        writer.await
        reader.await
      } finally io.perform(io.backend.ops.close(in))
    }
    assertEquals(received, total)
  }

  test("clients and a server echo over stream sockets") {
    val dir = Files.createTempDirectory("gears")
    val clients = 16
    val trips = 200
    val echoed =
      try
        running { io ?=>
          val (listener, connect) = listenAndConnect(io, dir)
          try {
            val server = Future {
              val accepted = Slot[Connection]()
              val accept = io.backend.ops.accept(listener, accepted)
              val connections = (1 to clients).map { _ =>
                io.perform(accept)
                val socket = accepted.clear()
                Future {
                  val buf = arena.allocate(64)
                  val read = io.backend.ops.read(socket, buf)
                  val write = io.backend.ops.write(socket, buf)
                  try
                    for _ <- 1 to trips do {
                      buf.clear().limit(5)
                      while buf.hasRemaining do io.perform(read)
                      buf.flip()
                      while buf.hasRemaining do io.perform(write)
                    }
                  finally io.perform(io.backend.ops.close(socket))
                }
              }
              connections.awaitAll
            }
            val done = (1 to clients).map { _ =>
              Future {
                val socket = connect()
                try {
                  val buf = arena.allocate(64)
                  val read = io.backend.ops.read(socket, buf)
                  val write = io.backend.ops.write(socket, buf)
                  for _ <- 1 to trips do {
                    buf.clear().put("hello".getBytes(StandardCharsets.UTF_8)).flip()
                    while buf.hasRemaining do io.perform(write)
                    buf.clear().limit(5)
                    while buf.hasRemaining do io.perform(read)
                    assertEquals(text(buf), "hello")
                  }
                  1
                } finally io.perform(io.backend.ops.close(socket))
              }
            }
            val echoed = done.awaitAll.sum
            server.await
            echoed
          } finally io.perform(io.backend.ops.close(listener))
        }
      finally
        if Files.exists(dir) then {
          Files.list(dir).forEach(Files.deleteIfExists(_): Unit)
          Files.deleteIfExists(dir): Unit
        }
    assertEquals(echoed, clients)
  }

  test("an op performed with a slot not yet cleared fails with IllegalArgumentException, opening nothing") {
    val outcome = running { io ?=>
      val (in, out) = pipe(io)
      val read = Slot[Readable]()
      val write = Slot[Writable]()
      io.perform(io.backend.ops.pipe(read, write))
      val first = read.clear() // `write` still holds the first pipe's end
      val refused =
        try {
          io.perform(io.backend.ops.pipe(read, write))
          "performed"
        } catch case e: IllegalArgumentException => "refused"
      val nothingOpened =
        try {
          read.clear()
          false
        } catch case _: IllegalStateException => true
      for h <- List[Handle](in, out, first, write.clear()) do io.perform(io.backend.ops.close(h))
      s"$refused $nothingOpened"
    }
    assertEquals(outcome, "refused true")
  }

  test("a read performed while another future's read waits on the handle fails with IllegalArgumentException") {
    val outcome = running { io ?=>
      val (in, out) = pipe(io)
      try {
        val buf = arena.allocate(16)
        val waiting = Future(io.perform(io.backend.ops.read(in, buf)))
        io.perform(io.backend.ops.timer(20)) // so that the first read is pending
        val second =
          try {
            io.perform(io.backend.ops.read(in, arena.allocate(16)))
            "performed"
          } catch case _: IllegalArgumentException => "refused"
        io.perform(io.backend.ops.write(out, arena.of("first".getBytes(StandardCharsets.UTF_8))))
        waiting.await
        s"$second ${text(buf)}"
      } finally {
        io.perform(io.backend.ops.close(in))
        io.perform(io.backend.ops.close(out))
      }
    }
    assertEquals(outcome, "refused first")
  }

  test("a connect is performed only once, and a listener's port is free once its close is performed") {
    val port = freePort()
    val outcome = running { io ?=>
      val address = IpAddress.IPv4("127.0.0.1", port)
      val listening = Slot[Listener]()
      val listener = opening(io, listening, io.backend.ops.listen(address, listening))
      val accepted = Slot[Connection]()
      val accepting = Future(io.perform(io.backend.ops.accept(listener, accepted)))
      val into = Slot[Connection]()
      val connect = io.backend.ops.connect(address, into)
      io.perform(connect)
      val again =
        try {
          io.perform(connect)
          "performed"
        } catch case _: IllegalStateException => "refused"
      accepting.await
      io.perform(io.backend.ops.close(into.clear()))
      io.perform(io.backend.ops.close(accepted.clear()))
      val waiting = Future(io.perform(io.backend.ops.accept(listener, Slot[Connection]())))
      io.perform(io.backend.ops.timer(20)) // so that the accept is waiting on the listener
      io.perform(io.backend.ops.close(listener))
      waiting.awaitResult
      val relisten = Slot[Listener]()
      io.perform(io.backend.ops.close(opening(io, relisten, io.backend.ops.listen(address, relisten))))
      again
    }
    assertEquals(outcome, "refused")
  }

  test("a datagram socket refuses an empty packet when connected, and a receive with no room") {
    val port = freePort()
    val outcome = running { io ?=>
      val boundInto = Slot[BoundDatagram]()
      val server = opening(io, boundInto, io.backend.ops.bindDatagram(IpAddress.IPv4("127.0.0.1", port), boundInto))
      val clientInto = Slot[ConnectedDatagram]()
      val client =
        opening(io, clientInto, io.backend.ops.connectDatagram(null, IpAddress.IPv4("127.0.0.1", port), clientInto))
      try {
        def refused(op: io.Op): Boolean =
          try {
            io.perform(op)
            false
          } catch case _: IllegalArgumentException => true
        val empty = arena.allocate(4)
        empty.limit(0)
        val none = arena.allocate(4)
        none.limit(0)
        List(
          refused(io.backend.ops.send(client, empty)),
          refused(io.backend.ops.receive(server, none.slice(), Slot[IpAddress]()))
        ).mkString(" ")
      } finally {
        io.perform(io.backend.ops.close(client))
        io.perform(io.backend.ops.close(server))
      }
    }
    assertEquals(outcome, "true true")
  }

  test(
    "futures receiving on one datagram socket at once take turns, each its own packet, one cancelled leaving the queue"
  ) {
    val port = freePort()
    val outcome = running { io ?=>
      val boundInto = Slot[BoundDatagram]()
      val server = opening(io, boundInto, io.backend.ops.bindDatagram(IpAddress.IPv4("127.0.0.1", port), boundInto))
      val clientInto = Slot[ConnectedDatagram]()
      val client =
        opening(io, clientInto, io.backend.ops.connectDatagram(null, IpAddress.IPv4("127.0.0.1", port), clientInto))
      try {
        def receiving(): Future[String] = {
          val buf = arena.allocate(16)
          val receiver = Future {
            io.perform(io.backend.ops.receive(server, buf, Slot[IpAddress]()))
            StandardCharsets.UTF_8.decode(buf).toString
          }
          io.perform(io.backend.ops.timer(20)) // so that it waits, behind those before it
          receiver
        }
        val first = receiving()
        val leaving = receiving()
        val second = receiving()
        leaving.cancel()
        leaving.awaitResult
        for packet <- List("one", "two") do
          io.perform(io.backend.ops.send(client, arena.of(packet.getBytes(StandardCharsets.UTF_8))))
        s"${first.await} ${second.await}"
      } finally {
        io.perform(io.backend.ops.close(client))
        io.perform(io.backend.ops.close(server))
      }
    }
    assertEquals(outcome, "one two")
  }

  test("futures sending on one datagram socket at once each send every packet, whole") {
    val port = freePort()
    val outcome = running { io ?=>
      val boundInto = Slot[BoundDatagram]()
      val server = opening(io, boundInto, io.backend.ops.bindDatagram(IpAddress.IPv4("127.0.0.1", port), boundInto))
      val clientInto = Slot[ConnectedDatagram]()
      val client =
        opening(io, clientInto, io.backend.ops.connectDatagram(null, IpAddress.IPv4("127.0.0.1", port), clientInto))
      try {
        val senders = 4
        val each = 25
        val received = Future {
          val buf = arena.allocate(32)
          val from = Slot[IpAddress]()
          val receive = io.backend.ops.receive(server, buf, from)
          (1 to senders * each).map { _ =>
            buf.clear()
            io.perform(receive)
            from.clear()
            StandardCharsets.UTF_8.decode(buf).toString
          }
        }
        val sending = (1 to senders).map { s =>
          Future {
            val buf = arena.allocate(32)
            for i <- 1 to each do {
              buf.clear()
              buf.put(s"sender $s packet $i".getBytes(StandardCharsets.UTF_8)).flip()
              io.perform(io.backend.ops.send(client, buf))
            }
          }
        }
        sending.awaitAll
        val packets = received.await
        val expected = for s <- 1 to senders; i <- 1 to each yield s"sender $s packet $i"
        s"${packets.size} ${packets.sorted == expected.sorted}"
      } finally {
        io.perform(io.backend.ops.close(client))
        io.perform(io.backend.ops.close(server))
      }
    }
    assertEquals(outcome, "100 true")
  }

  test("futures accepting on one listener at once each take their own connection") {
    val port = freePort()
    val outcome = running { io ?=>
      val address = IpAddress.IPv4("127.0.0.1", port)
      val listening = Slot[Listener]()
      val listener = opening(io, listening, io.backend.ops.listen(address, listening))
      try {
        val accepting = (1 to 3).map { _ =>
          val into = Slot[Connection]()
          val accepter = Future {
            io.perform(io.backend.ops.accept(listener, into))
            into.clear()
          }
          io.perform(io.backend.ops.timer(20)) // so that it waits, behind those before it
          accepter
        }
        val clients = (1 to 3).map { _ =>
          val into = Slot[Connection]()
          io.perform(io.backend.ops.connect(address, into))
          into.clear()
        }
        val accepted = accepting.awaitAll
        for connection <- accepted ++ clients do io.perform(io.backend.ops.close(connection))
        accepted.distinct.size
      } finally io.perform(io.backend.ops.close(listener))
    }
    assertEquals(outcome, 3)
  }

  test("cancelling a future cancels the op it waits on, which may then be performed again") {
    val outcome = running { io ?=>
      val (in, out) = pipe(io)
      try {
        val buf = arena.allocate(16)
        val read = io.backend.ops.read(in, buf)
        val waiting = Future(io.perform(read))
        io.perform(io.backend.ops.timer(20)) // so that the read is pending
        waiting.cancel()
        val cancelled = waiting.awaitResult.failed.toOption.exists(_.isInstanceOf[CancellationException])
        io.perform(io.backend.ops.write(out, arena.of("again".getBytes(StandardCharsets.UTF_8))))
        io.perform(read)
        s"$cancelled ${text(buf)}"
      } finally {
        io.perform(io.backend.ops.close(in))
        io.perform(io.backend.ops.close(out))
      }
    }
    assertEquals(outcome, "true again")
  }

  test("a read into a buffer with no room fails at once, reading nothing, on a pipe and on a stream socket") {

    /** Reads into a full buffer before and after `send` writes, then reads what was sent; the time limits turn a read
      * that never returns into a failure.
      */
    def check(io: Io[backend.type], in: Readable, send: String => Unit)(using Async): String = {
      val full = arena.allocate(4)
      full.position(full.limit())
      val read = io.backend.ops.read(in, full)
      def refused(): Boolean =
        try {
          io.perform(read, FiniteDuration(2_000, "ms"))
          false
        } catch case _: IllegalArgumentException => true
      val before = refused()
      send("hello")
      val after = refused()
      val buf = arena.allocate(16)
      val readSome = io.backend.ops.read(in, buf)
      while buf.position() < 5 do io.perform(readSome, FiniteDuration(2_000, "ms"))
      s"$before $after ${full.position() == full.limit()} ${text(buf)}"
    }
    def writeAll(io: Io[backend.type], out: Writable)(message: String)(using Async): Unit = {
      val buf = arena.of(message.getBytes(StandardCharsets.UTF_8))
      val write = io.backend.ops.write(out, buf)
      while buf.hasRemaining do io.perform(write)
    }
    val outcomes = withTempDir { dir =>
      running { io ?=>
        val onPipe = {
          val (in, out) = pipe(io)
          try check(io, in, writeAll(io, out))
          finally {
            io.perform(io.backend.ops.close(in))
            io.perform(io.backend.ops.close(out))
          }
        }
        val onSocket = {
          val (listener, connect) = listenAndConnect(io, dir)
          try {
            val accepted = Slot[Connection]()
            val accepting = Future(io.perform(io.backend.ops.accept(listener, accepted)))
            val client = connect()
            accepting.await
            val server = accepted.clear()
            try check(io, client, writeAll(io, server))
            finally {
              io.perform(io.backend.ops.close(client))
              io.perform(io.backend.ops.close(server))
            }
          } finally io.perform(io.backend.ops.close(listener))
        }
        (onPipe, onSocket)
      }
    }
    assertEquals(outcomes, ("true true true hello", "true true true hello"))
  }

  test("performing a read with a time limit throws TimeoutException, and the read may then be performed again") {
    val outcome = running { io ?=>
      val (in, out) = pipe(io)
      try {
        val buf = arena.allocate(16)
        val read = io.backend.ops.read(in, buf)
        val started = System.nanoTime()
        val timedOut =
          try {
            io.perform(read, FiniteDuration(20, "ms"))
            false
          } catch case _: TimeoutException => true
        val waited = System.nanoTime() - started
        io.perform(io.backend.ops.write(out, arena.of("in time".getBytes(StandardCharsets.UTF_8))))
        io.perform(read, FiniteDuration(5_000, "ms"))
        s"$timedOut ${waited >= 20_000_000L} ${buf.position()} ${text(buf)}"
      } finally {
        io.perform(io.backend.ops.close(in))
        io.perform(io.backend.ops.close(out))
      }
    }
    assertEquals(outcome, "true true 7 in time")
  }

  test("a blocking op past its time limit is interrupted, and perform throws TimeoutException") {
    val outcome = running { io ?=>
      io.backend match {
        case b: (gears.async.io.Blocking & io.backend.type) =>
          val returned = new java.util.concurrent.atomic.AtomicBoolean
          val op = b.ops.blocking { () =>
            try Thread.sleep(10_000)
            catch case _: InterruptedException => ()
            returned.set(true)
          }
          val started = System.nanoTime()
          val timedOut =
            try {
              io.perform(op, FiniteDuration(20, "ms"))
              false
            } catch case _: TimeoutException => true
          val waited = System.nanoTime() - started
          s"$timedOut ${returned.get()} ${waited >= 20_000_000L && waited < 5_000_000_000L}"
        case _ => "true true true" // no blocking ops to check
      }
    }
    assertEquals(outcome, "true true true")
  }

  test("a timer with a shorter time limit throws TimeoutException from perform") {
    val outcome = running { io ?=>
      val started = System.nanoTime()
      val timedOut =
        try {
          io.perform(io.backend.ops.timer(60_000), FiniteDuration(20, "ms"))
          false
        } catch case _: TimeoutException => true
      val waited = System.nanoTime() - started
      s"$timedOut ${waited >= 20_000_000L && waited < 30_000_000_000L}"
    }
    assertEquals(outcome, "true true")
  }

  test("a timer with a time limit no shorter than its time completes") {
    val outcome = running { io ?=>
      val completed =
        for limit <- List(20, 21, 1000)
        yield
          try {
            io.perform(io.backend.ops.timer(20), FiniteDuration(limit.toLong, "ms"))
            true
          } catch case _: TimeoutException => false
      completed.mkString(" ")
    }
    assertEquals(outcome, "true true true")
  }

  test("gears' sleep and withTimeout run on the backend's timers: the program's Io is its AsyncOperations") {
    val outcome = running { io ?=>
      val (in, out) = pipe(io)
      try {
        val started = System.nanoTime()
        sleep(20)
        val slept = System.nanoTime() - started
        val buf = arena.allocate(16)
        val timedOut =
          try {
            withTimeout(20.millis)(io.perform(io.backend.ops.read(in, buf)))
            false
          } catch case _: TimeoutException => true
        s"${slept >= 20_000_000L} $timedOut ${buf.position()}"
      } finally {
        io.perform(io.backend.ops.close(in))
        io.perform(io.backend.ops.close(out))
      }
    }
    assertEquals(outcome, "true true 0")
  }

  test("a cancelled timer stops waiting, and may be performed again") {
    val outcome = running { io ?=>
      val timer = io.backend.ops.timer(60_000)
      val sleeping = Future(io.perform(timer))
      io.perform(io.backend.ops.timer(20)) // so that the long timer is waiting
      val started = System.nanoTime()
      sleeping.cancel()
      val cancelled = sleeping.awaitResult.failed.toOption.exists(_.isInstanceOf[CancellationException])
      val waited = System.nanoTime() - started
      io.perform(io.backend.ops.timer(1)) // the op again, now for a moment
      s"$cancelled ${waited < 1_000_000_000L}"
    }
    assertEquals(outcome, "true true")
  }

  test("a future waiting on a deadline timer is released when another moves its deadline earlier, with no cancel") {
    val outcome = running { io ?=>
      val timer = io.backend.ops.deadlineTimer(System.nanoTime() + 60_000_000_000L)
      val waiting = Future(io.perform(timer))
      io.perform(io.backend.ops.timer(20)) // so that the timer is waiting
      val started = System.nanoTime()
      timer.moveTo(System.nanoTime() + 1_000_000L)
      val completed = waiting.awaitResult.isSuccess
      val waited = System.nanoTime() - started
      timer.moveTo(System.nanoTime() + 2_000_000L) // and again, later than now
      io.perform(timer)
      s"$completed ${waited < 10_000_000_000L}"
    }
    assertEquals(outcome, "true true")
  }

  test("a read waiting on a socket is cancelled with the connection left open") {
    val outcome = withTempDir { dir =>
      running { io ?=>
        val (listener, connect) = listenAndConnect(io, dir)
        val client = connect()
        try {
          val accepted = Slot[Connection]()
          io.perform(io.backend.ops.accept(listener, accepted))
          val server = accepted.clear()
          try {
            val buf = arena.allocate(16)
            val read = io.backend.ops.read(client, buf)
            val waiting = Future(io.perform(read))
            io.perform(io.backend.ops.timer(20))
            waiting.cancel()
            val cancelled = waiting.awaitResult.failed.toOption.exists(_.isInstanceOf[CancellationException])
            io.perform(io.backend.ops.write(server, arena.of("still".getBytes(StandardCharsets.UTF_8))))
            io.perform(read)
            s"$cancelled ${text(buf)}"
          } finally io.perform(io.backend.ops.close(server))
        } finally {
          io.perform(io.backend.ops.close(client))
          io.perform(io.backend.ops.close(listener))
        }
      }
    }
    assertEquals(outcome, "true still")
  }

  test("an op waiting on a handle that is closed fails") {
    val failure = running { io ?=>
      val (in, out) = pipe(io)
      try {
        val waiting = Future(io.perform(io.backend.ops.read(in, arena.allocate(4))))
        io.perform(io.backend.ops.timer(20))
        io.perform(io.backend.ops.close(in))
        waiting.awaitResult.failed.toOption.orNull
      } finally io.perform(io.backend.ops.close(out))
    }
    assert(failure.isInstanceOf[java.nio.channels.ClosedChannelException], String.valueOf(failure))
  }

  test("a connection refused after waiting for it fails perform") {
    val port = freePort() // nothing listens on it: one just given up
    val failure = running { io ?=>
      val into = Slot[Connection]()
      try {
        io.perform(io.backend.ops.connect(IpAddress.IPv4("127.0.0.1", port), into))
        io.perform(io.backend.ops.close(into.clear()))
        null
      } catch {
        case t: Throwable =>
          intercept[IllegalStateException](into.clear()) // a failed connect leaves its slot empty
          t
      }
    }
    assert(failure.isInstanceOf[IOException], String.valueOf(failure))
  }

  test("an op that is not Repeatable is performed only once") {
    val failure = running { io ?=>
      val into = Slot[List[ResolvedAddress]]()
      val resolve = io.backend.ops.resolve("localhost", into)
      io.perform(resolve)
      into.clear()
      try {
        io.perform(resolve)
        null
      } catch case t: Throwable => t
    }
    assert(failure.isInstanceOf[IllegalStateException], String.valueOf(failure))
  }

  test("an op that fails fails perform") {
    val failure = running { io ?=>
      val (in, out) = pipe(io)
      io.perform(io.backend.ops.close(in))
      io.perform(io.backend.ops.close(out))
      try {
        io.perform(io.backend.ops.read(in, arena.allocate(4)))
        null
      } catch case t: Throwable => t
    }
    assert(failure.isInstanceOf[IOException], String.valueOf(failure))
  }

  test("an op a future performs is cancelled when the group the future was made in ends, which waits for it") {
    val outcome = running { io ?=>
      var waiting: Future[Unit] | Null = null
      val started = System.nanoTime()
      Async.group {
        waiting = Future(io.perform(io.backend.ops.timer(60_000)))
      } // ends with the timer pending: the future is cancelled, and with it the op, before the group returns
      val waited = System.nanoTime() - started
      val cancelled = waiting.nn.awaitResult.failed.toOption.exists(_.isInstanceOf[CancellationException])
      s"$cancelled ${waited < 1_000_000_000L}"
    }
    assertEquals(outcome, "true true")
  }

  test("an op a future performs races in select, and cancelling the future stops it, the stream left usable") {
    val outcome = running { io ?=>
      val (in, out) = pipe(io)
      try {
        val buf = arena.allocate(16)
        val read = Future(io.perform(io.backend.ops.read(in, buf))) // pending: nothing is written
        val timer = Future(io.perform(io.backend.ops.timer(20)))
        val first = Async.select(timer.handle(_ => "timer"), read.handle(_ => "read"))
        read.cancel()
        val cancelled = read.awaitResult.failed.toOption.exists(_.isInstanceOf[CancellationException])
        // The cancelled read took nothing: what is written now is read in full.
        io.perform(io.backend.ops.write(out, arena.of("after".getBytes(StandardCharsets.UTF_8))))
        val again = arena.allocate(16)
        io.perform(io.backend.ops.read(in, again))
        s"$first $cancelled ${text(again)}"
      } finally {
        io.perform(io.backend.ops.close(in))
        io.perform(io.backend.ops.close(out))
      }
    }
    assertEquals(outcome, "timer true after")
  }

  test("a failure of the program is rethrown") {
    intercept[IllegalStateException](running(throw new IllegalStateException("boom")))
  }
}
