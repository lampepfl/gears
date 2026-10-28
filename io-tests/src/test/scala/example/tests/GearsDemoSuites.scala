package example.tests

import java.nio.file.Paths

/** The same checks against the testkit's gears demos, on kqueue, run as `iokqueuedemos gears <command>`. */
class GearsKQueueDemoSuite extends KQueueDemoSuite {
  override def commandPrefix: Seq[String] = Seq("gears")

  demoTest("quic-timers: each connection's deadline timer, moved per packet, acknowledges and closes it idle") {
    for backend <- Seq("kqueue") do {
      val output = run("quic-timers", "--backend", backend, "--connections", "16", "--packets", "201")
      assert(output.contains("every packet acknowledged, every connection closed idle"), s"$backend:\n$output")
      assert(output.contains("16 connections closed idle"), s"$backend:\n$output")
      assert(!output.contains("Exception"), s"$backend:\n$output")
    }
  }
}

/** The gears demos on Scala Native. No gears backend sends an empty datagram on a connected socket, since the JDK's
  * cannot (see `IpDatagramOps.send`); empty packets to the server, from Java's own clients, are still echoed.
  */
class GearsSocketDemoSuite extends SocketDemoSuite {
  override def commandPrefix: Seq[String] = Seq("gears")
  override def sendsEmptyConnectedDatagrams: Boolean = false
  override def otherStreamServers: Seq[StreamServer] = Seq(StreamServer("reactor-sock", Nil))
}

/** The same gears demos on the JVM, over NIO's demo application (`niodemos.NioDemos`): `NioOnCaller` (`backend`
  * "caller"), or `NioBackend` on a reactor pool ("reactors"), chosen as the bench's launchers choose it, by
  * `-Dgears.io.nio.backend`. NIO has neither FIFOs nor Unix datagram sockets, so its demos that need them fail; the
  * cancel demo is on NIO's reactors; and, as on Scala Native, no empty datagram on a connected socket.
  */
trait OnJvm extends DemoSuite {
  def backend: String

  /** The classpath property and main class of the JVM program to start. */
  def program: (String, String) = ("gears.demo.jvm.classpath", "niodemos.NioDemos")

  override def launcher: Seq[String] = {
    val (property, main) = program
    val classpath = sys.props.getOrElse(property, fail(s"set -D$property"))
    Seq(
      Paths.get(sys.props("java.home"), "bin", "java").toString,
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/jdk.internal.vm=ALL-UNNAMED",
      s"-Dgears.io.nio.backend=$backend",
      "-cp",
      classpath,
      main
    )
  }

  override def gearsMirrors: Boolean = true
  override def hasFifos: Boolean = false
  override def hasUnixDatagrams: Boolean = false
  override def sendsEmptyConnectedDatagrams: Boolean = false
}

class JvmGearsDemoSuite extends KQueueDemoSuite with OnJvm {
  def backend = "caller"
}

class JvmGearsSocketDemoSuite extends SocketDemoSuite with OnJvm {
  def backend = "caller"
}

class JvmReactorsGearsDemoSuite extends KQueueDemoSuite with OnJvm {
  def backend = "reactors"
}

class JvmReactorsGearsSocketDemoSuite extends SocketDemoSuite with OnJvm {
  def backend = "reactors"
  override def otherStreamServers: Seq[StreamServer] = Seq(StreamServer("reactor-sock", Nil))
}
