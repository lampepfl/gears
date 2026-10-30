package gears.async.io
package testkit

import gears.async.Async
import gears.async.Future

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** What a backend with Unix stream sockets must do, with or without IP sockets. */
trait UnixStreamContract extends ContractSuite {
  val backend: Backend & UnixStreams

  /** A listener on a Unix socket path, listening, from the `listen` op, whose failure is thrown. */
  private def unixListen(io: Io[backend.type], address: UnixAddress)(using Async): backend.Listener = {
    val into = Slot[backend.Listener]()
    opening(io, into, io.backend.ops.listen(address, into))
  }

  /** A connection to a Unix socket path, from the `connect` op, whose failure is thrown. */
  private def unixConnect(io: Io[backend.type], address: UnixAddress)(using Async): backend.Connection = {
    val into = Slot[backend.Connection]()
    opening(io, into, io.backend.ops.connect(address, into))
  }

  /** A connection to `listener`, at `address`: its client's end and the one `listener` accepted. */
  private def unixPair(io: Io[backend.type], listener: backend.Listener, address: UnixAddress)(using
      Async.Spawn
  ): (backend.Connection, backend.Connection) = {
    val accepted = Slot[backend.Connection]()
    val accepting = Future(opening(io, accepted, io.backend.ops.accept(listener, accepted)))
    val client = unixConnect(io, address)
    (client, accepting.await)
  }

  /** Performs `read`, into `buf`, until it completes adding nothing, the end of the stream: what `buf` then holds. */
  private def unixReadToEnd(io: Io[backend.type], read: io.Op, buf: ByteBuffer)(using Async): String = {
    var atEnd = false
    while !atEnd do {
      val before = buf.position()
      io.perform(read)
      atEnd = buf.position() == before
    }
    text(buf)
  }

  test("a socket's reads end after the bytes sent before its peer closed") {
    withTempDir { dir =>
      running { io ?=>
        val address = UnixAddress(dir.resolve("end.sock").toString)
        val listener = unixListen(io, address)
        try {
          val (client, server) = unixPair(io, listener, address)
          try {
            io.perform(io.backend.ops.write(client, bytes("last words")))
            close(io, client)
            val buf = allocate(64)
            assertEquals(unixReadToEnd(io, io.backend.ops.read(server, buf), buf), "last words")
          } finally close(io, server)
        } finally close(io, listener)
      }
    }
  }

  test("a Unix stream socket accepts, connects and echoes") {
    withTempDir { dir =>
      running { io ?=>
        val address = UnixAddress(dir.resolve("echo.sock").toString)
        val listener = unixListen(io, address)
        try {
          val (client, server) = unixPair(io, listener, address)
          try {
            io.perform(io.backend.ops.write(client, bytes("ping")))
            val serverBuf = allocate(16)
            io.perform(io.backend.ops.read(server, serverBuf))
            assertEquals(text(serverBuf), "ping")
            serverBuf.flip()
            io.perform(io.backend.ops.write(server, serverBuf))
            val clientBuf = allocate(16)
            io.perform(io.backend.ops.read(client, clientBuf))
            assertEquals(text(clientBuf), "ping")
          } finally {
            close(io, server)
            close(io, client)
          }
        } finally close(io, listener)
      }
    }
  }

  test("closing a Unix listener leaves the socket file of a listener that took its path since") {
    withTempDir { dir =>
      running { io ?=>
        val path = UnixAddress(dir.resolve("taken.sock").toString)
        val first = unixListen(io, path)
        val second = unixListen(io, path) // replaces the first's socket file
        try {
          close(io, first)
          val into = Slot[backend.Connection]()
          val failure = attempt(io, io.backend.ops.connect(path, into))
          assertEquals(failure, null, s"the second listener's file was removed: $failure")
          close(io, into.clear())
        } finally close(io, second)
      }
    }
  }

  test("listening on a path that holds a file other than a socket fails, leaving the file") {
    withTempDir { dir =>
      running { io ?=>
        val path = dir.resolve("data.txt")
        Files.write(path, "data".getBytes(StandardCharsets.UTF_8))
        val into = Slot[backend.Listener]()
        val failure = attempt(io, io.backend.ops.listen(UnixAddress(path.toString), into))
        assert(failure.isInstanceOf[java.io.IOException], String.valueOf(failure))
        assertEquals(Files.readString(path), "data")
      }
    }
  }

  test("an empty Unix path, or one with a NUL, is refused as the op is built") {
    for path <- List("", "a\u0000b") do {
      intercept[IllegalArgumentException](backend.ops.listen(UnixAddress(path), Slot[backend.Listener]()))
      intercept[IllegalArgumentException](backend.ops.connect(UnixAddress(path), Slot[backend.Connection]()))
    }
  }

  test("closing a Unix listener removes its socket file") {
    withTempDir { dir =>
      running { io ?=>
        val path = dir.resolve("gone.sock")
        val listener = unixListen(io, UnixAddress(path.toString))
        assert(Files.exists(path))
        close(io, listener)
        assert(!Files.exists(path))
      }
    }
  }

  test("Unix stream sockets know their paths: the listener's, the client's peer, and an unnamed client's empty one") {
    withTempDir { dir =>
      running { io ?=>
        val address = UnixAddress(dir.resolve("named.sock").toString)
        val listener = unixListen(io, address)
        try {
          assertEquals(io.backend.ops.localAddress(listener), address)
          val (client, server) = unixPair(io, listener, address)
          try {
            assertEquals(io.backend.ops.peerAddress(client), address)
            assertEquals(io.backend.ops.localAddress(client), UnixAddress(""), "a client with no path of its own")
            assertEquals(io.backend.ops.peerAddress(server), UnixAddress(""))
            assertEquals(io.backend.ops.localAddress(server), address)
          } finally {
            close(io, client)
            close(io, server)
          }
        } finally close(io, listener)
      }
    }
  }

  test("a Unix stream socket takes the buffer sizes where the backend has them, and refuses TCP's options") {
    import SocketOption.Kind.{UnixConnection, UnixListener}
    for option <- List(SocketOption.NoDelay, SocketOption.KeepAlive, SocketOption.DontFragment, SocketOption.ReusePort)
    do {
      assert(!backend.ops.supports(option, UnixConnection) && !backend.ops.supports(option, UnixListener), s"$option")
      assert(!backend.ops.canChange(option, UnixConnection), s"$option")
    }
    intercept[IllegalArgumentException](
      backend.ops.connect(UnixAddress("/nowhere.sock"), SocketOptions.default.set(SocketOption.NoDelay, true), Slot())
    )
    intercept[IllegalArgumentException](
      backend.ops.listen(UnixAddress("/nowhere.sock"), SocketOptions.default.set(SocketOption.NoDelay, true), Slot())
    )
    assume(
      backend.ops.supports(SocketOption.ReceiveBuffer, UnixListener) &&
        backend.ops.supports(SocketOption.SendBuffer, UnixListener) &&
        backend.ops.supports(SocketOption.ReceiveBuffer, UnixConnection),
      "this backend has no buffer sizes for Unix sockets"
    )
    withTempDir { dir =>
      running { io ?=>
        val address = UnixAddress(dir.resolve("buffers.sock").toString)
        val listening = Slot[backend.Listener]()
        val listenerOptions =
          SocketOptions.default.set(SocketOption.ReceiveBuffer, 65536).set(SocketOption.SendBuffer, 65536)
        val listener = opening(io, listening, io.backend.ops.listen(address, listenerOptions, listening))
        try {
          val accepted = Slot[backend.Connection]()
          val accepting = Future(opening(io, accepted, io.backend.ops.accept(listener, accepted)))
          val into = Slot[backend.Connection]()
          val client = opening(
            io,
            into,
            io.backend.ops.connect(address, SocketOptions.default.set(SocketOption.ReceiveBuffer, 65536), into)
          )
          val server = accepting.await
          try {
            assert(io.backend.ops.option(client, SocketOption.ReceiveBuffer) >= 65536, "the client's receive buffer")
            assert(
              io.backend.ops.option(server, SocketOption.SendBuffer) >= 65536,
              "the listener's, on what it accepts"
            )
            intercept[IllegalArgumentException](io.backend.ops.option(client, SocketOption.NoDelay))
            if io.backend.ops.canChange(SocketOption.SendBuffer, UnixConnection) then {
              io.backend.ops.setOption(client, SocketOption.SendBuffer, 131072)
              assert(io.backend.ops.option(client, SocketOption.SendBuffer) >= 131072, "changed")
            } else
              intercept[IllegalArgumentException](io.backend.ops.setOption(client, SocketOption.SendBuffer, 131072))
          } finally {
            close(io, client)
            close(io, server)
          }
        } finally close(io, listener)
      }
    }
  }
}
