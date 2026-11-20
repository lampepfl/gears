import java.net.{InetAddress, InetSocketAddress, StandardSocketOptions}
import java.nio.ByteBuffer
import java.nio.channels.{ServerSocketChannel, SocketChannel}

import Cache.*

/** The scenarios of `io-bench/stacks/README.md` as blocking code: the JDK's channels in blocking mode, each side of a
  * connection a thread of its own that reads and writes and is parked by the JDK when it would block. How the threads
  * are started, and waited for, is the stack's (`Tasks`): virtual threads by hand, or Ox's forks.
  */
final class BlockingScenarios(tasks: BlockingScenarios.Tasks) {
  import BlockingScenarios.*

  private val MessageSize = 64
  private val ChunkSize = 65536

  def run(config: Config): Unit =
    for _ <- 1 to config.repeat; name <- config.scenarios do scenario(config, name)

  private def scenario(config: Config, name: String): Unit = name match {
    case "tcp-pingpong"   => tcpEcho(name, 1, config.n(100_000))
    case "tcp-concurrent" => tcpEcho(name, config.connections, config.n(5_000))
    case "tcp-stream"     => tcpStream(name, config.n(20_000))
    case "tcp-connect"    => tcpConnect(name, config.n(1_000))
    case "tcp-realistic"  => realistic(name, config.connections, config.n(2_000))(serve, ask)
    case "tcp-realistic-bulk" =>
      realistic(name, config.connections, config.n(2_000))(serveTogether(_, _, gathering = false), askTogether)
    case "tcp-realistic-gather" =>
      realistic(name, config.connections, config.n(2_000))(serveTogether(_, _, gathering = true), askTogether)
    case "work-realistic" => work(name, config.connections, config.n(2_000))
    case _                => () // a scenario this stack cannot do prints nothing
  }

  private def timed(name: String, ops: Long)(work: => Unit): Unit = {
    val start = System.nanoTime()
    work
    val seconds = (System.nanoTime() - start) / 1e9
    println(
      String.format(java.util.Locale.ROOT, "bench: %-20s %9d ops %8.3f s %9.3f us/op", name, ops, seconds, seconds * 1e6 / ops)
    )
  }

  // ---- connections

  private val loopback = InetAddress.getByName("127.0.0.1")

  private def listen(): ServerSocketChannel = {
    val server = ServerSocketChannel.open()
    server.bind(new InetSocketAddress(loopback, 0))
    server
  }

  private def noDelay(channel: SocketChannel): SocketChannel = {
    channel.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
    channel
  }

  /** `count` connections through one listener, made one after another, each as (client end, server end). */
  private def connected(count: Int)(use: List[(SocketChannel, SocketChannel)] => Unit): Unit = {
    val server = listen()
    val pairs = List.fill(count) {
      val client = noDelay(SocketChannel.open(server.getLocalAddress()))
      (client, noDelay(server.accept()))
    }
    try use(pairs)
    finally {
      pairs.foreach { (client, accepted) => client.close(); accepted.close() }
      server.close()
    }
  }

  /** Reads until `buf` is full; fails at the end of the stream. */
  private def readFully(channel: SocketChannel, buf: ByteBuffer): Unit =
    while buf.hasRemaining() do
      if channel.read(buf) < 0 then throw new java.io.EOFException("The connection ended within a message")

  private def writeFully(channel: SocketChannel, buf: ByteBuffer): Unit =
    while buf.hasRemaining() do channel.write(buf): Unit

  // ---- the echo, the stream and the connects

  private def tcpEcho(name: String, count: Int, rounds: Int): Unit =
    connected(count) { pairs =>
      timed(name, rounds.toLong * count) {
        tasks.all(pairs.flatMap { (client, server) =>
          val echo = () => {
            val buf = ByteBuffer.allocateDirect(MessageSize)
            var i = 0
            while i < rounds do {
              buf.clear()
              readFully(server, buf)
              buf.flip()
              writeFully(server, buf)
              i += 1
            }
          }
          val asking = () => {
            val out = ByteBuffer.allocateDirect(MessageSize)
            val in = ByteBuffer.allocateDirect(MessageSize)
            var i = 0
            while i < rounds do {
              out.clear()
              writeFully(client, out)
              in.clear()
              readFully(client, in)
              i += 1
            }
          }
          List(echo, asking)
        })
      }
    }

  private def tcpStream(name: String, chunks: Int): Unit =
    connected(1) { pairs =>
      val (client, server) = pairs.head
      timed(name, chunks) {
        tasks.all(
          List(
            () => {
              val chunk = ByteBuffer.allocateDirect(ChunkSize)
              var i = 0
              while i < chunks do {
                chunk.clear()
                writeFully(client, chunk)
                i += 1
              }
              client.shutdownOutput(): Unit
            },
            () => {
              val buf = ByteBuffer.allocateDirect(ChunkSize)
              var ended = false
              while !ended do {
                buf.clear()
                ended = server.read(buf) < 0
              }
            }
          )
        )
      }
    }

  private def tcpConnect(name: String, count: Int): Unit = {
    val server = listen()
    try
      timed(name, count) {
        tasks.all(
          List(
            () => {
              var i = 0
              while i < count do {
                server.accept().close()
                i += 1
              }
            },
            () => {
              val buf = ByteBuffer.allocateDirect(MessageSize)
              var i = 0
              while i < count do {
                val client = noDelay(SocketChannel.open(server.getLocalAddress()))
                buf.clear()
                while client.read(buf) >= 0 do buf.clear()
                client.close()
                i += 1
              }
            }
          )
        )
      }
    finally server.close()
  }

  // ---- the session cache

  /** One end of a connection that carries RESP values: an array, and a buffer over it for the channel. */
  private final class RespEnd(channel: SocketChannel, size: Int) {
    val bytes = new Array[Byte](size)
    private val buf = ByteBuffer.wrap(bytes)

    /** Reads once, whatever has come, at `have`: how many bytes there are then. */
    def more(have: Int): Int = {
      buf.limit(bytes.length)
      buf.position(have)
      if channel.read(buf) < 0 then throw new java.io.EOFException("The connection ended within a value")
      buf.position()
    }

    /** Receives one value, however many reads it comes in: where it ends. */
    def receive(): Int = {
      var have = 0
      var end = -1
      while end < 0 do {
        have = more(have)
        end = Resp.end(bytes, 0, have)
      }
      end
    }
  }

  private def send(channel: SocketChannel, values: Array[Byte], length: Int): Unit =
    writeFully(channel, ByteBuffer.wrap(values, 0, length))

  private def serve(channel: SocketChannel, rounds: Int): Unit = {
    val in = new RespEnd(channel, LargestValue)
    val cache = new CacheServer
    val response = new Array[Byte](LargestValue)
    var i = 0
    while i < rounds do {
      in.receive(): Unit
      send(channel, response, cache.handle(in.bytes, 0, response, 0))
      i += 1
    }
  }

  private def ask(channel: SocketChannel, id: Int, rounds: Int): Unit = {
    val in = new RespEnd(channel, LargestValue)
    val user = new CacheClient(id)
    val request = new Array[Byte](LargestValue)
    var i = 0
    while i < rounds do {
      send(channel, request, user.request(i, request, 0))
      user.check(i, user.answer, in.bytes, 0, in.receive())
      i += 1
    }
  }

  /** The cache as `tcp-realistic-gather` keeps it: a value is a direct buffer of its own, and an answer is buffers, a
    * line for its length, the value's own buffer and a line's end, for a gathering write to send with no copy of the
    * value.
    */
  private final class GatheringCache {
    private val values = new java.util.HashMap[String, ByteBuffer]()
    private val counts = new java.util.HashMap[String, Integer]()
    private val scratch = new Array[Byte](16)

    private def fixed(text: String): ByteBuffer = {
      val buf = ByteBuffer.allocateDirect(text.length)
      buf.put(Resp.bytes(text))
      buf.flip()
      buf
    }
    private val Ok = fixed("+OK\r\n")
    private val Nothing = fixed("$-1\r\n")
    private val LineEnd = fixed("\r\n")
    private val Unknown = fixed("-ERR unknown command\r\n")

    private def line(mark: Char, number: Int, heads: ByteBuffer): ByteBuffer = {
      scratch(0) = mark.toByte
      val length = Resp.putLine(scratch, 1, number)
      val part = heads.duplicate()
      heads.put(scratch, 0, length)
      part.limit(heads.position())
      part
    }

    def handle(request: Array[Byte], from: Int, heads: ByteBuffer, out: Array[ByteBuffer], count: Int): Int = {
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
        val held = values.get(key)
        val kept = if held != null && held.capacity() >= length then held else ByteBuffer.allocateDirect(length)
        kept.clear()
        kept.put(request, valueAt, length)
        kept.flip()
        values.put(key, kept)
        out(count) = Ok.duplicate()
        count + 1
      } else if nameLength == 3 && request(name) == 'G' then {
        val value = values.get(key)
        if value == null then {
          out(count) = Nothing.duplicate()
          count + 1
        } else {
          out(count) = line('$', value.remaining(), heads)
          out(count + 1) = value.duplicate()
          out(count + 2) = LineEnd.duplicate()
          count + 3
        }
      } else if nameLength == 4 && request(name) == 'I' then {
        val held = counts.get(key)
        val now = (if held == null then 0 else held.intValue) + 1
        counts.put(key, now)
        out(count) = line(':', now, heads)
        count + 1
      } else {
        out(count) = Unknown.duplicate()
        count + 1
      }
    }
  }

  private val NoBytes: ByteBuffer = ByteBuffer.allocate(0)

  private def serveTogether(channel: SocketChannel, rounds: Int, gathering: Boolean): Unit = {
    val in = new RespEnd(channel, LargestBatch)
    val requests = in.bytes
    val cache = new CacheServer
    val gathered = new GatheringCache
    val responses = new Array[Byte](LargestBatch)
    val heads = ByteBuffer.allocateDirect(Batch * 16)
    val answers = Array.fill[ByteBuffer](Batch * 3)(NoBytes)
    var have = 0
    var answered = 0
    while answered < rounds do {
      have = in.more(have)
      var from = 0
      var written = 0
      var count = 0
      var next = Resp.end(requests, from, have)
      while next >= 0 && count + 3 <= answers.length do {
        if gathering then count = gathered.handle(requests, from, heads, answers, count)
        else written = cache.handle(requests, from, responses, written)
        answered += 1
        from = next
        next = Resp.end(requests, from, have)
      }
      // What is left is the beginning of a request, the rest of which has yet to come.
      if from > 0 then {
        System.arraycopy(requests, from, requests, 0, have - from)
        have -= from
      }
      if gathering then {
        if count > 0 then {
          while answers(count - 1).hasRemaining() do channel.write(answers, 0, count): Unit
          heads.clear()
        }
      } else if written > 0 then send(channel, responses, written)
    }
  }

  private def askTogether(channel: SocketChannel, id: Int, rounds: Int): Unit = {
    val in = new RespEnd(channel, LargestBatch)
    val responses = in.bytes
    val user = new CacheClient(id)
    val requests = new Array[Byte](LargestBatch)
    val answers = new Array[Array[Byte]](Batch)
    var i = 0
    while i < rounds do {
      val batch = math.min(Batch, rounds - i)
      var length = 0
      var k = 0
      while k < batch do {
        length = user.request(i + k, requests, length)
        answers(k) = user.answer
        k += 1
      }
      send(channel, requests, length)
      var have = 0
      var from = 0
      var checked = 0
      while checked < batch do {
        have = in.more(have)
        var next = Resp.end(responses, from, have)
        while next >= 0 && checked < batch do {
          user.check(i + checked, answers(checked), responses, from, next)
          checked += 1
          from = next
          next = Resp.end(responses, from, have)
        }
      }
      i += batch
    }
  }

  private def realistic(name: String, count: Int, rounds: Int)(
      serving: (SocketChannel, Int) => Unit,
      asking: (SocketChannel, Int, Int) => Unit
  ): Unit =
    connected(count) { pairs =>
      timed(name, rounds.toLong * count) {
        tasks.all(pairs.zipWithIndex.flatMap { case ((client, server), id) =>
          List(() => serving(server, rounds), () => asking(client, id, rounds))
        })
      }
    }

  /** The work of `tcp-realistic` with nothing between the two sides. */
  private def work(name: String, count: Int, rounds: Int): Unit =
    timed(name, rounds.toLong * count) {
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
    }
}

object BlockingScenarios {

  /** How a stack runs blocking tasks side by side. */
  trait Tasks {

    /** Runs every task, each on a thread of its own, and returns when all have: the first failure is thrown. */
    def all(tasks: Seq[() => Unit]): Unit
  }

  val AllScenarios: List[String] = List(
    "tcp-pingpong",
    "tcp-concurrent",
    "tcp-stream",
    "tcp-connect",
    "tcp-realistic",
    "tcp-realistic-bulk",
    "tcp-realistic-gather",
    "work-realistic"
  )

  final case class Config(
      workers: Int = 1,
      scenarios: List[String] = AllScenarios,
      scale: Double = 1.0,
      connections: Int = 64,
      repeat: Int = 1
  ) {
    def n(count: Int): Int = math.max(1, math.floor(count * scale).toInt)
  }

  def parse(args: List[String], config: Config = Config()): Config = args match {
    case Nil                              => config
    case "--workers" :: value :: rest     => parse(rest, config.copy(workers = value.toInt))
    case "--scenarios" :: value :: rest   => parse(rest, config.copy(scenarios = value.split(',').toList.filter(_.nonEmpty)))
    case "--scale" :: value :: rest       => parse(rest, config.copy(scale = value.toDouble))
    case "--connections" :: value :: rest => parse(rest, config.copy(connections = value.toInt))
    case "--repeat" :: value :: rest      => parse(rest, config.copy(repeat = value.toInt))
    case other :: _                       => throw new IllegalArgumentException(s"unknown or incomplete argument: $other")
  }

  /** The JDK's virtual threads run on a pool of this many carrier threads: said before the first one is made. */
  def carriers(workers: Int): Unit = {
    System.setProperty("jdk.virtualThreadScheduler.parallelism", workers.toString)
    System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", math.max(workers, 256).toString)
  }
}
