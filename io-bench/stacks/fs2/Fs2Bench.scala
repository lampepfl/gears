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

import Cache.*

/** The IP socket scenarios of `io-bench/stacks/README.md`, on fs2-io and Cats Effect. */
object Fs2Bench {

  private val MessageSize = 64
  private val ChunkSize = 65536

  private val AllScenarios =
    List(
      "tcp-pingpong",
      "tcp-concurrent",
      "tcp-stream",
      "tcp-connect",
      "udp-pingpong",
      "udp-concurrent",
      "udp-connected",
      "tcp-realistic",
      "tcp-realistic-bulk",
      "tcp-realistic-gather",
      "work-realistic"
    )

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
    case "tcp-realistic"  => realistic(name, config.connections, config.n(2_000))(serve, ask)
    case "tcp-realistic-bulk" =>
      realistic(name, config.connections, config.n(2_000))(serveTogether(_, _, gathering = false), askTogether)
    case "tcp-realistic-gather" =>
      realistic(name, config.connections, config.n(2_000))(serveTogether(_, _, gathering = true), askTogether)
    case "work-realistic" => work(name, config.connections, config.n(2_000))
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

  // ---- the session cache

  /** Reads once, whatever has come, into `into` at `have`: how many bytes it holds then. */
  private def more(socket: Socket[IO], into: Array[Byte], have: Int): IO[Int] =
    socket.read(into.length - have).flatMap {
      case Some(chunk) =>
        IO {
          chunk.copyToArray(into, have)
          have + chunk.size
        }
      case None => IO.raiseError(new java.io.EOFException("The connection ended within a value"))
    }

  /** Receives one value into `value`, however many reads it comes in: where it ends. */
  private def receive(socket: Socket[IO], value: Array[Byte]): IO[Int] = {
    def go(have: Int): IO[Int] = more(socket, value, have).flatMap { now =>
      val end = Resp.end(value, 0, now)
      if end >= 0 then IO.pure(end) else go(now)
    }
    go(0)
  }

  private def serve(socket: Socket[IO], rounds: Int): IO[Unit] = IO.defer {
    val cache = new CacheServer
    val request = new Array[Byte](LargestValue)
    val response = new Array[Byte](LargestValue)
    (receive(socket, request) >>
      IO(cache.handle(request, 0, response, 0)).flatMap(length => socket.write(Chunk.array(response, 0, length))))
      .replicateA_(rounds)
  }

  private def ask(socket: Socket[IO], id: Int, rounds: Int): IO[Unit] = IO.defer {
    val user = new CacheClient(id)
    val request = new Array[Byte](LargestValue)
    val response = new Array[Byte](LargestValue)
    def go(i: Int): IO[Unit] =
      if i == rounds then IO.unit
      else
        IO(user.request(i, request, 0)).flatMap(length => socket.write(Chunk.array(request, 0, length))) >>
          receive(socket, response).flatMap(end => IO(user.check(i, user.answer, response, 0, end))) >>
          go(i + 1)
    go(0)
  }

  /** The cache as `tcp-realistic-gather` keeps it: a value is a chunk of its own, and an answer is chunks put
    * together, a line for its length, the value's own chunk and a line's end, with no copy of the value until the
    * socket writes them.
    */
  private final class GatheringCache {
    private val values = new java.util.HashMap[String, Chunk[Byte]]()
    private val counts = new java.util.HashMap[String, Integer]()
    private val Ok = Chunk.array(Resp.bytes("+OK\r\n"))
    private val Nothing = Chunk.array(Resp.bytes("$-1\r\n"))
    private val LineEnd = Chunk.array(Resp.bytes("\r\n"))
    private val Unknown = Chunk.array(Resp.bytes("-ERR unknown command\r\n"))

    def handle(request: Array[Byte], from: Int): Chunk[Byte] = {
      val until = request.length
      val parts = Resp.number(request, from + 1)
      var next = Resp.lineEnd(request, from, until)
      val nameLength = Resp.number(request, next + 1)
      val name = Resp.lineEnd(request, next, until)
      next = name + nameLength + 2
      val keyLength = Resp.number(request, next + 1)
      val keyAt = Resp.lineEnd(request, next, until)
      val key = Resp.text(request, keyAt, keyLength)
      next = keyAt + keyLength + 2
      if nameLength == 3 && request(name) == 'S' && parts == 3 then {
        val length = Resp.number(request, next + 1)
        val valueAt = Resp.lineEnd(request, next, until)
        values.put(key, Chunk.array(java.util.Arrays.copyOfRange(request, valueAt, valueAt + length)))
        Ok
      } else if nameLength == 3 && request(name) == 'G' then {
        val value = values.get(key)
        if value == null then Nothing
        else Chunk.array(Resp.bytes("$" + value.size + "\r\n")) ++ value ++ LineEnd
      } else if nameLength == 4 && request(name) == 'I' then {
        val held = counts.get(key)
        val now = (if held == null then 0 else held.intValue) + 1
        counts.put(key, now)
        Chunk.array(Resp.bytes(":" + now + "\r\n"))
      } else Unknown
    }
  }

  /** Serves a client that sends its requests together: every request a read brought is answered in one write, from one
    * array of answers, or, `gathering`, as the answers' own chunks put together.
    */
  private def serveTogether(socket: Socket[IO], rounds: Int, gathering: Boolean): IO[Unit] = IO.defer {
    val cache = new CacheServer
    val gathered = new GatheringCache
    val requests = new Array[Byte](LargestBatch)
    val responses = new Array[Byte](LargestBatch)
    def go(had: Int, answered: Int): IO[Unit] =
      if answered >= rounds then IO.unit
      else
        more(socket, requests, had).flatMap { now =>
          var have = now
          var done = answered
          var from = 0
          var written = 0
          var answers: Chunk[Byte] = Chunk.empty
          var next = Resp.end(requests, from, have)
          while next >= 0 do {
            if gathering then answers = answers ++ gathered.handle(requests, from)
            else written = cache.handle(requests, from, responses, written)
            done += 1
            from = next
            next = Resp.end(requests, from, have)
          }
          // What is left is the beginning of a request, the rest of which has yet to come.
          if from > 0 then {
            System.arraycopy(requests, from, requests, 0, have - from)
            have -= from
          }
          val writing =
            if gathering then if answers.isEmpty then IO.unit else socket.write(answers)
            else if written > 0 then socket.write(Chunk.array(responses, 0, written))
            else IO.unit
          writing >> go(have, done)
        }
    go(0, 0)
  }

  private def askTogether(socket: Socket[IO], id: Int, rounds: Int): IO[Unit] = IO.defer {
    val user = new CacheClient(id)
    val requests = new Array[Byte](LargestBatch)
    val responses = new Array[Byte](LargestBatch)
    val answers = new Array[Array[Byte]](Batch)

    /** Checks the answers of the batch that begins at request `i` as they come, `checked` of them so far. */
    def answered(i: Int, batch: Int, have: Int, from: Int, checked: Int): IO[Unit] =
      if checked == batch then IO.unit
      else
        more(socket, responses, have).flatMap { now =>
          var at = from
          var seen = checked
          var next = Resp.end(responses, at, now)
          while next >= 0 && seen < batch do {
            user.check(i + seen, answers(seen), responses, at, next)
            seen += 1
            at = next
            next = Resp.end(responses, at, now)
          }
          answered(i, batch, now, at, seen)
        }

    def go(i: Int): IO[Unit] =
      if i >= rounds then IO.unit
      else {
        val batch = math.min(Batch, rounds - i)
        IO {
          var length = 0
          var k = 0
          while k < batch do {
            length = user.request(i + k, requests, length)
            answers(k) = user.answer
            k += 1
          }
          length
        }.flatMap(length => socket.write(Chunk.array(requests, 0, length))) >>
          answered(i, batch, 0, 0, 0) >> go(i + batch)
      }
    go(0)
  }

  private def realistic(name: String, count: Int, rounds: Int)(
      serving: (Socket[IO], Int) => IO[Unit],
      asking: (Socket[IO], Int, Int) => IO[Unit]
  ): IO[Unit] =
    connections(count).use { pairs =>
      timed(name, rounds.toLong * count) {
        pairs.zipWithIndex.parTraverse_ { case ((client, server), id) =>
          serving(server, rounds).both(asking(client, id, rounds)).void
        }
      }
    }

  /** The work of `tcp-realistic` with nothing between the two sides. */
  private def work(name: String, count: Int, rounds: Int): IO[Unit] =
    timed(name, rounds.toLong * count)(IO {
      val request = new Array[Byte](LargestValue)
      val response = new Array[Byte](LargestValue)
      var id = 0
      while id < count do {
        val cache = new CacheServer
        val user = new CacheClient(id)
        var i = 0
        while i < rounds do {
          user.request(i, request, 0): Unit
          user.check(i, user.answer, response, 0, cache.handle(request, 0, response, 0))
          i += 1
        }
        id += 1
      }
    })
}
