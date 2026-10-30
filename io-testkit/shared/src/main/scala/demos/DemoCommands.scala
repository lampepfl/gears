package gears.io.demos

import gears.async.io.Backend
import gears.async.io.Blocking
import gears.async.io.Fifos
import gears.async.io.IpAddress
import gears.async.io.IpDatagrams
import gears.async.io.IpStreams
import gears.async.io.Pipes
import gears.async.io.Resolving
import gears.async.io.UnixAddress
import gears.async.io.UnixDatagrams
import gears.async.io.UnixStreams

import scala.concurrent.duration.*

import mainargs.{ParserForClass, ParserForMethods, arg, main}

/** Which of a main application's backends a demo runs on: `--backend <name>`, one of those its `DemoBackends` offers,
  * or its default when not given. Every command takes it, so every command has the flag.
  */
@main
final case class BackendChoice(
    @arg(doc = "the backend to run on, one of those this application offers; its default when not given")
    backend: Option[String] = None
)

object BackendChoice {
  implicit val parser: ParserForClass[BackendChoice] = ParserForClass[BackendChoice]
}

/** The gears demos' command line, the same for every backend: a backend's main application makes one over the backends
  * it offers and runs it (`main`). The demos know only the `gears.async.io` interface, each taking the `Io` and `Async`
  * it runs with; this is where each command checks that the backend chosen has the capabilities its demo needs, which a
  * backend without them, such as the reference backend without FIFOs or Unix datagram sockets, fails with a message
  * saying so, and then runs the demo with the backend's `DemoRunner`, the main application's, which is what starts a
  * run.
  */
class DemoCommands(backends: DemoBackends) {

  private def streams(choice: BackendChoice): DemoRunner[UnixStreams & IpStreams] =
    backends.needing(choice.backend, "Unix and IP stream sockets") { case b: (UnixStreams & IpStreams) => b }

  @main
  def fileRead(@arg fifo: String, choice: BackendChoice): Unit = {
    backends.needing(choice.backend, "FIFOs") { case b: Fifos => b }.run(GearsFileRead.run(fifo))
  }
  @main
  def fileWrite(@arg fifo: String, @arg(short = 'm') message: String, choice: BackendChoice): Unit = {
    backends.needing(choice.backend, "FIFOs") { case b: Fifos => b }.run(GearsFileWrite.run(fifo, message))
  }
  @main
  def timer(choice: BackendChoice): Unit = {
    backends.named(choice.backend)._2.run(GearsTimer.run())
  }
  @main(
    name = "quic-timers",
    doc = "QUIC's acknowledgement-delay and idle timers on one deadline timer per connection, moved per packet"
  )
  def quicTimers(
      @arg connections: Int = 8,
      @arg packets: Int = 101,
      @arg(name = "interval-micros") intervalMicros: Int = 2000,
      choice: BackendChoice
  ): Unit = {
    backends
      .needing(choice.backend, "IP datagram sockets") { case b: IpDatagrams => b }
      .run(GearsQuicTimers.run(connections, packets, intervalMicros.micros))
  }
  @main
  def sockServe(@arg sock: String, choice: BackendChoice): Unit = {
    streams(choice).run(GearsServerSocket.run(sock))
  }
  @main
  def sock(@arg sock: String, choice: BackendChoice): Unit = {
    streams(choice).run(GearsSocket.run(sock))
  }
  @main(name = "sock4-serve")
  def sock4Serve(@arg host: String = "127.0.0.1", @arg port: Int = 9999, choice: BackendChoice): Unit = {
    streams(choice).run(GearsServerSocket.run(IpAddress.IPv4(host, port)))
  }
  @main(name = "sock4")
  def sock4(@arg host: String = "127.0.0.1", @arg port: Int = 9999, choice: BackendChoice): Unit = {
    streams(choice).run(GearsSocket.run(IpAddress.IPv4(host, port)))
  }
  @main(name = "sock6-serve")
  def sock6Serve(@arg host: String = "::1", @arg port: Int = 9999, choice: BackendChoice): Unit = {
    streams(choice).run(GearsServerSocket.run(IpAddress.IPv6(host, port)))
  }
  @main(name = "sock6")
  def sock6(@arg host: String = "::1", @arg port: Int = 9999, choice: BackendChoice): Unit = {
    streams(choice).run(GearsSocket.run(IpAddress.IPv6(host, port)))
  }
  @main
  def kvServe(@arg sock: String, choice: BackendChoice): Unit = {
    streams(choice).run(GearsKeyValue.serve(UnixAddress(sock)))
  }
  @main
  def kv(@arg sock: String, @arg count: Int = 1000, choice: BackendChoice): Unit = {
    streams(choice).run(GearsKeyValue.run(UnixAddress(sock), count))
  }
  @main(name = "kv4-serve")
  def kv4Serve(@arg host: String = "127.0.0.1", @arg port: Int = 9999, choice: BackendChoice): Unit = {
    streams(choice).run(GearsKeyValue.serve(IpAddress.IPv4(host, port)))
  }
  @main(name = "kv4")
  def kv4(
      @arg host: String = "127.0.0.1",
      @arg port: Int = 9999,
      @arg count: Int = 1000,
      choice: BackendChoice
  ): Unit = {
    streams(choice).run(GearsKeyValue.run(IpAddress.IPv4(host, port), count))
  }
  @main(name = "kv6-serve")
  def kv6Serve(@arg host: String = "::1", @arg port: Int = 9999, choice: BackendChoice): Unit = {
    streams(choice).run(GearsKeyValue.serve(IpAddress.IPv6(host, port)))
  }
  @main(name = "kv6")
  def kv6(@arg host: String = "::1", @arg port: Int = 9999, @arg count: Int = 1000, choice: BackendChoice): Unit = {
    streams(choice).run(GearsKeyValue.run(IpAddress.IPv6(host, port), count))
  }
  @main
  def datagramServe(@arg sock: String, choice: BackendChoice): Unit = {
    backends
      .needing(choice.backend, "Unix datagram sockets") { case b: UnixDatagrams => b }
      .run(GearsDatagram.serve(UnixAddress(sock)))
  }
  @main
  def datagram(
      @arg sock: String,
      @arg localSock: String,
      @arg(short = 'm') message: String = "Hello from the gears datagram demo!",
      choice: BackendChoice
  ): Unit = {
    backends
      .needing(choice.backend, "Unix datagram sockets") { case b: UnixDatagrams => b }
      .run(GearsDatagram.run(sock, localSock, message))
  }
  @main(name = "datagram4-serve")
  def datagram4Serve(@arg host: String = "127.0.0.1", @arg port: Int = 9999, choice: BackendChoice): Unit = {
    ipDatagrams(choice).run(GearsDatagram.serve(IpAddress.IPv4(host, port)))
  }
  @main(name = "datagram4")
  def datagram4(
      @arg host: String = "127.0.0.1",
      @arg port: Int = 9999,
      @arg(short = 'm') message: String = "Hello from the gears datagram demo!",
      choice: BackendChoice
  ): Unit = {
    ipDatagrams(choice).run(GearsDatagram.run(IpAddress.IPv4(host, port), message))
  }
  @main(name = "datagram6-serve")
  def datagram6Serve(@arg host: String = "::1", @arg port: Int = 9999, choice: BackendChoice): Unit = {
    ipDatagrams(choice).run(GearsDatagram.serve(IpAddress.IPv6(host, port)))
  }
  @main(name = "datagram6")
  def datagram6(
      @arg host: String = "::1",
      @arg port: Int = 9999,
      @arg(short = 'm') message: String = "Hello from the gears datagram demo!",
      choice: BackendChoice
  ): Unit = {
    ipDatagrams(choice).run(GearsDatagram.run(IpAddress.IPv6(host, port), message))
  }
  @main
  def whois(
      @arg host: String = "whois.iana.org",
      @arg port: Int = 43,
      @arg query: String = "example.com",
      choice: BackendChoice
  ): Unit = {
    backends
      .needing(choice.backend, "IP stream sockets and name resolution") { case b: (IpStreams & Resolving) => b }
      .run(GearsWhois.run(host, port, query))
  }
  @main(doc = "Cancelling ops: reads, blocking ops and timers, and the futures left running when the program ends")
  def cancel(choice: BackendChoice): Unit = {
    backends
      .needing(choice.backend, "blocking ops and pipes") { case b: (Blocking & Pipes) => b }
      .run(GearsCancel.run())
      .report()
  }
  @main
  def resolve(@arg host: String = "localhost", choice: BackendChoice): Unit = {
    backends.needing(choice.backend, "name resolution") { case b: Resolving => b }.run(GearsWhois.resolve(host))
  }

  private def ipDatagrams(choice: BackendChoice): DemoRunner[IpDatagrams] =
    backends.needing(choice.backend, "IP datagram sockets") { case b: IpDatagrams => b }

  /** Runs the command `args` names. */
  def main(args: Array[String]): Unit = {
    ParserForMethods(this).runOrExit(args.toIndexedSeq)
  }
}
