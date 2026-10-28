package example.tests

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.util.Using

/** Exercises the native demos against both native and Java socket peers. */
class SocketDemoSuite extends DemoSuite {

  private val Reply = "Just pinging back!\n"

  enum Family(val suffix: String, val name: String) {
    case Unix extends Family("", "AF_UNIX")
    case IPv4 extends Family("4", "AF_INET")
    case IPv6 extends Family("6", "AF_INET6")

    def host: String = if (this == IPv6) "::1" else "127.0.0.1"
    def protocol: StandardProtocolFamily = this match {
      case Unix => StandardProtocolFamily.UNIX
      case IPv4 => StandardProtocolFamily.INET
      case IPv6 => StandardProtocolFamily.INET6
    }
  }

  /** A server address plus the demo arguments that select it. */
  final case class Endpoint(address: SocketAddress, args: Seq[String])

  /** A stream server the stream checks run against, beside `sock-serve`: run as `<command><suffix>-serve`, with `args`
    * after the address's, for the families listed.
    */
  final case class StreamServer(command: String, args: Seq[String], families: Seq[Family] = Family.values.toSeq)

  /** The stream servers checked beside `sock-serve`. */
  def otherStreamServers: Seq[StreamServer] = Nil

  for (family <- Family.values) {
    demoTest(s"stream ${family.name}: native clients, framing, fragmentation, invalid length, concurrent clients") {
      withTempDirectory("gears-sockets-")(stream(family, _))
    }
    for (server <- otherStreamServers if server.families.contains(family))
      demoTest(s"stream ${family.name}, ${(server.command +: server.args).mkString(" ")}: the same") {
        withTempDirectory("gears-sockets-")(stream(family, _, server))
      }
    if family != Family.Unix || hasUnixDatagrams then
      demoTest(s"datagram ${family.name}: native clients, UTF-8, empty/binary packets, queued senders") {
        withTempDirectory("gears-sockets-")(datagram(family, _))
      }
    if family != Family.Unix then
      demoTest(s"restart ${family.name}: a killed server's port can be listened on again at once") {
        withTempDirectory("gears-sockets-")(restart(family, _))
      }
    demoTest(s"key-value ${family.name}: pipelined commands from native and Java clients") {
      withTempDirectory("gears-sockets-")(keyValue(family, _))
    }
  }
  demoTest("errors: numeric addresses, ports, refused connection, cleanup, path length, UDP timeout") {
    withTempDirectory("gears-sockets-")(errors)
  }

  private def endpoint(family: Family, datagram: Boolean, directory: Path): Endpoint =
    family match {
      case Family.Unix =>
        val path = directory.resolve("server-é.sock")
        Endpoint(UnixDomainSocketAddress.of(path), Seq("--sock", path.toString))
      case _ =>
        // Ask the OS for a free port, then hand it to the native server.
        val probe = new InetSocketAddress(family.host, 0)
        val bound =
          if (datagram) Using.resource(DatagramChannel.open(family.protocol).bind(probe))(_.getLocalAddress)
          else Using.resource(ServerSocketChannel.open(family.protocol).bind(probe))(_.getLocalAddress)
        val port = bound.asInstanceOf[InetSocketAddress].getPort
        Endpoint(new InetSocketAddress(family.host, port), Seq("--host", family.host, "--port", port.toString))
    }

  private def utf8(text: String): Array[Byte] = text.getBytes(StandardCharsets.UTF_8)

  /** The stream demos' four-byte big-endian length header followed by the payload. */
  private def frame(payload: Array[Byte]): Array[Byte] =
    ByteBuffer.allocate(4 + payload.length).putInt(payload.length).put(payload).array()

  /** Reads to the end of the stream, which must hold one whole frame and nothing more, and returns its payload. */
  private def readReply(channel: SocketChannel): String = {
    val out = new ByteArrayOutputStream
    val buffer = ByteBuffer.allocate(4096)
    while (channel.read(buffer) != -1) {
      out.write(buffer.array(), 0, buffer.position())
      buffer.clear()
    }
    val bytes = out.toByteArray
    assert(bytes.length >= 4, s"a reply of ${bytes.length} bytes has no header")
    val length = ByteBuffer.wrap(bytes).getInt(0)
    assertEquals(bytes.length, 4 + length, "a reply is one whole frame")
    new String(bytes, 4, length, StandardCharsets.UTF_8)
  }

  /** Serves one request, kills the server without letting it clean up, and listens on the same port again. The server
    * closed that connection first, so the port has a connection in TIME_WAIT when the second server binds it.
    */
  private def restart(family: Family, directory: Path): Unit = {
    val Endpoint(_, args) = endpoint(family, datagram = false, directory)
    for (_ <- 1 to 2)
      // Stopping a server destroys its process, which, like Ctrl-C, ends it without running any cleanup.
      withServer(s"sock${family.suffix}-serve" +: args, "listening for connections") { _ =>
        assert(run(s"sock${family.suffix}" +: args*).contains("Just pinging back!"))
      }
  }

  private def stream(family: Family, directory: Path, serving: StreamServer = StreamServer("sock", Nil)): Unit = {
    val Endpoint(address, args) = endpoint(family, datagram = false, directory)
    withServer(s"${serving.command}${family.suffix}-serve" +: (args ++ serving.args), "listening for connections") {
      server =>
        for (_ <- 1 to 2) assert(run(s"sock${family.suffix}" +: args*).contains("Just pinging back!"))
        val payloads = Seq(
          (utf8("coalesced"), false),
          (utf8("fragmented"), true),
          (Array.empty[Byte], false),
          (Array.fill[Byte](131072)('x'), false)
        )
        for ((payload, fragmented) <- payloads) {
          Using.resource(SocketChannel.open(address)) { client =>
            val bytes = frame(payload)
            if (fragmented) {
              for ((from, until) <- Seq((0, 1), (1, 3), (3, 5), (5, bytes.length))) {
                client.write(ByteBuffer.wrap(bytes, from, until - from))
                Thread.sleep(30)
              }
            } else client.write(ByteBuffer.wrap(bytes))
            assertEquals(readReply(client), Reply)
          }
        }
        // Reject an invalid frame without losing the listening server.
        Using.resource(SocketChannel.open(address)) { client =>
          client.write(ByteBuffer.allocate(4).putInt(0xffffffff).flip())
          assertEquals(client.read(ByteBuffer.allocate(1)), -1)
        }
        assert(run(s"sock${family.suffix}" +: args*).contains("Just pinging back!"))
        // Clients that go away before the reply: writing to them must fail only that connection, not raise SIGPIPE and
        // end the server. A TCP client resets the connection (SO_LINGER 0), so that the server's one write fails.
        for (_ <- 1 to 3) {
          val client = SocketChannel.open(address)
          if (family != Family.Unix) client.setOption(java.net.StandardSocketOptions.SO_LINGER, Integer.valueOf(0))
          client.write(ByteBuffer.wrap(frame(utf8("going away"))))
          client.close()
        }
        Thread.sleep(200)
        assert(server.isAlive, server.output.takeRight(4096))
        assert(run(s"sock${family.suffix}" +: args*).contains("Just pinging back!"))
        // Keep several connections ready together to exercise native event-array
        // indexing, including events beyond the first entry in a poll result.
        val clients = Vector.fill(8)(SocketChannel.open(address))
        try {
          for ((client, i) <- clients.zipWithIndex)
            client.write(ByteBuffer.wrap(frame(utf8(s"concurrent client $i"))))
          for (client <- clients) assertEquals(readReply(client), Reply)
        } finally clients.foreach(_.close())
        assert(server.isAlive, server.output.takeRight(4096))
    }
  }

  private def keyValue(family: Family, directory: Path): Unit = {
    val Endpoint(address, args) = endpoint(family, datagram = false, directory)
    withServer(s"kv${family.suffix}-serve" +: args, "listening for connections") { server =>
      // Sets a counter, pipelines 20000 increments, and reads it back. The batch is far larger than the socket
      // buffers, so it only completes if replies are read while commands are still being sent.
      val output = run(s"kv${family.suffix}" +: args ++: Seq("--count", "20000")*)
      assert(output.contains("Received 20002 replies; last reply: `20000`"), output)
      // A Java client sends a batch in one write and checks each reply, including the counter left by the native run.
      val commands = Seq("SET name gears", "GET name", "INCR hits", "INCR hits", "GET missing", "GET counter", "BOGUS")
      val expected = Seq("OK", "gears", "1", "2", "(nil)", "20000", "ERR unknown command `BOGUS`")
      Using.resource(SocketChannel.open(address)) { client =>
        val batch = new java.io.ByteArrayOutputStream()
        for (command <- commands) {
          val bytes = utf8(command)
          batch.write(ByteBuffer.allocate(4).putInt(bytes.length).array())
          batch.write(bytes)
        }
        client.write(ByteBuffer.wrap(batch.toByteArray))
        val replies = expected.map { reply =>
          val frame = ByteBuffer.allocate(4 + utf8(reply).length)
          while (frame.hasRemaining) assert(client.read(frame) != -1, "server closed early")
          frame.flip()
          val length = frame.getInt()
          val bytes = new Array[Byte](length)
          frame.get(bytes)
          new String(bytes, StandardCharsets.UTF_8)
        }
        assertEquals(replies, expected)
      }
      assert(server.isAlive, server.output.takeRight(4096))
    }
  }

  private def datagram(family: Family, directory: Path): Unit = {
    val Endpoint(address, args) = endpoint(family, datagram = true, directory)
    withServer(s"datagram${family.suffix}-serve" +: args, "waiting for messages") { server =>
      val local = directory.resolve("native-client-é.sock")
      val localArgs = if (family == Family.Unix) Seq("--local-sock", local.toString) else Seq.empty
      for (message <- Seq("hello", "héllo 🌍") ++ (if sendsEmptyConnectedDatagrams then Seq("") else Nil)) {
        val output = run(s"datagram${family.suffix}" +: args ++: localArgs ++: Seq("-m", message)*)
        assert(output.contains(s"Received datagram of ${utf8(message).length} bytes: `$message`"), output)
        assert(!Files.exists(local), "Unix client path was not removed")
      }
      family match {
        case Family.Unix =>
          // Java has no Unix datagram sockets, so several native clients send in turn instead.
          val serverPath = address.asInstanceOf[UnixDomainSocketAddress].getPath.toString
          for (i <- 0 until 3) {
            val message = s"peer $i"
            val peer = directory.resolve(s"peer-$i.sock").toString
            val output = run("datagram", "--sock", serverPath, "--local-sock", peer, "-m", message)
            assert(output.contains(s"Received datagram of ${message.length} bytes: `$message`"), output)
          }
        case _ =>
          val inet = address.asInstanceOf[InetSocketAddress]
          val clients = Vector.fill(3)(new DatagramSocket(new InetSocketAddress(family.host, 0)))
          try {
            clients.foreach(_.setSoTimeout(5000))
            // Queue packets before reading replies to expose lost readiness events
            // and accidental merging of packets or sender addresses.
            val packets =
              Seq(Array.empty[Byte], utf8("one"), utf8("two\u0000three"), Array.tabulate[Byte](1024)(_.toByte))
            def tagged(packet: Array[Byte], i: Int): Array[Byte] = if (packet.isEmpty) packet else packet :+ i.toByte
            for ((client, i) <- clients.zipWithIndex; packet <- packets) {
              val data = tagged(packet, i)
              client.send(new DatagramPacket(data, data.length, inet))
            }
            for ((client, i) <- clients.zipWithIndex; packet <- packets) {
              val received = new DatagramPacket(new Array[Byte](65536), 65536)
              client.receive(received)
              val data = java.util.Arrays.copyOf(received.getData, received.getLength)
              assertEquals(data.toSeq, tagged(packet, i).toSeq)
              assertEquals(received.getAddress, InetAddress.getByName(family.host))
              assertEquals(received.getPort, inet.getPort)
            }
          } finally clients.foreach(_.close())
      }
      assert(server.isAlive, server.output.takeRight(4096))
    }
  }

  private def errors(directory: Path): Unit = {
    val invalidHosts =
      Seq(("sock4", "::1"), ("sock6", "127.0.0.1"), ("datagram4-serve", "invalid"), ("datagram6-serve", "invalid"))
    for ((command, host) <- invalidHosts)
      assert(runFailing(command, "--host", host).contains("Not a numeric"))
    for (port <- Seq("-1", "65536"))
      assert(runFailing("sock4", "--port", port).contains("A port is from 0 to 65535"))
    // A bound but not listening TCP socket refuses connections.
    Using.resource(SocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0))) { reserved =>
      val port = reserved.getLocalAddress.asInstanceOf[InetSocketAddress].getPort
      assert(runFailing("sock4", "--port", port.toString).contains("Failed to connect"))
    }
    if hasUnixDatagrams then {
      val local = directory.resolve("failed-client.sock")
      runFailing("datagram", "--sock", directory.resolve("missing.sock").toString, "--local-sock", local.toString)
      assert(!Files.exists(local), "Failed Unix client leaked its bound path")
      val longPath = directory.resolve("é" * 60).toString
      assert(runFailing("datagram-serve", "--sock", longPath).contains("UTF-8 bytes"))
    }
    Using.resource(new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) { silent =>
      assert(runFailing("datagram4", "--port", silent.getLocalPort.toString).contains("Timed out"))
    }
  }
}
