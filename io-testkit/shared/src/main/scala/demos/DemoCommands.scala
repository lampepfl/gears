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
import gears.async.io.internal.ReactorBackend
import gears.async.io.internal.TakePolicy

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
  * backend without them, such as NIO without FIFOs or Unix datagram sockets, fails with a message saying so, and then
  * runs the demo with the backend's `DemoRunner`, the main application's, which is what starts a run.
  *
  * The commands and flags are those of the plain kqueue demos, `iokqueuedemos <command>`, that the demos mirror.
  */
class DemoCommands(backends: DemoBackends) {

  private def streams(choice: BackendChoice): DemoRunner[UnixStreams & IpStreams] =
    backends.needing(choice.backend, "Unix and IP stream sockets") { case b: (UnixStreams & IpStreams) => b }

  /** The backend chosen, or the first offered with reactors if it has none, as a reactor backend with `C`, which `has`
    * recognises: for `ReactorServerSocket`, which drives a reactor of the backend's itself, with no `Io`.
    */
  private def reactor[C <: Backend](choice: BackendChoice, needs: String)(
      has: PartialFunction[Backend, C]
  ): ReactorBackend & C = {
    val (name, runner) = backends.withReactors(choice.backend)
    runner.backend match {
      case b: ReactorBackend if has.isDefinedAt(b) => b.asInstanceOf[ReactorBackend & C]
      case _ => throw new IllegalArgumentException(s"The backend `$name` has no $needs, which this demo needs")
    }
  }

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
  @main(
    name = "reactor-sock-serve",
    doc = "sock-serve as completions on one reactor (`ReactorServerSocket`), of a backend with reactors"
  )
  def reactorSockServe(@arg sock: String, choice: BackendChoice): Unit = {
    ReactorServerSocket.run(sock)(using reactor(choice, "Unix stream sockets") { case b: UnixStreams => b })
  }
  @main(
    name = "reactor-sock4-serve",
    doc = "sock4-serve as completions on one reactor (`ReactorServerSocket`), of a backend with reactors"
  )
  def reactorSock4Serve(@arg host: String = "127.0.0.1", @arg port: Int = 9999, choice: BackendChoice): Unit = {
    ReactorServerSocket.run(IpAddress.IPv4(host, port))(using
      reactor(choice, "IP stream sockets") { case b: IpStreams => b }
    )
  }
  @main(
    name = "reactor-sock6-serve",
    doc = "sock6-serve as completions on one reactor (`ReactorServerSocket`), of a backend with reactors"
  )
  def reactorSock6Serve(@arg host: String = "::1", @arg port: Int = 9999, choice: BackendChoice): Unit = {
    ReactorServerSocket.run(IpAddress.IPv6(host, port))(using
      reactor(choice, "IP stream sockets") { case b: IpStreams => b }
    )
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
      @arg(short = 'm') message: String = "Hello from Scala Native KQueue Datagram Example!",
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
      @arg(short = 'm') message: String = "Hello from Scala Native KQueue Datagram Example!",
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
      @arg(short = 'm') message: String = "Hello from Scala Native KQueue Datagram Example!",
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
  @main(doc = "Cancelling ops on one reactor's pool of one worker, of a backend with reactors")
  def cancel(choice: BackendChoice): Unit = {
    val (name, _) = backends.withReactors(choice.backend)
    backends
      .needing(Some(name), "blocking ops and pipes") { case b: (Blocking & Pipes) => b }
      .onPool(1, TakePolicy.Affine)(GearsCancel.run())
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
