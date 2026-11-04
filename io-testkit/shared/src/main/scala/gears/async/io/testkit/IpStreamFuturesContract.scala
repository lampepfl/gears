package gears.async.io
package testkit

import gears.async.Future
import gears.async.Future.awaitAll

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException

/** Concurrent stream-socket programs requiring only IpStreams. */
trait IpStreamFuturesContract extends IpStreamFixture {

  test("clients and a server echo over stream sockets") {
    val clients = 16
    val trips = 200
    val echoed =
      running { io ?=>
        val (listener, connect) = listenAndConnect(io)
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
    assertEquals(echoed, clients)
  }

  test("a connect is performed only once, and a listener's port is free once its close is performed") {
    val outcome = running { io ?=>
      val listening = Slot[Listener]()
      val listener =
        opening(io, listening, io.backend.ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), listening))
      val address = io.backend.ops.localAddress(listener).asInstanceOf[IpSocketAddress]
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

  test("futures accepting on one listener at once each take their own connection") {
    val outcome = running { io ?=>
      val listening = Slot[Listener]()
      val listener =
        opening(io, listening, io.backend.ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), listening))
      val address = io.backend.ops.localAddress(listener).asInstanceOf[IpSocketAddress]
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

  test("a read waiting on a socket is cancelled with the connection left open") {
    val outcome =
      running { io ?=>
        val (listener, connect) = listenAndConnect(io)
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
    assertEquals(outcome, "true still")
  }

  test("a connection refused after waiting for it fails perform") {
    val failure = running { io ?=>
      val (listener, _) = listenAndConnect(io)
      val address = io.backend.ops.localAddress(listener).asInstanceOf[IpSocketAddress]
      close(io, listener) // connecting to the address just given up must fail
      val into = Slot[Connection]()
      try {
        io.perform(io.backend.ops.connect(address, into))
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
}
