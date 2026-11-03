package gears.async.io
package testkit

import gears.async.Async
import gears.async.Future

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** What a backend with Unix datagram sockets must do, with or without IP sockets: checked through `perform`, as
  * programs over the backend (see `ContractSuite`).
  */
trait UnixDatagramContract extends ContractSuite {
  val backend: Backend & UnixDatagrams

  /** A Unix datagram socket bound to `local`, from the `bindDatagram` op, whose failure is thrown. */
  protected def bindUnixDatagram(io: Io[backend.type], local: UnixAddress)(using Async): backend.UnixBoundDatagram = {
    val into = Slot[backend.UnixBoundDatagram]()
    opening(io, into, backend.ops.bindDatagram(local, into))
  }

  /** A Unix datagram socket connected to `remote`, from the `connectDatagram` op, whose failure is thrown. */
  protected def connectUnixDatagram(io: Io[backend.type], local: UnixAddress | Null, remote: UnixAddress)(using
      Async
  ): backend.UnixConnectedDatagram = {
    val into = Slot[backend.UnixConnectedDatagram]()
    opening(io, into, backend.ops.connectDatagram(local, remote, into))
  }

  /** The packet in `buf`, received and flipped, as text. */
  private def packet(buf: java.nio.ByteBuffer): String = StandardCharsets.UTF_8.decode(buf).toString

  test(
    "a Unix datagram goes to a bound socket, which learns its sender's path, and its reply to the connected client"
  ) {
    withTempDir { dir =>
      val serverPath = dir.resolve("server.sock").toString
      val clientPath = dir.resolve("client.sock").toString
      running { io ?=>
        val server = bindUnixDatagram(io, UnixAddress(serverPath))
        try {
          val client = connectUnixDatagram(io, UnixAddress(clientPath), UnixAddress(serverPath))
          try {
            val buf = allocate(64)
            val from = Slot[UnixAddress]()
            val received = Future(io.perform(backend.ops.receive(server, buf, from)))
            io.perform(backend.ops.send(client, bytes("unix datagram")))
            received.await
            assertEquals(packet(buf), "unix datagram")
            val sender = from.clear()
            assertEquals(sender, UnixAddress(clientPath))
            io.perform(backend.ops.send(server, bytes("reply"), sender))
            val reply = allocate(64)
            io.perform(backend.ops.receive(client, reply))
            assertEquals(packet(reply), "reply")
          } finally close(io, client)
        } finally close(io, server)
      }
      assert(!Files.exists(java.nio.file.Path.of(serverPath)), "closing a bound datagram socket removes its path")
    }
  }

  test("Unix datagrams follow the IP rules: an unnamed sender, and no empty packet on a connected socket") {
    withTempDir { dir =>
      running { io ?=>
        val serverPath = UnixAddress(dir.resolve("server.sock").toString)
        val server = bindUnixDatagram(io, serverPath)
        try {
          val unnamed = connectUnixDatagram(io, null, serverPath) // with no path of its own
          try {
            val empty = allocate(4)
            empty.limit(0)
            val failure = attempt(io, backend.ops.send(unnamed, empty))
            assert(failure.isInstanceOf[IllegalArgumentException], String.valueOf(failure))
            io.perform(backend.ops.send(unnamed, bytes("anonymous")))
            val buf = allocate(64)
            val from = Slot[UnixAddress]()
            io.perform(backend.ops.receive(server, buf, from))
            assertEquals(packet(buf), "anonymous")
            assertEquals(from.clear(), UnixAddress(""), "an unnamed sender has the empty path")
            intercept[IllegalArgumentException](backend.ops.send(server, bytes("x"), UnixAddress("")))
          } finally close(io, unnamed)
        } finally close(io, server)
      }
    }
  }

  test("Unix datagram sockets know their paths: a bound one its own, a connected one its peer and its own") {
    withTempDir { dir =>
      running { io ?=>
        val serverPath = UnixAddress(dir.resolve("bound.sock").toString)
        val server = bindUnixDatagram(io, serverPath)
        try {
          assertEquals(backend.ops.localAddress(server), serverPath)
          val clientPath = UnixAddress(dir.resolve("client.sock").toString)
          val named = connectUnixDatagram(io, clientPath, serverPath)
          val unnamed = connectUnixDatagram(io, null, serverPath)
          try {
            assertEquals(backend.ops.peerAddress(named), serverPath)
            assertEquals(backend.ops.localAddress(named), clientPath)
            assertEquals(backend.ops.localAddress(unnamed), UnixAddress(""))
          } finally {
            close(io, named)
            close(io, unnamed)
          }
        } finally close(io, server)
      }
    }
  }

  test(
    "a Unix datagram sent from several buffers is one packet, from a bound socket or a connected one, never empty there"
  ) {
    withTempDir { dir =>
      running { io ?=>
        val to = UnixAddress(dir.resolve("receiver.sock").toString)
        val receiver = bindUnixDatagram(io, to)
        val bound = bindUnixDatagram(io, UnixAddress(dir.resolve("bound.sock").toString))
        val connected = connectUnixDatagram(io, null, to)
        try {
          def received()(using munit.Location): String = {
            val buf = allocate(64)
            io.perform(backend.ops.receive(receiver, buf, Slot[UnixAddress]()))
            packet(buf)
          }
          io.perform(backend.ops.send(bound, Array(bytes("one "), bytes("packet")), to))
          assertEquals(received(), "one packet")
          io.perform(backend.ops.send(connected, Array(bytes("two "), bytes(""), bytes("parts"))))
          assertEquals(received(), "two parts")
          val empty = allocate(1)
          empty.limit(0)
          val failure = attempt(io, backend.ops.send(connected, Array(empty, empty.duplicate())))
          assert(failure.isInstanceOf[IllegalArgumentException], String.valueOf(failure))
        } finally {
          close(io, receiver)
          close(io, bound)
          close(io, connected)
        }
      }
    }
  }

  test("a Unix datagram socket takes the buffer sizes where the backend has them, and refuses IP's options") {
    import SocketOption.Kind.{UnixBoundDatagram, UnixConnectedDatagram}
    for option <- List(SocketOption.NoDelay, SocketOption.KeepAlive, SocketOption.DontFragment, SocketOption.ReusePort)
    do assert(!backend.ops.supports(option, UnixBoundDatagram), s"$option")
    intercept[IllegalArgumentException](
      backend.ops.bindDatagram(
        UnixAddress("/nowhere.sock"),
        SocketOptions.default.set(SocketOption.DontFragment, true),
        Slot[backend.UnixBoundDatagram]()
      )
    )
    assume(
      backend.ops.supports(SocketOption.ReceiveBuffer, UnixBoundDatagram) &&
        backend.ops.supports(SocketOption.SendBuffer, UnixConnectedDatagram),
      "this backend has no buffer sizes for Unix datagram sockets"
    )
    withTempDir { dir =>
      running { io ?=>
        val path = UnixAddress(dir.resolve("buffers.sock").toString)
        val boundInto = Slot[backend.UnixBoundDatagram]()
        val bound = opening(
          io,
          boundInto,
          backend.ops.bindDatagram(path, SocketOptions.default.set(SocketOption.ReceiveBuffer, 65536), boundInto)
        )
        val connectedInto = Slot[backend.UnixConnectedDatagram]()
        val connected = opening(
          io,
          connectedInto,
          backend.ops
            .connectDatagram(null, path, SocketOptions.default.set(SocketOption.SendBuffer, 65536), connectedInto)
        )
        try {
          assert(backend.ops.option(bound, SocketOption.ReceiveBuffer) >= 65536)
          assert(backend.ops.option(connected, SocketOption.SendBuffer) >= 65536)
          intercept[IllegalArgumentException](backend.ops.option(bound, SocketOption.DontFragment))
        } finally {
          close(io, connected)
          close(io, bound)
        }
      }
    }
  }
}
