package gears.async.io
package testkit

import gears.async.io.internal.ReactorBackend

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** What a backend with Unix datagram sockets must do (see `ReactorContract`), with or without IP sockets. */
trait UnixDatagramContract extends ReactorContract {
  val backend: ReactorBackend & Blocking & Pipes & UnixDatagrams

  /** A Unix datagram socket bound to `local`, from the `bindDatagram` op, whose failure is thrown. */
  protected def bindUnixDatagram(r: R, local: UnixAddress)(using munit.Location): UnixBoundDatagram = {
    val into = Slot[UnixBoundDatagram]()
    opened(r, into, backend.ops.bindDatagram(local, into))
  }

  /** A Unix datagram socket connected to `remote`, from the `connectDatagram` op, whose failure is thrown. */
  protected def connectUnixDatagram(r: R, local: UnixAddress | Null, remote: UnixAddress)(using
      munit.Location
  ): UnixConnectedDatagram = {
    val into = Slot[UnixConnectedDatagram]()
    opened(r, into, backend.ops.connectDatagram(local, remote, into))
  }

  test(
    "a Unix datagram goes to a bound socket, which learns its sender's path, and its reply to the connected client"
  ) {
    val dir = tempDir()
    try
      withReactor { r =>
        val serverPath = dir.resolve("server.sock").toString
        val clientPath = dir.resolve("client.sock").toString
        val server = bindUnixDatagram(r, UnixAddress(serverPath))
        try {
          val client = connectUnixDatagram(r, UnixAddress(clientPath), UnixAddress(serverPath))
          try {
            val buf = allocate(64)
            val from = Slot[UnixAddress]()
            val received = Outcome()
            r.submit(backend.ops.receive(server, buf, from), received)
            val sent = Outcome()
            r.submit(backend.ops.send(client, bytes("unix datagram")), sent)
            turnUntil(r)(received.finished && sent.finished)
            assertEquals(received.completed, 1, String.valueOf(received.failure))
            val copy = new Array[Byte](buf.remaining())
            buf.get(copy)
            assertEquals(new String(copy, StandardCharsets.UTF_8), "unix datagram")
            val sender = from.clear()
            assertEquals(sender, UnixAddress(clientPath))
            assertEquals(run(r, backend.ops.send(server, bytes("reply"), sender)), null)
            val reply = allocate(64)
            assertEquals(run(r, backend.ops.receive(client, reply)), null)
            assertEquals(StandardCharsets.UTF_8.decode(reply).toString, "reply")
          } finally closeAlone(client)
        } finally closeAlone(server)
        assert(!Files.exists(java.nio.file.Path.of(serverPath)), "closing a bound datagram socket removes its path")
      }
    finally deleteAll(dir)
  }

  test(
    "Unix datagrams follow the IP rules: an unnamed sender, and no empty packet on a connected socket"
  ) {
    val dir = tempDir()
    try
      withReactor { r =>
        val serverPath = UnixAddress(dir.resolve("server.sock").toString)
        val server = bindUnixDatagram(r, serverPath)
        try {
          val unnamed = connectUnixDatagram(r, null, serverPath) // with no path of its own
          try {
            def refused(failure: Throwable | Null)(using munit.Location): Unit =
              assert(failure.isInstanceOf[IllegalArgumentException], String.valueOf(failure))
            val empty = allocate(4)
            empty.limit(0)
            refused(run(r, backend.ops.send(unnamed, empty)))
            assertEquals(run(r, backend.ops.send(unnamed, bytes("anonymous"))), null)
            val buf = allocate(64)
            val from = Slot[UnixAddress]()
            assertEquals(run(r, backend.ops.receive(server, buf, from)), null)
            assertEquals(StandardCharsets.UTF_8.decode(buf).toString, "anonymous")
            assertEquals(from.clear(), UnixAddress(""), "an unnamed sender has the empty path")
            intercept[IllegalArgumentException](backend.ops.send(server, bytes("x"), UnixAddress("")))
          } finally close(r, unnamed)
        } finally close(r, server)
      }
    finally deleteAll(dir)
  }

  test("Unix datagram sockets know their paths: a bound one its own, a connected one its peer and its own") {
    val dir = tempDir()
    try
      withReactor { r =>
        val serverPath = UnixAddress(dir.resolve("bound.sock").toString)
        val server = bindUnixDatagram(r, serverPath)
        try {
          assertEquals(backend.ops.localAddress(server), serverPath)
          val clientPath = UnixAddress(dir.resolve("client.sock").toString)
          val named = connectUnixDatagram(r, clientPath, serverPath)
          val unnamed = connectUnixDatagram(r, null, serverPath)
          try {
            assertEquals(backend.ops.peerAddress(named), serverPath)
            assertEquals(backend.ops.localAddress(named), clientPath)
            assertEquals(backend.ops.localAddress(unnamed), UnixAddress(""))
          } finally {
            close(r, named)
            close(r, unnamed)
          }
        } finally close(r, server)
      }
    finally deleteAll(dir)
  }

  test(
    "a Unix datagram sent from several buffers is one packet, from a bound socket or a connected one, never empty there"
  ) {
    val dir = tempDir()
    try
      withReactor { r =>
        val to = UnixAddress(dir.resolve("receiver.sock").toString)
        val receiver = bindUnixDatagram(r, to)
        val bound = bindUnixDatagram(r, UnixAddress(dir.resolve("bound.sock").toString))
        val connected = connectUnixDatagram(r, null, to)
        try {
          def received()(using munit.Location): String = {
            val buf = allocate(64)
            assertEquals(run(r, backend.ops.receive(receiver, buf, Slot[UnixAddress]())), null)
            StandardCharsets.UTF_8.decode(buf).toString
          }
          assertEquals(run(r, backend.ops.send(bound, Array(bytes("one "), bytes("packet")), to)), null)
          assertEquals(received(), "one packet")
          assertEquals(run(r, backend.ops.send(connected, Array(bytes("two "), bytes(""), bytes("parts")))), null)
          assertEquals(received(), "two parts")
          val empty = allocate(1)
          empty.limit(0)
          assert(
            run(r, backend.ops.send(connected, Array(empty, empty.duplicate()))).isInstanceOf[IllegalArgumentException]
          )
        } finally {
          close(r, receiver)
          close(r, bound)
          close(r, connected)
        }
      }
    finally deleteAll(dir)
  }

  test("a Unix datagram socket takes the buffer sizes where the backend has them, and refuses IP's options") {
    import SocketOption.Kind.{UnixBoundDatagram, UnixConnectedDatagram}
    for option <- List(SocketOption.NoDelay, SocketOption.KeepAlive, SocketOption.DontFragment, SocketOption.ReusePort)
    do assert(!backend.ops.supports(option, UnixBoundDatagram), s"$option")
    intercept[IllegalArgumentException](
      backend.ops.bindDatagram(
        UnixAddress("/nowhere.sock"),
        SocketOptions.default.set(SocketOption.DontFragment, true),
        Slot[UnixBoundDatagram]()
      )
    )
    assume(
      backend.ops.supports(SocketOption.ReceiveBuffer, UnixBoundDatagram) &&
        backend.ops.supports(SocketOption.SendBuffer, UnixConnectedDatagram),
      "this backend has no buffer sizes for Unix datagram sockets"
    )
    val dir = tempDir()
    try
      withReactor { r =>
        val path = UnixAddress(dir.resolve("buffers.sock").toString)
        val boundInto = Slot[UnixBoundDatagram]()
        val bound = opened(
          r,
          boundInto,
          backend.ops.bindDatagram(path, SocketOptions.default.set(SocketOption.ReceiveBuffer, 65536), boundInto)
        )
        val connectedInto = Slot[UnixConnectedDatagram]()
        val connected = opened(
          r,
          connectedInto,
          backend.ops
            .connectDatagram(null, path, SocketOptions.default.set(SocketOption.SendBuffer, 65536), connectedInto)
        )
        try {
          assert(backend.ops.option(bound, SocketOption.ReceiveBuffer) >= 65536)
          assert(backend.ops.option(connected, SocketOption.SendBuffer) >= 65536)
          intercept[IllegalArgumentException](backend.ops.option(bound, SocketOption.DontFragment))
        } finally {
          close(r, connected)
          close(r, bound)
        }
      }
    finally deleteAll(dir)
  }
}
