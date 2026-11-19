//> using scala 3.7.4
//> using jvm system

import java.io.EOFException
import java.net.{InetAddress, InetSocketAddress, StandardProtocolFamily, StandardSocketOptions}
import java.nio.ByteBuffer
import java.nio.channels.*
import java.nio.channels.SelectionKey.{OP_ACCEPT, OP_CONNECT, OP_READ, OP_WRITE}
import java.util.function.Consumer

/** The IP socket scenarios of `CapBench` over `java.nio.channels` and one `Selector`, on one thread: see `../README.md`.
  *
  * Every channel is non-blocking and has one [[Task]], a small state machine. A task tries its op, and only when the op
  * would block is the channel registered with the selector (or its interest changed) and the task left until the
  * selector says it is ready.
  */
object NioBench {
  import Cache.{Batch, CacheClient, CacheServer, LargestBatch, LargestValue, Resp}

  private inline val MessageSize = 64
  private inline val Chunk = 64 * 1024

  private val selector = Selector.open()

  /** Tasks begun and not yet finished. */
  private var live = 0

  private val dispatch: Consumer[SelectionKey] = key => key.attachment().asInstanceOf[Task].step()

  /** Begins each task with a first try of its op, and runs the selector until all have finished. */
  private def run(tasks: Seq[Task]): Unit = {
    live += tasks.size
    tasks.foreach(_.step())
    while live > 0 do selector.select(dispatch)
  }

  private abstract class Task {
    private var key: SelectionKey | Null = null

    /** Goes on from where it was, until an op would block or the task has finished. */
    def step(): Unit

    /** Leaves the task until `channel` is ready for `op`. */
    protected final def waitFor(channel: SelectableChannel, op: Int): Unit = {
      val k = key
      if k == null then key = channel.register(selector, op, this)
      else if k.interestOps() != op then k.interestOps(op)
    }

    /** Forgets the key, whose channel has been closed. */
    protected final def closed(): Unit = key = null

    protected final def finish(): Unit = {
      val k = key
      if k != null && k.isValid() && k.interestOps() != 0 then k.interestOps(0)
      live -= 1
    }
  }

  private type Stream = SelectableChannel & ByteChannel

  /** Reads a whole message and writes it back, `rounds` times over. Over a connected datagram socket too, where a read
    * and a write are each a whole message or nothing.
    */
  private final class Echo(channel: Stream, rounds: Int) extends Task {
    private val buf = ByteBuffer.allocateDirect(MessageSize)
    private var writing = false
    private var done = 0

    def step(): Unit =
      while true do
        if !writing then
          if channel.read(buf) < 0 then throw new EOFException
          if buf.hasRemaining() then { waitFor(channel, OP_READ); return }
          buf.flip()
          writing = true
        channel.write(buf)
        if buf.hasRemaining() then { waitFor(channel, OP_WRITE); return }
        buf.clear()
        writing = false
        done += 1
        if done == rounds then { finish(); return }
  }

  /** Writes a message and reads a whole one back, `rounds` times over. */
  private final class Ask(channel: Stream, rounds: Int) extends Task {
    private val out = ByteBuffer.allocateDirect(MessageSize)
    private val in = ByteBuffer.allocateDirect(MessageSize)
    private var writing = true
    private var done = 0

    def step(): Unit =
      while true do
        if writing then
          channel.write(out)
          if out.hasRemaining() then { waitFor(channel, OP_WRITE); return }
          in.clear()
          writing = false
        if channel.read(in) < 0 then throw new EOFException
        if in.hasRemaining() then { waitFor(channel, OP_READ); return }
        done += 1
        if done == rounds then { finish(); return }
        out.clear()
        writing = true
  }

  /** Writes `chunks` chunks and shuts down the output. */
  private final class StreamOut(channel: SocketChannel, chunks: Int) extends Task {
    private val buf = ByteBuffer.allocateDirect(Chunk)
    private var done = 0

    def step(): Unit =
      while true do
        channel.write(buf)
        if buf.hasRemaining() then { waitFor(channel, OP_WRITE); return }
        buf.clear()
        done += 1
        if done == chunks then
          channel.shutdownOutput()
          finish()
          return
  }

  /** Reads, into a buffer of one chunk, until the end of the stream. */
  private final class StreamIn(channel: SocketChannel) extends Task {
    private val buf = ByteBuffer.allocateDirect(Chunk)

    def step(): Unit =
      while true do
        buf.clear()
        val n = channel.read(buf)
        if n == 0 then { waitFor(channel, OP_READ); return }
        if n < 0 then { finish(); return }
  }

  /** Accepts a connection and closes it at once, `count` times over. */
  private final class AcceptAndClose(listener: ServerSocketChannel, count: Int) extends Task {
    private var done = 0

    def step(): Unit =
      while true do
        val accepted = listener.accept()
        if accepted == null then { waitFor(listener, OP_ACCEPT); return }
        accepted.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
        accepted.close()
        done += 1
        if done == count then { finish(); return }
  }

  /** Connects, reads until the end of the stream and closes, `count` times over, one after another. */
  private final class ConnectAndClose(address: InetSocketAddress, count: Int) extends Task {
    private val buf = ByteBuffer.allocateDirect(8)
    private var channel: SocketChannel | Null = null
    private var connected = false
    private var done = 0

    def step(): Unit =
      while true do
        var ch = channel
        if ch == null then
          ch = tcpClient()
          channel = ch
          connected = ch.connect(address)
        else if !connected then connected = ch.finishConnect()
        if !connected then { waitFor(ch, OP_CONNECT); return }
        buf.clear()
        val n = ch.read(buf)
        if n == 0 then { waitFor(ch, OP_READ); return }
        if n > 0 then throw new IllegalStateException("the server sent something")
        ch.close()
        closed()
        channel = null
        done += 1
        if done == count then { finish(); return }
  }

  /** Receives a message and sends it to `peer`, `rounds` times over, over a socket that is not connected. */
  private final class DatagramEcho(channel: DatagramChannel, peer: InetSocketAddress, rounds: Int) extends Task {
    private val buf = ByteBuffer.allocateDirect(MessageSize)
    private var sending = false
    private var done = 0

    def step(): Unit =
      while true do
        if !sending then
          buf.clear()
          if channel.receive(buf) == null then { waitFor(channel, OP_READ); return }
          buf.flip()
          sending = true
        if channel.send(buf, peer) == 0 then { waitFor(channel, OP_WRITE); return }
        sending = false
        done += 1
        if done == rounds then { finish(); return }
  }

  /** Sends a message to `peer` and receives one, `rounds` times over, over a socket that is not connected. */
  private final class DatagramAsk(channel: DatagramChannel, peer: InetSocketAddress, rounds: Int) extends Task {
    private val out = ByteBuffer.allocateDirect(MessageSize)
    private val in = ByteBuffer.allocateDirect(MessageSize)
    private var sending = true
    private var done = 0

    def step(): Unit =
      while true do
        if sending then
          out.clear()
          if channel.send(out, peer) == 0 then { waitFor(channel, OP_WRITE); return }
          sending = false
        in.clear()
        if channel.receive(in) == null then { waitFor(channel, OP_READ); return }
        done += 1
        if done == rounds then { finish(); return }
        sending = true
  }

  // ---- the session cache (see `../README.md`): `Cache.scala` has the cache, its client and the protocol

  /** Serves a connection of `tcp-realistic` or `tcp-realistic-bulk`: every whole command a read brought is answered,
    * all in one write, and the beginning of a command is kept for the next read. With one request sent at a time that
    * is an answer for each command as it comes. `capacity` is the most that comes together.
    */
  private final class CacheServe(channel: SocketChannel, rounds: Int, capacity: Int) extends Task {
    private val cache = new CacheServer
    private val in = ByteBuffer.allocateDirect(capacity)
    private val out = ByteBuffer.allocateDirect(capacity)
    private val requests = new Array[Byte](capacity)
    private val responses = new Array[Byte](capacity)
    private var have = 0
    private var answered = 0
    private var writing = false

    def step(): Unit =
      while true do
        if writing then
          channel.write(out)
          if out.hasRemaining() then { waitFor(channel, OP_WRITE); return }
          writing = false
          if answered == rounds then { finish(); return }
        in.clear()
        in.limit(capacity - have)
        val n = channel.read(in)
        if n < 0 then throw new EOFException("The connection ended within a request")
        if n == 0 then { waitFor(channel, OP_READ); return }
        in.flip()
        in.get(requests, have, n)
        have += n
        var from = 0
        var written = 0
        var next = Resp.end(requests, from, have)
        while next >= 0 do
          written = cache.handle(requests, from, responses, written)
          answered += 1
          from = next
          next = Resp.end(requests, from, have)
        // What is left is the beginning of a request, the rest of which has yet to come.
        if from > 0 then
          System.arraycopy(requests, from, requests, 0, have - from)
          have -= from
        if written > 0 then
          out.clear()
          out.put(responses, 0, written)
          out.flip()
          writing = true
  }

  /** The cache as `tcp-realistic-gather` keeps it, as `CapBench` has it: a value is a direct buffer of its own, and an
    * answer is buffers, a line for its length, the value's own buffer, and a line's end, for a gathering write to send
    * with no copy of the value.
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

    /** A line of `mark` and `number`, written into `heads`: the part of `heads` that holds it. */
    private def line(mark: Char, number: Int, heads: ByteBuffer): ByteBuffer = {
      scratch(0) = mark.toByte
      val length = Resp.putLine(scratch, 1, number)
      val part = heads.duplicate()
      heads.put(scratch, 0, length)
      part.limit(heads.position())
      part
    }

    /** Answers the command that begins at `from` in `request`, whose bytes are those of `source` at the same places,
      * with buffers put into `out` from `count`: how many there are then. Lines made for the answer go into `heads`.
      */
    def handle(
        request: Array[Byte],
        from: Int,
        source: ByteBuffer,
        heads: ByteBuffer,
        out: Array[ByteBuffer],
        count: Int
    ): Int = {
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
        // The value goes from the buffer it was read into to one of its own, buffer to buffer. The last one kept
        // under the key is used again if it is large enough.
        val held = values.get(key)
        val kept = if held != null && held.capacity() >= length then held else ByteBuffer.allocateDirect(length)
        kept.clear()
        val value = source.duplicate()
        value.limit(valueAt + length)
        value.position(valueAt)
        kept.put(value)
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

  /** Serves a connection of `tcp-realistic-gather`: every request a read brought is answered in one gathering write,
    * of buffers that are the cache's own.
    */
  private final class CacheServeGathering(channel: SocketChannel, rounds: Int) extends Task {
    private val cache = new GatheringCache
    private val requests = ByteBuffer.allocateDirect(LargestBatch)
    private val mirror = new Array[Byte](LargestBatch) // the same bytes, where the protocol is read
    private val heads = ByteBuffer.allocateDirect(Batch * 16)
    private val answers = new Array[ByteBuffer](Batch * 3)
    private var have = 0
    private var answered = 0

    /** The answers being written are those from `first` until `count`. */
    private var first = 0
    private var count = 0

    /** Answers the whole requests there are, as many as `answers` has room for, and keeps what is left. */
    private def answer(): Unit = {
      heads.clear()
      first = 0
      count = 0
      var from = 0
      var next = Resp.end(mirror, from, have)
      while next >= 0 && count + 3 <= answers.length do
        count = cache.handle(mirror, from, requests, heads, answers, count)
        answered += 1
        from = next
        next = Resp.end(mirror, from, have)
      // What is left is the beginning of a request, the rest of which has yet to come: kept in both.
      if from > 0 then
        System.arraycopy(mirror, from, mirror, 0, have - from)
        requests.limit(have)
        requests.position(from)
        requests.compact()
        have -= from
    }

    def step(): Unit =
      while true do
        if first < count then
          channel.write(answers, first, count - first)
          while first < count && !answers(first).hasRemaining() do first += 1
          if first < count then { waitFor(channel, OP_WRITE); return }
          if answered == rounds then { finish(); return }
          answer() // any requests there was no room to answer the last time
        else
          requests.limit(requests.capacity())
          requests.position(have)
          val n = channel.read(requests)
          if n < 0 then throw new EOFException("The connection ended within a request")
          if n == 0 then { waitFor(channel, OP_READ); return }
          requests.get(have, mirror, have, n)
          have += n
          answer()
  }

  /** The client `id` of the cache: sends its requests `batch` at once, in one write, and checks their answers as they
    * come, `rounds` requests in all. With a batch of one, each request is answered before the next is sent.
    */
  private final class CacheAsk(channel: SocketChannel, id: Int, rounds: Int, batch: Int) extends Task {
    private val capacity = batch * LargestValue
    private val user = new CacheClient(id)
    private val out = ByteBuffer.allocateDirect(capacity)
    private val in = ByteBuffer.allocateDirect(capacity)
    private val requests = new Array[Byte](capacity)
    private val responses = new Array[Byte](capacity)
    private val answers = new Array[Array[Byte]](batch)
    private var i = 0
    private var sent = 0 // how many requests the batch being answered has
    private var have = 0
    private var from = 0
    private var checked = 0
    private var writing = false

    private def send(): Unit = {
      sent = math.min(batch, rounds - i)
      var length = 0
      var k = 0
      while k < sent do
        length = user.request(i + k, requests, length)
        answers(k) = user.answer
        k += 1
      out.clear()
      out.put(requests, 0, length)
      out.flip()
      have = 0
      from = 0
      checked = 0
      writing = true
    }

    send()

    def step(): Unit =
      while true do
        if writing then
          channel.write(out)
          if out.hasRemaining() then { waitFor(channel, OP_WRITE); return }
          writing = false
        in.clear()
        in.limit(capacity - have)
        val n = channel.read(in)
        if n < 0 then throw new EOFException("The connection ended within an answer")
        if n == 0 then { waitFor(channel, OP_READ); return }
        in.flip()
        in.get(responses, have, n)
        have += n
        var next = Resp.end(responses, from, have)
        while next >= 0 && checked < sent do
          user.check(i + checked, answers(checked), responses, from, next)
          checked += 1
          from = next
          next = Resp.end(responses, from, have)
        if checked == sent then
          i += sent
          if i == rounds then { finish(); return }
          send()
  }

  // ---- setting up

  private val loopback = InetAddress.getByAddress(Array[Byte](127, 0, 0, 1))
  private def anyPort = new InetSocketAddress(loopback, 0)

  private def tcpClient(): SocketChannel = {
    val channel = SocketChannel.open(StandardProtocolFamily.INET)
    channel.configureBlocking(false)
    channel.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
    channel
  }

  private def listen(): ServerSocketChannel = {
    val listener = ServerSocketChannel.open(StandardProtocolFamily.INET)
    listener.bind(anyPort)
    listener
  }

  private def address(listener: ServerSocketChannel): InetSocketAddress =
    listener.getLocalAddress().asInstanceOf[InetSocketAddress]

  /** `count` connections through `listener`, one after another, each as (client end, server end). It is before the
    * scenario's work, so the connecting and accepting are blocking.
    */
  private def connected(listener: ServerSocketChannel, count: Int): Seq[(SocketChannel, SocketChannel)] = {
    val to = address(listener)
    Seq.fill(count) {
      val client = SocketChannel.open(StandardProtocolFamily.INET)
      client.connect(to)
      val server = listener.accept()
      for channel <- Seq(client, server) do
        channel.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
        channel.configureBlocking(false)
      (client, server)
    }
  }

  private def udp(): DatagramChannel = {
    val channel = DatagramChannel.open(StandardProtocolFamily.INET)
    channel.bind(anyPort)
    channel.configureBlocking(false)
    channel
  }

  private def address(channel: DatagramChannel): InetSocketAddress =
    channel.getLocalAddress().asInstanceOf[InetSocketAddress]

  /** Closes the channels and has the selector forget their keys. */
  private def close(channels: Seq[Channel]): Unit = {
    channels.foreach(_.close())
    val _ = selector.selectNow()
  }

  // ---- the scenarios

  private def timed(name: String, operations: Long)(body: => Unit): Unit = {
    val start = System.nanoTime()
    body
    val nanos = System.nanoTime() - start
    println(f"bench: $name%-20s $operations%9d ops ${nanos / 1e9}%8.3f s ${nanos / 1e3 / operations}%9.3f us/op")
  }

  private def tcpEcho(name: String, connections: Int, rounds: Int): Unit = {
    val listener = listen()
    val pairs = connected(listener, connections)
    val tasks = pairs.flatMap((client, server) => Seq(new Echo(server, rounds), new Ask(client, rounds)))
    timed(name, rounds.toLong * connections)(run(tasks))
    close(pairs.flatMap((client, server) => Seq(client, server)) :+ listener)
  }

  private def tcpStream(chunks: Int): Unit = {
    val listener = listen()
    val (client, server) = connected(listener, 1).head
    val tasks = Seq(new StreamOut(client, chunks), new StreamIn(server))
    timed("tcp-stream", chunks)(run(tasks))
    close(Seq(client, server, listener))
  }

  private def tcpConnect(count: Int): Unit = {
    val listener = listen()
    listener.configureBlocking(false)
    val tasks = Seq(new AcceptAndClose(listener, count), new ConnectAndClose(address(listener), count))
    timed("tcp-connect", count)(run(tasks))
    close(Seq(listener))
  }

  /** The sockets are bound within the time, as `CapBench` has it; they are closed outside it. */
  private def udpEcho(name: String, pairs: Int, rounds: Int): Unit = {
    var channels = List.empty[DatagramChannel]
    timed(name, rounds.toLong * pairs) {
      val tasks = Seq.fill(pairs) {
        val server = udp()
        val client = udp()
        channels = server :: client :: channels
        Seq(new DatagramEcho(server, address(client), rounds), new DatagramAsk(client, address(server), rounds))
      }
      run(tasks.flatten)
    }
    close(channels)
  }

  private def udpConnected(rounds: Int): Unit = {
    var channels = List.empty[DatagramChannel]
    timed("udp-connected", rounds) {
      val server = udp()
      val client = udp()
      channels = List(server, client)
      server.connect(address(client))
      client.connect(address(server))
      run(Seq(new Echo(server, rounds), new Ask(client, rounds)))
    }
    close(channels)
  }

  /** The session cache and its clients, over `connections` connections made beforehand: the requests sent `batch` at
    * once, and with `gathering` the answers written as their parts.
    */
  private def tcpRealistic(name: String, connections: Int, rounds: Int, batch: Int, gathering: Boolean): Unit = {
    val listener = listen()
    val pairs = connected(listener, connections)
    val tasks = pairs.zipWithIndex.flatMap { case ((client, server), id) =>
      val serving =
        if gathering then new CacheServeGathering(server, rounds)
        else new CacheServe(server, rounds, batch * LargestValue)
      Seq(serving, new CacheAsk(client, id, rounds, batch))
    }
    timed(name, rounds.toLong * connections)(run(tasks))
    close(pairs.flatMap((client, server) => Seq(client, server)) :+ listener)
  }

  /** The work of `tcp-realistic` with nothing between the two sides: what the scenario costs beside its I/O. */
  private def workRealistic(connections: Int, rounds: Int): Unit = {
    val request = new Array[Byte](LargestValue)
    val response = new Array[Byte](LargestValue)
    timed("work-realistic", rounds.toLong * connections) {
      var id = 0
      while id < connections do
        val cache = new CacheServer
        val user = new CacheClient(id)
        var i = 0
        while i < rounds do
          user.request(i, request, 0): Unit
          user.check(i, user.answer, response, 0, cache.handle(request, 0, response, 0))
          i += 1
        id += 1
    }
  }

  private val allScenarios = Seq(
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

  def main(args: Array[String]): Unit = {
    var scenarios = allScenarios
    var scale = 1.0
    var connections = 64
    var repeat = 1
    var i = 0
    while i < args.length do
      args(i) match
        case "--workers"     => () // one thread, whatever is asked for
        case "--scenarios"   => scenarios = args(i + 1).split(',').toSeq
        case "--scale"       => scale = args(i + 1).toDouble
        case "--connections" => connections = args(i + 1).toInt
        case "--repeat"      => repeat = args(i + 1).toInt
        case other           => throw new IllegalArgumentException(other)
      i += 2

    def n(x: Int): Int = math.max(1, (x * scale).toInt)

    for _ <- 1 to repeat do
      scenarios.foreach {
        case "tcp-pingpong"   => tcpEcho("tcp-pingpong", 1, n(100_000))
        case "tcp-concurrent" => tcpEcho("tcp-concurrent", connections, n(5_000))
        case "tcp-stream"     => tcpStream(n(20_000))
        case "tcp-connect"    => tcpConnect(n(1_000))
        case "udp-pingpong"   => udpEcho("udp-pingpong", 1, n(100_000))
        case "udp-concurrent" => udpEcho("udp-concurrent", connections, n(5_000))
        case "udp-connected"  => udpConnected(n(100_000))
        case "tcp-realistic"  => tcpRealistic("tcp-realistic", connections, n(2_000), 1, gathering = false)
        case "tcp-realistic-bulk" =>
          tcpRealistic("tcp-realistic-bulk", connections, n(2_000), Batch, gathering = false)
        case "tcp-realistic-gather" =>
          tcpRealistic("tcp-realistic-gather", connections, n(2_000), Batch, gathering = true)
        case "work-realistic" => workRealistic(connections, n(2_000))
        case _                => () // not one this stack does
      }
    selector.close()
  }
}
