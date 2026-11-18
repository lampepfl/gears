//> using scala 3.8.4
//> using jvm system
//> using dep co.fs2::fs2-io:3.16.0
//> using dep org.typelevel::cats-effect:3.7.1
//> using mainClass Fs2Bench

import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.Chunk
import fs2.io.net.{Datagram, DatagramSocket, Network, ServerSocket, Socket, SocketOption}

import java.util.Locale

/** The IP socket scenarios of `io-bench/stacks/README.md`, on fs2-io and Cats Effect. */
object Fs2Bench {

  private val MessageSize = 64
  private val ChunkSize = 65536

  private val AllScenarios =
    List("tcp-pingpong", "tcp-concurrent", "tcp-stream", "tcp-connect", "udp-pingpong", "udp-concurrent", "udp-connected")

  private final case class Config(
      workers: Int = 1,
      scenarios: List[String] = AllScenarios,
      scale: Double = 1.0,
      connections: Int = 64,
      repeat: Int = 1
  ) {
    def n(count: Int): Int = math.max(1, math.floor(count * scale).toInt)
  }

  private def parse(args: List[String], config: Config): Config = args match {
    case Nil                             => config
    case "--workers" :: value :: rest     => parse(rest, config.copy(workers = value.toInt))
    case "--scenarios" :: value :: rest   => parse(rest, config.copy(scenarios = value.split(',').toList.filter(_.nonEmpty)))
    case "--scale" :: value :: rest       => parse(rest, config.copy(scale = value.toDouble))
    case "--connections" :: value :: rest => parse(rest, config.copy(connections = value.toInt))
    case "--repeat" :: value :: rest      => parse(rest, config.copy(repeat = value.toInt))
    case other :: _                       => throw new IllegalArgumentException(s"unknown or incomplete argument: $other")
  }

  private var config = Config()

  /** The arguments are read before the runtime is built, so that its compute pool has `--workers` threads. */
  def main(args: Array[String]): Unit = {
    try config = parse(args.toList, Config())
    catch {
      case e: IllegalArgumentException =>
        System.err.println(e.getMessage)
        sys.exit(2)
    }
    App.main(args)
  }

  private object App extends IOApp.Simple {
    override protected def computeWorkerThreadCount: Int = config.workers

    def run: IO[Unit] = config.scenarios.traverse_(scenario).replicateA_(config.repeat)
  }

  private def scenario(name: String): IO[Unit] = name match {
    case "tcp-pingpong"   => tcpEcho(name, 1, config.n(100_000))
    case "tcp-concurrent" => tcpEcho(name, config.connections, config.n(5_000))
    case "tcp-stream"     => tcpStream(name, config.n(20_000))
    case "tcp-connect"    => tcpConnect(name, config.n(1_000))
    case "udp-pingpong"   => udpEcho(name, 1, config.n(100_000))
    case "udp-concurrent" => udpEcho(name, config.connections, config.n(5_000))
    case "udp-connected"  => udpConnected(name, config.n(100_000))
    case other            => IO(System.err.println(s"no such scenario: $other"))
  }

  private def timed(name: String, ops: Long)(work: IO[Unit]): IO[Unit] =
    work.timed.flatMap { (time, _) =>
      val seconds = time.toNanos / 1e9
      IO.println(
        String.format(Locale.ROOT, "bench: %-20s %9d ops %8.3f s %9.3f us/op", name, ops, seconds, seconds * 1e6 / ops)
      )
    }

  private val network = Network[IO]
  private val loopback = SocketAddress(ip"127.0.0.1", Port.Wildcard)
  private val message = Chunk.array(new Array[Byte](MessageSize))

  private def whole(chunk: Chunk[Byte]): IO[Chunk[Byte]] =
    if chunk.size == MessageSize then IO.pure(chunk)
    else IO.raiseError(new java.io.EOFException(s"a message of ${chunk.size} bytes"))

  // ---- TCP

  private val noDelay = List(SocketOption.noDelay(true))

  /** A listener; the options are those of the sockets it accepts. */
  private val listener: Resource[IO, ServerSocket[IO]] = network.bind(loopback, noDelay)

  /** One connection through `server`, as (client end, server end). */
  private def connection(server: ServerSocket[IO]): Resource[IO, (Socket[IO], Socket[IO])] =
    network.connect(server.address, noDelay).both(server.accept.head.compile.resource.lastOrError)

  /** `count` connections through one listener, made one after another. */
  private def connections(count: Int): Resource[IO, List[(Socket[IO], Socket[IO])]] =
    listener.flatMap(server => connection(server).replicateA(count))

  private def echoServer(socket: Socket[IO], rounds: Int): IO[Unit] =
    socket.readN(MessageSize).flatMap(whole).flatMap(socket.write).replicateA_(rounds)

  private def echoClient(socket: Socket[IO], rounds: Int): IO[Unit] =
    (socket.write(message) >> socket.readN(MessageSize).flatMap(whole)).replicateA_(rounds)

  private def tcpEcho(name: String, count: Int, rounds: Int): IO[Unit] =
    connections(count).use { pairs =>
      timed(name, rounds.toLong * count) {
        pairs.parTraverse_((client, server) => echoServer(server, rounds).both(echoClient(client, rounds)))
      }
    }

  private def tcpStream(name: String, chunks: Int): IO[Unit] =
    connections(1).use { pairs =>
      val (client, server) = pairs.head
      val chunk = Chunk.array(new Array[Byte](ChunkSize))
      val writer = client.write(chunk).replicateA_(chunks) >> client.endOfOutput
      def reader: IO[Unit] = server.read(ChunkSize).flatMap {
        case Some(_) => reader
        case None    => IO.unit
      }
      timed(name, chunks)(writer.both(reader).void)
    }

  private def tcpConnect(name: String, count: Int): IO[Unit] =
    listener.use { server =>
      // Each accepted socket is closed as the stream goes on from it.
      val accepting = server.accept.take(count).compile.drain
      // Reading until the server has closed its end, so that the next connect finds the last accepted.
      val connecting = network
        .connect(server.address, noDelay)
        .use(_.read(8))
        .flatMap {
          case None    => IO.unit
          case Some(_) => IO.raiseError(new java.io.IOException("bytes from a server that sends none"))
        }
        .replicateA_(count)
      timed(name, count)(accepting.both(connecting).void)
    }

  // ---- UDP

  private val datagramSocket: Resource[IO, DatagramSocket[IO]] = network.bindDatagramSocket(loopback)

  private def received(datagram: Datagram): IO[Chunk[Byte]] = whole(datagram.bytes)

  // A datagram socket's `write` makes its buffer as the `IO` is built, not as it is run (fs2-io 3.16.0), and a second
  // run of the same `IO` sends what is left of that buffer, which is nothing. So each send is built anew, in `defer`.

  private def udpPair(client: DatagramSocket[IO], server: DatagramSocket[IO], rounds: Int): IO[Unit] = {
    val echo = server.read.flatMap(received).flatMap(server.write(_, client.address)).replicateA_(rounds)
    val asking =
      (IO.defer(client.write(message, server.address)) >> client.read.flatMap(received)).replicateA_(rounds)
    echo.both(asking).void
  }

  private def udpEcho(name: String, count: Int, rounds: Int): IO[Unit] =
    datagramSocket.both(datagramSocket).replicateA(count).use { pairs =>
      timed(name, rounds.toLong * count)(pairs.parTraverse_((client, server) => udpPair(client, server, rounds)))
    }

  private def udpConnected(name: String, rounds: Int): IO[Unit] =
    datagramSocket.both(datagramSocket).use { (client, server) =>
      val echo = server.read.flatMap(received).flatMap(server.write(_)).replicateA_(rounds)
      val asking = (IO.defer(client.write(message)) >> client.read.flatMap(received)).replicateA_(rounds)
      client.connect(server.address) >> server.connect(client.address) >>
        timed(name, rounds)(echo.both(asking).void)
    }
}
