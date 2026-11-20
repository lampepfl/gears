//> using scala 3.8.4
//> using platform scala-js
//> using jsVersion 1.22.0
//> using jsModuleKind es
//> using dep co.fs2::fs2-io::3.16.0
//> using dep org.typelevel::cats-effect::3.7.1
//> using mainClass Fs2JsBench

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.Chunk
import fs2.io.net.{Network, ServerSocket, Socket, SocketOption}

import Cache.*

/** The realistic scenarios of `io-bench/stacks/README.md`, and the echo they are set beside, on fs2-io and Cats Effect
  * for Scala.js, over Node.js's sockets: built to JavaScript, and to WebAssembly.
  */
object Fs2JsBench extends IOApp {

  private val MessageSize = 64

  private val AllScenarios =
    List("tcp-concurrent", "tcp-realistic", "tcp-realistic-bulk", "tcp-realistic-gather", "work-realistic")

  private final case class Config(
      scenarios: List[String] = AllScenarios,
      scale: Double = 1.0,
      connections: Int = 64,
      repeat: Int = 1
  ) {
    def n(count: Int): Int = math.max(1, math.floor(count * scale).toInt)
  }

  private def parse(args: List[String], config: Config): Config = args match {
    case Nil                              => config
    case "--workers" :: _ :: rest         => parse(rest, config) // one thread: ignored
    case "--scenarios" :: value :: rest   => parse(rest, config.copy(scenarios = value.split(',').toList.filter(_.nonEmpty)))
    case "--scale" :: value :: rest       => parse(rest, config.copy(scale = value.toDouble))
    case "--connections" :: value :: rest => parse(rest, config.copy(connections = value.toInt))
    case "--repeat" :: value :: rest      => parse(rest, config.copy(repeat = value.toInt))
    case other :: _                       => throw new IllegalArgumentException(s"unknown or incomplete argument: $other")
  }

  def run(args: List[String]): IO[ExitCode] = {
    val config = parse(args, Config())
    config.scenarios.traverse_(scenario(config, _)).replicateA_(config.repeat).as(ExitCode.Success)
  }

  private def scenario(config: Config, name: String): IO[Unit] = name match {
    case "tcp-concurrent" => tcpEcho(name, config.connections, config.n(5_000))
    case "tcp-realistic"  => realistic(name, config.connections, config.n(2_000))(serve, ask)
    case "tcp-realistic-bulk" =>
      realistic(name, config.connections, config.n(2_000))(serveTogether(_, _, gathering = false), askTogether)
    case "tcp-realistic-gather" =>
      realistic(name, config.connections, config.n(2_000))(serveTogether(_, _, gathering = true), askTogether)
    case "work-realistic" => work(name, config.connections, config.n(2_000))
    case _                => IO.unit // a scenario this stack cannot do prints nothing
  }

  private def timed(name: String, ops: Long)(work: IO[Unit]): IO[Unit] =
    work.timed.flatMap { (time, _) =>
      val seconds = time.toNanos / 1e9
      IO.println(f"bench: $name%-20s $ops%9d ops $seconds%8.3f s ${seconds * 1e6 / ops}%9.3f us/op")
    }

  private val network = Network[IO]
  private val loopback = SocketAddress(ip"127.0.0.1", Port.Wildcard)
  private val noDelay = List(SocketOption.noDelay(true))

  /** A listener; the options are those of the sockets it accepts. */
  private val listener: Resource[IO, ServerSocket[IO]] = network.bind(loopback, noDelay)

  /** One connection through `server`, as (client end, server end). */
  private def connection(server: ServerSocket[IO]): Resource[IO, (Socket[IO], Socket[IO])] =
    network.connect(server.address, noDelay).both(server.accept.head.compile.resource.lastOrError)

  /** `count` connections through one listener, made one after another. */
  private def connections(count: Int): Resource[IO, List[(Socket[IO], Socket[IO])]] =
    listener.flatMap(server => connection(server).replicateA(count))

  // ---- the echo

  private val message = Chunk.array(new Array[Byte](MessageSize))

  private def whole(chunk: Chunk[Byte]): IO[Chunk[Byte]] =
    if chunk.size == MessageSize then IO.pure(chunk)
    else IO.raiseError(new java.io.EOFException(s"a message of ${chunk.size} bytes"))

  private def tcpEcho(name: String, count: Int, rounds: Int): IO[Unit] =
    connections(count).use { pairs =>
      timed(name, rounds.toLong * count) {
        pairs.parTraverse_ { (client, server) =>
          val echo = server.readN(MessageSize).flatMap(whole).flatMap(server.write).replicateA_(rounds)
          val asking = (client.write(message) >> client.readN(MessageSize).flatMap(whole)).replicateA_(rounds)
          echo.both(asking)
        }
      }
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
