package gears.async.io
package bench

import gears.async.Async
import gears.async.Future

import java.nio.ByteBuffer

/** Scenarios over every capability of the interface, written against the interface alone, so that the same source runs
  * over any backend, and over another branch's: what a backend's `BenchMain` runs. Each prints one line, `bench: <name>
  * <ops> ops <seconds> s <microseconds> us/op`, which `compare.Compare` reads.
  *
  * A scenario that needs a capability the backend lacks, FIFOs or Unix datagram sockets on the JVM, is skipped.
  */
object CapBench {

  /** What the scenarios are written over; a backend runs those it has the capability for (`supports`). */
  type All = Pipes & IpStreams & UnixStreams & IpDatagrams & RegularFiles & Blocking & Resolving

  def buffer(size: Int): ByteBuffer = BenchPlatform.buffer(size)

  /** A buffer the scenario reads and fills by way of arrays (see `BenchPlatform.arrayBuffer`). */
  def arrayBuffer(size: Int): ByteBuffer = BenchPlatform.arrayBuffer(size)

  final case class Args(scenarios: Seq[String], scale: Double, connections: Int, repeat: Int, serve: Int = 0)

  val allScenarios: Seq[String] = Seq(
    "pipe-pingpong",
    "pipe-concurrent",
    "pipe-stream",
    "tcp-pingpong",
    "tcp-concurrent",
    "tcp-stream",
    "tcp-connect",
    "tcp-realistic",
    "tcp-realistic-bulk",
    "tcp-realistic-gather",
    "work-realistic",
    "unix-pingpong",
    "unix-concurrent",
    "unix-stream",
    "unix-connect",
    "udp-pingpong",
    "udp-concurrent",
    "udp-connected",
    "udg-pingpong",
    "udg-concurrent",
    "fifo-pingpong",
    "file-read",
    "file-write",
    "file-concurrent",
    "file-open",
    "blocking",
    "blocking-concurrent",
    "resolve",
    "timer-zero",
    "timers"
  )

  def parse(args: Array[String]): (Int, String, Args) = {
    var workers = 1
    var policy = "affine"
    var scenarios = allScenarios
    var scale = 1.0
    var connections = 64
    var repeat = 1
    var serve = 0
    var i = 0
    while i < args.length do
      args(i) match
        case "--workers"     => workers = args(i + 1).toInt
        case "--policy"      => policy = args(i + 1)
        case "--scenarios"   => scenarios = args(i + 1).split(',').toSeq
        case "--scale"       => scale = args(i + 1).toDouble
        case "--connections" => connections = args(i + 1).toInt
        case "--repeat"      => repeat = args(i + 1).toInt
        case "--serve"       => serve = args(i + 1).toInt
        case other           => throw new IllegalArgumentException(other)
      i += 2
    (workers, policy, Args(scenarios, scale, connections, repeat, serve))
  }

  private inline val MessageSize = 64
  private inline val Chunk = 64 * 1024
  private inline val Block = 4096

  private val unique = new java.util.concurrent.atomic.AtomicInteger(0)
  private val tmp = s"/tmp/gcb-${System.nanoTime() % 100000000L}"
  private def path(kind: String): String = {
    BenchFiles.makeDirectory(tmp)
    s"$tmp/$kind-${unique.incrementAndGet()}"
  }
  private def remove(path: String): Unit = BenchFiles.remove(path)

  private def opening[K <: AnyRef](io: Io[Interface])(build: Slot[K] => io.Op)(using Async): K = {
    val into = Slot[K]()
    io.perform(build(into))
    into.clear()
  }

  // ---- byte streams, whatever they are over

  /** An echo of `rounds` messages from `in` to `out`, and its client: each message echoed back before the next. */
  private def echoPair(io: Io[ByteStreams], rounds: Int)(
      serverIn: io.ops.Readable,
      serverOut: io.ops.Writable,
      clientIn: io.ops.Readable,
      clientOut: io.ops.Writable
  )(using Async.Spawn): List[Future[Unit]] = {
    val echo = Future {
      val buf = buffer(MessageSize)
      val read = io.ops.read(serverIn, buf)
      val write = io.ops.write(serverOut, buf)
      var i = 0
      while i < rounds do
        buf.clear()
        while buf.hasRemaining() do io.perform(read)
        buf.flip()
        while buf.hasRemaining() do io.perform(write)
        i += 1
    }
    val client = Future {
      val out = buffer(MessageSize)
      val in = buffer(MessageSize)
      val write = io.ops.write(clientOut, out)
      val read = io.ops.read(clientIn, in)
      var i = 0
      while i < rounds do
        out.clear()
        while out.hasRemaining() do io.perform(write)
        in.clear()
        while in.hasRemaining() do io.perform(read)
        i += 1
    }
    List(echo, client)
  }

  /** `chunks` chunks written to `out`, which `end` then ends, and read from `in` until its end. With GEARS_TRACE set,
    * how the two took turns is printed: one worker runs them, so the plain fields are safe there.
    */
  private def streaming(io: Io[ByteStreams], chunks: Int)(in: io.ops.Readable, out: io.ops.Writable)(
      end: Async ?=> Unit
  )(using Async.Spawn): List[Future[Unit]] = {
    var lastWasWrite = false
    var switches = 0L
    var writeOps = 0L
    var partialWrites = 0L
    var readOps = 0L
    var fullReads = 0L
    var readBytes = 0L
    var inFlight = 0L
    var maxInFlight = 0L
    var sumInFlightAtRead = 0L
    val writer = Future {
      val buf = buffer(Chunk)
      val write = io.ops.write(out, buf)
      var i = 0
      while i < chunks do
        buf.clear()
        while buf.hasRemaining() do
          val before = buf.position()
          io.perform(write)
          writeOps += 1
          inFlight += buf.position() - before
          if inFlight > maxInFlight then maxInFlight = inFlight
          if buf.hasRemaining() then partialWrites += 1
          if !lastWasWrite then { lastWasWrite = true; switches += 1 }
        i += 1
      end
    }
    val reader = Future {
      val buf = buffer(Chunk)
      val read = io.ops.read(in, buf)
      var ended = false
      while !ended do
        buf.clear()
        sumInFlightAtRead += inFlight
        io.perform(read)
        readOps += 1
        readBytes += buf.position()
        inFlight -= buf.position()
        if !buf.hasRemaining() then fullReads += 1
        if lastWasWrite then { lastWasWrite = false; switches += 1 }
        ended = buf.position() == 0
      if BenchFiles.environment("GEARS_TRACE") != null then
        System.err.println(
          s"stream: writes $writeOps (partial $partialWrites) reads $readOps (full $fullReads, mean ${readBytes / readOps} B) " +
            s"turn changes $switches, mean run ${(writeOps + readOps) / math.max(1, switches)} ops, " +
            s"in flight: max ${maxInFlight / 1024} KiB, mean as a read begins ${sumInFlightAtRead / readOps / 1024} KiB"
        )
    }
    List(writer, reader)
  }

  private def closeAll(io: Io[Handles], handles: Seq[io.ops.Handle])(using Async): Unit =
    handles.foreach(handle => io.perform(io.ops.close(handle)))

  private def pipe(io: Io[Pipes])(using Async): (io.ops.Readable, io.ops.Writable) = {
    val read = Slot[io.ops.Readable]()
    val write = Slot[io.ops.Writable]()
    io.perform(io.ops.pipe(read, write))
    (read.clear(), write.clear())
  }

  private def pipeEcho(io: Io[Pipes], rounds: Int)(using Async.Spawn): List[Future[Unit]] = {
    val (requestIn, requestOut) = pipe(io)
    val (replyIn, replyOut) = pipe(io)
    val both = echoPair(io, rounds)(requestIn, replyOut, replyIn, requestOut)
    List(Future { both.foreach(_.await); closeAll(io, Seq(requestIn, requestOut, replyIn, replyOut)) })
  }

  // ---- stream sockets: `listen` and `connect` are the address family's

  private final class Sockets[I <: StreamSockets](val io: Io[I])(
      val listen: Slot[io.ops.Listener] => io.Op,
      val connect: io.ops.Listener => Slot[io.ops.Connection] => io.Op,
      val cleanup: () => Unit
  )

  private def tcp(io: Io[IpStreams]): Sockets[IpStreams] =
    new Sockets[IpStreams](io)(
      into => io.ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), into),
      listener => {
        val address = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress]
        into => io.ops.connect(address, into)
      },
      () => ()
    )

  private def unix(io: Io[UnixStreams]): Sockets[UnixStreams] = {
    val address = UnixAddress(path("sock"))
    new Sockets[UnixStreams](io)(
      into => io.ops.listen(address, into),
      _ => into => io.ops.connect(address, into),
      () => remove(address.path)
    )
  }

  /** `count` connections through one listener, each as (client end, server end). */
  private def connected(sockets: Sockets[? <: StreamSockets], count: Int)(using
      Async.Spawn
  ): (sockets.io.ops.Listener, List[(sockets.io.ops.Connection, sockets.io.ops.Connection)]) = {
    val io: sockets.io.type = sockets.io
    val listener = opening[io.ops.Listener](io)(sockets.listen)
    val connect = sockets.connect(listener)
    val accepted = Slot[io.ops.Connection]()
    val accept = io.ops.accept(listener, accepted)
    val pairs = List.fill(count) {
      val connecting = Future(opening[io.ops.Connection](io)(connect))
      io.perform(accept)
      (connecting.await, accepted.clear())
    }
    (listener, pairs)
  }

  private def socketEcho(sockets: Sockets[? <: StreamSockets], connections: Int, rounds: Int, name: String)(using
      Async.Spawn
  ): Unit = {
    val io: sockets.io.type = sockets.io
    val (listener, pairs) = connected(sockets, connections)
    timed(name, rounds.toLong * connections)(
      pairs.flatMap((client, server) => echoPair(io, rounds)(server, server, client, client))
    )
    closeAll(io, pairs.flatMap((client, server) => Seq(client, server)) :+ listener)
    sockets.cleanup()
  }

  // ---- a session cache that speaks Redis's protocol, and a client of it (see `io-bench/stacks/README.md`)

  /** RESP, the protocol of Redis: values written into arrays of bytes, and found in them. */
  private object Resp {
    private val Latin1 = java.nio.charset.StandardCharsets.ISO_8859_1

    def bytes(text: String): Array[Byte] = text.getBytes(Latin1)
    def text(from: Array[Byte], at: Int, length: Int): String = new String(from, at, length, Latin1)

    /** Writes `text` at `at`: where it ends. */
    def put(into: Array[Byte], at: Int, text: Array[Byte]): Int = {
      System.arraycopy(text, 0, into, at, text.length)
      at + text.length
    }

    /** Writes `number` in decimal and the end of a line. */
    def putLine(into: Array[Byte], at: Int, number: Int): Int = {
      var end = put(into, at, bytes(Integer.toString(number)))
      into(end) = '\r'
      into(end + 1) = '\n'
      end + 2
    }

    /** Writes a bulk string: `$`, its length, a line's end, its bytes, a line's end. */
    def putBulk(into: Array[Byte], at: Int, from: Array[Byte], start: Int, length: Int): Int = {
      into(at) = '$'
      val body = putLine(into, at + 1, length)
      System.arraycopy(from, start, into, body, length)
      into(body + length) = '\r'
      into(body + length + 1) = '\n'
      body + length + 2
    }

    /** Where the line that begins at `at` ends, past its `\r\n`; -1 if it has not all come. */
    def lineEnd(in: Array[Byte], at: Int, until: Int): Int = {
      var i = at
      while i + 1 < until do {
        if in(i) == '\r' && in(i + 1) == '\n' then return i + 2
        i += 1
      }
      -1
    }

    /** The decimal number from `at` to the end of its line. */
    def number(in: Array[Byte], at: Int): Int = {
      var i = at
      val negative = in(i) == '-'
      if negative then i += 1
      var value = 0
      while in(i) != '\r' do {
        value = value * 10 + (in(i) - '0')
        i += 1
      }
      if negative then -value else value
    }

    /** Where the value that begins at `at` ends; -1 if it has not all come. */
    def end(in: Array[Byte], at: Int, until: Int): Int = {
      val line = lineEnd(in, at, until)
      if line < 0 then -1
      else
        in(at) match {
          case '$' =>
            val length = number(in, at + 1)
            if length < 0 then line else if line + length + 2 <= until then line + length + 2 else -1
          case '*' =>
            var left = number(in, at + 1)
            var next = line
            while left > 0 && next >= 0 do {
              next = end(in, next, until)
              left -= 1
            }
            next
          case _ => line // a status, an error or an integer: its line
        }
    }
  }

  /** The cache: what it keeps for one connection, and how it answers `SET`, `GET` and `INCR`. */
  private final class CacheServer(
      values: java.util.Map[String, Array[Byte]] = new java.util.HashMap[String, Array[Byte]]()
  ) {
    private val Ok = Resp.bytes("+OK\r\n")
    private val Nothing = Resp.bytes("$-1\r\n")
    private val Unknown = Resp.bytes("-ERR unknown command\r\n")

    /** Answers the command that begins at `from` in `request`, an array of bulk strings, into `response` at `at`:
      * where the answer ends.
      */
    def handle(request: Array[Byte], from: Int, response: Array[Byte], at: Int): Int = {
      // *<n>\r\n $<l>\r\n <name>\r\n $<l>\r\n <key>\r\n [ $<l>\r\n <value>\r\n ]
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
        values.put(key, java.util.Arrays.copyOfRange(request, valueAt, valueAt + length))
        Resp.put(response, at, Ok)
      } else if nameLength == 3 && request(name) == 'G' then {
        val value = values.get(key)
        if value == null then Resp.put(response, at, Nothing) else Resp.putBulk(response, at, value, 0, value.length)
      } else if nameLength == 4 && request(name) == 'I' then {
        // Kept as Redis keeps a count, its digits; counted in one step, for a table that connections share.
        val kept = values.compute(
          key,
          (_, held) => Resp.bytes(Integer.toString((if held == null then 0 else Resp.number(held, 0)) + 1) + "\r")
        )
        response(at) = ':'
        Resp.putLine(response, at + 1, Resp.number(kept, 0))
      } else Resp.put(response, at, Unknown)
    }
  }

  /** A client of the cache, for the user `id`: it keeps the user's sessions there, reads them back, and counts the
    * user's requests. What it asks follows from the request's number, and it knows what each answer must be.
    */
  private final class CacheClient(id: Int) {
    private val kept = new java.util.HashMap[String, Array[Byte]]()
    private var hits = 0
    private var expected: Array[Byte] | Null = null
    private val Set = Resp.bytes("*3\r\n$3\r\nSET\r\n")
    private val Get = Resp.bytes("*2\r\n$3\r\nGET\r\n")
    private val Incr = Resp.bytes("*2\r\n$4\r\nINCR\r\n")
    private val Ok = Resp.bytes("+OK\r\n")
    private val Nothing = Resp.bytes("$-1\r\n")

    /** A session as a program would keep one: a document of JSON, some 500 bytes of it. */
    private def session(i: Int): Array[Byte] = {
      val text = new java.lang.StringBuilder(640)
      text.append("{\"user\":").append(id).append(",\"seq\":").append(i)
      text.append(",\"token\":\"").append(Integer.toHexString(id * 0x9e3779b1 + i)).append("-")
      text.append(Integer.toHexString(i * 0x85ebca6b + id)).append("\",\"expires\":").append(1795000000L + i)
      text.append(",\"cart\":[")
      var item = 0
      while item < 8 do {
        if item > 0 then text.append(',')
        text.append("{\"sku\":\"SKU-").append(100000 + (id * 131 + i * 17 + item * 7919) % 900000)
        text.append("\",\"qty\":").append(1 + (i + item) % 5).append(",\"price\":").append(199 + (i * 37 + item * 101) % 9800)
        text.append('}')
        item += 1
      }
      text.append("],\"locale\":\"en-GB\",\"theme\":\"").append(if i % 2 == 0 then "dark" else "light").append("\"}")
      Resp.bytes(text.toString)
    }

    /** What the answer to the last request written must be. */
    def answer: Array[Byte] = expected.nn

    /** Writes request `i` into `request` at `at`: where it ends. One in five keeps a session, one in five counts a
      * request, and the rest read a session back.
      */
    def request(i: Int, request: Array[Byte], at: Int): Int =
      if i % 5 == 3 then {
        hits += 1
        expected = Resp.bytes(":" + hits + "\r\n")
        val key = Resp.bytes("hits:" + id)
        Resp.putBulk(request, Resp.put(request, at, Incr), key, 0, key.length)
      } else {
        val name = "session:" + id + ":" + (i * 7 % 50)
        val key = Resp.bytes(name)
        if i % 5 == 0 then {
          val document = session(i)
          kept.put(name, document)
          expected = Ok
          val value = Resp.putBulk(request, Resp.put(request, at, Set), key, 0, key.length)
          Resp.putBulk(request, value, document, 0, document.length)
        } else {
          val document = kept.get(name)
          expected =
            if document == null then Nothing
            else {
              val whole = new Array[Byte](document.length + 16)
              java.util.Arrays.copyOf(whole, Resp.putBulk(whole, 0, document, 0, document.length))
            }
          Resp.putBulk(request, Resp.put(request, at, Get), key, 0, key.length)
        }
      }

    /** Fails unless the bytes of `response` from `from` to `until` are `right`, the answer to request `i`. */
    def check(i: Int, right: Array[Byte], response: Array[Byte], from: Int, until: Int): Unit = {
      var same = right.length == until - from
      var at = 0
      while same && at < right.length do {
        same = right(at) == response(from + at)
        at += 1
      }
      if !same then throw new IllegalStateException(s"request $i of client $id was answered wrongly")
    }
  }

  private inline val LargestValue = 2048

  /** How many requests go at once in `tcp-realistic-bulk`, and room for them and for their answers. */
  private inline val Batch = 16
  private inline val LargestBatch = Batch * LargestValue

  /** One end of a connection that carries RESP values. */
  private final class RespEnd(val io: Io[ByteStreams], size: Int)(in: io.ops.Readable, out: io.ops.Writable) {
    private val buf = arrayBuffer(size)
    private val read = io.ops.read(in, buf)
    private val write = io.ops.write(out, buf)

    def send(values: Array[Byte], length: Int)(using Async): Unit = {
      buf.clear()
      buf.put(values, 0, length)
      buf.flip()
      while buf.hasRemaining() do io.perform(write)
    }

    /** Reads once, whatever has come, into `into` at `have`: how many bytes. */
    def more(into: Array[Byte], have: Int)(using Async): Int = {
      buf.clear()
      if buf.remaining() > into.length - have then buf.limit(into.length - have)
      io.perform(read)
      buf.flip()
      val count = buf.remaining()
      if count == 0 then throw new java.io.EOFException("The connection ended within a value")
      buf.get(into, have, count)
      count
    }

    /** Receives one value into `value`, however many reads it comes in: how long it is. */
    def receive(value: Array[Byte])(using Async): Int = {
      var have = 0
      var end = -1
      while end < 0 do {
        have += more(value, have)
        end = Resp.end(value, 0, have)
      }
      end
    }
  }

  private def realistic(sockets: Sockets[? <: StreamSockets], connections: Int, rounds: Int, name: String)(using
      Async.Spawn
  ): Unit = {
    val io: sockets.io.type = sockets.io
    val (listener, pairs) = connected(sockets, connections)
    timed(name, rounds.toLong * connections)(pairs.zipWithIndex.flatMap { case ((client, server), id) =>
      val serving = Future {
        val end = new RespEnd(io, LargestValue)(server, server)
        val cache = new CacheServer
        val request = new Array[Byte](LargestValue)
        val response = new Array[Byte](LargestValue)
        var i = 0
        while i < rounds do {
          end.receive(request): Unit
          end.send(response, cache.handle(request, 0, response, 0))
          i += 1
        }
      }
      val asking = Future {
        val end = new RespEnd(io, LargestValue)(client, client)
        val user = new CacheClient(id)
        val request = new Array[Byte](LargestValue)
        val response = new Array[Byte](LargestValue)
        var i = 0
        while i < rounds do {
          end.send(request, user.request(i, request, 0))
          user.check(i, user.answer, response, 0, end.receive(response))
          i += 1
        }
      }
      List(serving, asking)
    })
    closeAll(io, pairs.flatMap((client, server) => Seq(client, server)) :+ listener)
    sockets.cleanup()
  }

  /** The cache as `tcp-realistic-gather` keeps it: a value is a buffer of its own, and an answer is buffers, a line
    * for its length, the value's own buffer, and a line's end, for a gathering write to send with no copy of the value.
    */
  private final class GatheringCache {
    private val values = new java.util.HashMap[String, ByteBuffer]()
    private val counts = new java.util.HashMap[String, Integer]()
    private val scratch = new Array[Byte](16)

    private def fixed(text: String): ByteBuffer = {
      val buf = arrayBuffer(text.length)
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
        // under the key is used again if it is large enough: nothing is being written from it by now.
        val held = values.get(key)
        val kept = if held != null && held.capacity() >= length then held else arrayBuffer(length)
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

  private val NoBytes: ByteBuffer = ByteBuffer.allocate(0)

  /** Serves a connection of `tcp-realistic-gather`: every request a read brought is answered in one gathering write,
    * of buffers that are the cache's own.
    */
  private def serveGathering(io: Io[ByteStreams], rounds: Int)(in: io.ops.Readable, out: io.ops.Writable)(using
      Async
  ): Unit = {
    val cache = new GatheringCache
    val requests = arrayBuffer(LargestBatch)
    val mirror = new Array[Byte](LargestBatch) // the same bytes, where the protocol is read
    val read = io.ops.read(in, requests)
    val heads = arrayBuffer(Batch * 16)
    val answers = Array.fill[ByteBuffer](Batch * 3)(NoBytes)
    val write = io.ops.write(out, answers)
    var have = 0
    var answered = 0
    while answered < rounds do {
      requests.limit(requests.capacity())
      requests.position(have)
      io.perform(read)
      val now = requests.position()
      if now == have then throw new java.io.EOFException("The connection ended within a request")
      val came = requests.duplicate()
      came.limit(now)
      came.position(have)
      came.get(mirror, have, now - have)
      have = now
      var from = 0
      var count = 0
      var next = Resp.end(mirror, from, have)
      while next >= 0 && count + 3 <= answers.length do {
        count = cache.handle(mirror, from, requests, heads, answers, count)
        answered += 1
        from = next
        next = Resp.end(mirror, from, have)
      }
      // What is left is the beginning of a request, the rest of which has yet to come: kept in both.
      if from > 0 then {
        System.arraycopy(mirror, from, mirror, 0, have - from)
        requests.limit(have)
        requests.position(from)
        requests.compact()
        have -= from
      }
      var i = count
      while i < answers.length do {
        answers(i) = NoBytes
        i += 1
      }
      while answers(count - 1).hasRemaining() do io.perform(write)
      heads.clear()
    }
  }

  /** As `realistic`, the requests sent `Batch` at once, as a client that pipelines sends them: the server answers every
    * request a read brought, in one write, and the client checks every answer a read brought. A wait for each read
    * there is, not for each request.
    */
  private def realisticBulk(
      sockets: Sockets[? <: StreamSockets],
      connections: Int,
      rounds: Int,
      name: String,
      gathering: Boolean
  )(using Async.Spawn): Unit = {
    val io: sockets.io.type = sockets.io
    val (listener, pairs) = connected(sockets, connections)
    timed(name, rounds.toLong * connections)(pairs.zipWithIndex.flatMap { case ((client, server), id) =>
      val serving =
        if gathering then Future(serveGathering(io, rounds)(server, server))
        else
          Future {
            val end = new RespEnd(io, LargestBatch)(server, server)
            val cache = new CacheServer
            val requests = new Array[Byte](LargestBatch)
            val responses = new Array[Byte](LargestBatch)
            var have = 0
            var answered = 0
            while answered < rounds do {
              have += end.more(requests, have)
              var from = 0
              var written = 0
              var next = Resp.end(requests, from, have)
              while next >= 0 do {
                written = cache.handle(requests, from, responses, written)
                answered += 1
                from = next
                next = Resp.end(requests, from, have)
              }
              // What is left is the beginning of a request, the rest of which has yet to come.
              if from > 0 then {
                System.arraycopy(requests, from, requests, 0, have - from)
                have -= from
              }
              if written > 0 then end.send(responses, written)
            }
          }
      val asking = Future {
        val end = new RespEnd(io, LargestBatch)(client, client)
        val user = new CacheClient(id)
        val requests = new Array[Byte](LargestBatch)
        val responses = new Array[Byte](LargestBatch)
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
          end.send(requests, length)
          var have = 0
          var from = 0
          var checked = 0
          while checked < batch do {
            have += end.more(responses, have)
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
      List(serving, asking)
    })
    closeAll(io, pairs.flatMap((client, server) => Seq(client, server)) :+ listener)
    sockets.cleanup()
  }

  /** The cache as a server of its own, for a client that is not this program's, `redis-benchmark` say: it listens on
    * `port`, and serves every connection that comes, answering every request a read brought in one write, until the
    * connection ends. Its table is one for every connection, as Redis's is. It never returns.
    */
  private def serveCache(io: Io[IpStreams], port: Int)(using Async.Spawn): Unit = {
    val table = new java.util.concurrent.ConcurrentHashMap[String, Array[Byte]]()
    val listener =
      opening[io.ops.Listener](io)(into => io.ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, port), into))
    println(s"serving on 127.0.0.1:$port")
    val accepted = Slot[io.ops.Connection]()
    val accept = io.ops.accept(listener, accepted)
    while true do {
      io.perform(accept)
      val connection = accepted.clear()
      Future {
        try {
          val end = new RespEnd(io, LargestBatch)(connection, connection)
          val cache = new CacheServer(table)
          val requests = new Array[Byte](LargestBatch)
          val responses = new Array[Byte](LargestBatch)
          var have = 0
          while true do {
            have += end.more(requests, have)
            var from = 0
            var written = 0
            var next = Resp.end(requests, from, have)
            // An answer is no longer than its request, but for a `GET`'s, which is a value: room is kept for one.
            while next >= 0 && written + LargestValue <= responses.length do {
              written = cache.handle(requests, from, responses, written)
              from = next
              next = Resp.end(requests, from, have)
            }
            if from > 0 then {
              System.arraycopy(requests, from, requests, 0, have - from)
              have -= from
            }
            if written > 0 then end.send(responses, written)
          }
        } catch case _: java.io.IOException => () // the client has gone
        finally
          try io.perform(io.ops.close(connection))
          catch case _: java.io.IOException => ()
      }
    }
  }

  /** The work of `tcp-realistic` with nothing between the two sides: what the scenario costs beside its I/O. */
  private def realisticWork(connections: Int, rounds: Int)(using Async.Spawn): Future[Unit] = Future {
    val request = new Array[Byte](LargestValue)
    val response = new Array[Byte](LargestValue)
    var id = 0
    while id < connections do {
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

  private def socketStream(sockets: Sockets[? <: StreamSockets], chunks: Int, name: String)(using Async.Spawn): Unit = {
    val io: sockets.io.type = sockets.io
    val (listener, pairs) = connected(sockets, 1)
    val (client, server) = pairs.head
    timed(name, chunks)(streaming(io, chunks)(server, client)(io.perform(io.ops.shutdownOutput(client))))
    closeAll(io, Seq(client, server, listener))
    sockets.cleanup()
  }

  /** A connection made, accepted and closed, `count` times over. */
  private def socketConnect(sockets: Sockets[? <: StreamSockets], count: Int, name: String)(using Async.Spawn): Unit = {
    val io: sockets.io.type = sockets.io
    val listener = opening[io.ops.Listener](io)(sockets.listen)
    val connect = sockets.connect(listener)
    val accepted = Slot[io.ops.Connection]()
    val accept = io.ops.accept(listener, accepted)
    timed(name, count) {
      val server = Future {
        var i = 0
        while i < count do
          io.perform(accept)
          io.perform(io.ops.close(accepted.clear()))
          i += 1
      }
      val client = Future {
        val buf = buffer(8)
        var i = 0
        while i < count do
          val connection = opening[io.ops.Connection](io)(connect)
          // Until the server has closed its end, so that the next connect finds the last accepted.
          buf.clear()
          io.perform(io.ops.read(connection, buf))
          io.perform(io.ops.close(connection))
          i += 1
      }
      List(server, client)
    }
    io.perform(io.ops.close(listener))
    sockets.cleanup()
  }

  // ---- datagrams

  private def udpEcho(io: Io[IpDatagrams], rounds: Int)(using Async.Spawn): List[Future[Unit]] = {
    val any = IpSocketAddress(IpAddress.IPv4Loopback, 0)
    val server = opening[io.ops.BoundDatagram](io)(into => io.ops.bindDatagram(any, into))
    val client = opening[io.ops.BoundDatagram](io)(into => io.ops.bindDatagram(any, into))
    val serverAddress = io.ops.localAddress(server)
    val clientAddress = io.ops.localAddress(client)
    val echo = Future {
      val buf = buffer(MessageSize)
      val from = Slot[IpSocketAddress]()
      val receive = io.ops.receive(server, buf, from)
      val send = io.ops.send(server, buf, clientAddress)
      var i = 0
      while i < rounds do
        io.perform(receive)
        from.clear()
        io.perform(send)
        i += 1
    }
    val asking = Future {
      val out = buffer(MessageSize)
      val in = buffer(MessageSize)
      val from = Slot[IpSocketAddress]()
      val send = io.ops.send(client, out, serverAddress)
      val receive = io.ops.receive(client, in, from)
      var i = 0
      while i < rounds do
        out.clear()
        io.perform(send)
        io.perform(receive)
        from.clear()
        i += 1
    }
    List(Future { echo.await; asking.await; closeAll(io, Seq(server, client)) })
  }

  private def udpConnectedEcho(io: Io[IpDatagrams], rounds: Int)(using Async.Spawn): List[Future[Unit]] = {
    val any = IpSocketAddress(IpAddress.IPv4Loopback, 0)
    val bound = opening[io.ops.BoundDatagram](io)(into => io.ops.bindDatagram(any, into))
    val serverAddress = io.ops.localAddress(bound)
    val client = opening[io.ops.ConnectedDatagram](io)(into => io.ops.connectDatagram(any, serverAddress, into))
    val clientAddress = io.ops.localAddress(client)
    io.perform(io.ops.close(bound))
    val server =
      opening[io.ops.ConnectedDatagram](io)(into => io.ops.connectDatagram(serverAddress, clientAddress, into))
    val echo = Future {
      val buf = buffer(MessageSize)
      val receive = io.ops.receive(server, buf)
      val send = io.ops.send(server, buf)
      var i = 0
      while i < rounds do
        io.perform(receive)
        io.perform(send)
        i += 1
    }
    val asking = Future {
      val out = buffer(MessageSize)
      val in = buffer(MessageSize)
      val send = io.ops.send(client, out)
      val receive = io.ops.receive(client, in)
      var i = 0
      while i < rounds do
        out.clear()
        io.perform(send)
        io.perform(receive)
        i += 1
    }
    List(Future { echo.await; asking.await; closeAll(io, Seq(server, client)) })
  }

  private def udgEcho(io: Io[UnixDatagrams], rounds: Int)(using Async.Spawn): List[Future[Unit]] = {
    val serverAddress = UnixAddress(path("udg"))
    val clientAddress = UnixAddress(path("udg"))
    val server = opening[io.ops.UnixBoundDatagram](io)(into => io.ops.bindDatagram(serverAddress, into))
    val client = opening[io.ops.UnixBoundDatagram](io)(into => io.ops.bindDatagram(clientAddress, into))
    val echo = Future {
      val buf = buffer(MessageSize)
      val from = Slot[UnixAddress]()
      val receive = io.ops.receive(server, buf, from)
      val send = io.ops.send(server, buf, clientAddress)
      var i = 0
      while i < rounds do
        io.perform(receive)
        from.clear()
        io.perform(send)
        i += 1
    }
    val asking = Future {
      val out = buffer(MessageSize)
      val in = buffer(MessageSize)
      val from = Slot[UnixAddress]()
      val send = io.ops.send(client, out, serverAddress)
      val receive = io.ops.receive(client, in, from)
      var i = 0
      while i < rounds do
        out.clear()
        io.perform(send)
        io.perform(receive)
        from.clear()
        i += 1
    }
    List(Future {
      echo.await; asking.await
      closeAll(io, Seq(server, client))
      remove(serverAddress.path); remove(clientAddress.path)
    })
  }

  // ---- FIFOs

  private def mkfifo(path: String): Unit = BenchFiles.makeFifo(path)

  private def fifo(io: Io[Fifos])(using Async.Spawn): (String, io.ops.Readable, io.ops.Writable) = {
    val at = path("fifo")
    mkfifo(at)
    val read = opening[io.ops.Readable](io)(into => io.ops.openFifoForReading(at, into))
    val write = opening[io.ops.Writable](io)(into => io.ops.openFifoForWriting(at, into))
    (at, read, write)
  }

  private def fifoEcho(io: Io[Fifos], rounds: Int)(using Async.Spawn): List[Future[Unit]] = {
    val (requestPath, requestIn, requestOut) = fifo(io)
    val (replyPath, replyIn, replyOut) = fifo(io)
    val both = echoPair(io, rounds)(requestIn, replyOut, replyIn, requestOut)
    List(Future {
      both.foreach(_.await)
      closeAll(io, Seq(requestIn, requestOut, replyIn, replyOut))
      remove(requestPath); remove(replyPath)
    })
  }

  // ---- files

  private def newFile(blocks: Int): String = {
    val at = path("file")
    BenchFiles.writeZeros(at, Block * blocks)
    at
  }

  /** `count` blocks read, or written, at the start of the file: one op, performed again and again. */
  private def fileBlocks(io: Io[RegularFiles], at: String, count: Int, write: Boolean)(using
      Async.Spawn
  ): Future[Unit] = Future {
    val file = opening[io.ops.File](io)(into => io.ops.open(at, write, into))
    val buf = buffer(Block)
    val op = if write then io.ops.write(file, buf, 0L) else io.ops.read(file, buf, 0L)
    var i = 0
    while i < count do
      buf.clear()
      io.perform(op)
      i += 1
    io.perform(io.ops.close(file))
  }

  private def fileOpens(io: Io[RegularFiles], at: String, count: Int)(using Async.Spawn): Future[Unit] = Future {
    var i = 0
    while i < count do
      val file = opening[io.ops.File](io)(into => io.ops.open(at, false, into))
      io.perform(io.ops.close(file))
      i += 1
  }

  // ---- blocking, names, timers

  private def blockingCalls(io: Io[Blocking], count: Int)(using Async.Spawn): Future[Unit] = Future {
    val body: () => Unit = () => ()
    var i = 0
    while i < count do
      io.perform(io.ops.blocking(body))
      i += 1
  }

  private def resolving(io: Io[Resolving], count: Int)(using Async.Spawn): Future[Unit] = Future {
    val into = Slot[List[IpAddress]]()
    var i = 0
    while i < count do
      io.perform(io.ops.resolve("localhost", into))
      into.clear()
      i += 1
  }

  private def timers(io: Io[Interface], count: Int, millis: Long)(using Async.Spawn): Future[Unit] = Future {
    val timer = io.ops.timer(millis)
    var i = 0
    while i < count do
      io.perform(timer)
      i += 1
  }

  private def timed(name: String, operations: Long)(body: => List[Future[Unit]])(using Async): Unit = {
    val start = System.nanoTime()
    body.foreach(_.await)
    val nanos = System.nanoTime() - start
    println(f"bench: $name%-20s $operations%9d ops ${nanos / 1e9}%8.3f s ${nanos / 1e3 / operations}%9.3f us/op")
  }

  /** Whether a backend with `ops` can do `scenario`, by the capability its name begins with. */
  private def supports(ops: Interface, scenario: String): Boolean = scenario.takeWhile(_ != '-') match
    case "pipe"     => ops.isInstanceOf[Pipes]
    case "tcp"      => ops.isInstanceOf[IpStreams]
    case "unix"     => ops.isInstanceOf[UnixStreams]
    case "udp"      => ops.isInstanceOf[IpDatagrams]
    case "udg"      => ops.isInstanceOf[UnixDatagrams]
    case "fifo"     => ops.isInstanceOf[Fifos]
    case "file"     => ops.isInstanceOf[RegularFiles]
    case "blocking" => ops.isInstanceOf[Blocking]
    case "resolve"  => ops.isInstanceOf[Resolving]
    case _          => true

  /** Runs the scenarios `args` name, in order, `args.repeat` times over, skipping those over a capability the backend
    * lacks.
    */
  def run(io: Io[Interface], args: Args)(using Async.Spawn): Unit = {
    if args.serve > 0 then return serveCache(io.asInstanceOf[Io[IpStreams]], args.serve)
    val able = args.copy(scenarios = args.scenarios.filter(supports(io.ops, _)))
    // Every scenario left is over what the backend has.
    val all = io.asInstanceOf[Io[All]]
    for _ <- 1 to args.repeat do once(all, able)
  }

  private def once(io: Io[All], args: Args)(using Async.Spawn): Unit = {
    def count(n: Int): Int = math.max(1, (n * args.scale).toInt)
    val many = args.connections
    args.scenarios.foreach {
      case "pipe-pingpong" =>
        val rounds = count(200_000)
        timed("pipe-pingpong", rounds)(pipeEcho(io, rounds))
      case "pipe-concurrent" =>
        val rounds = count(10_000)
        timed("pipe-concurrent", rounds.toLong * many)(List.fill(many)(pipeEcho(io, rounds)).flatten)
      case "pipe-concurrent-a" => // closed by the main future, once all are done
        val rounds = count(10_000)
        val made = List.fill(many) {
          val (requestIn, requestOut) = pipe(io)
          val (replyIn, replyOut) = pipe(io)
          (requestIn, requestOut, replyIn, replyOut)
        }
        timed("pipe-concurrent-a", rounds.toLong * many)(
          made.flatMap((requestIn, requestOut, replyIn, replyOut) =>
            echoPair(io, rounds)(requestIn, replyOut, replyIn, requestOut)
          )
        )
        made.foreach((a, b, c, d) => closeAll(io, Seq(a, b, c, d)))
      case "pipe-concurrent-b" => // never closed
        val rounds = count(10_000)
        timed("pipe-concurrent-b", rounds.toLong * many)(
          List
            .fill(many) {
              val (requestIn, requestOut) = pipe(io)
              val (replyIn, replyOut) = pipe(io)
              echoPair(io, rounds)(requestIn, replyOut, replyIn, requestOut)
            }
            .flatten
        )
      case "pipe-stream" =>
        val chunks = count(20_000)
        val (in, out) = pipe(io)
        timed("pipe-stream", chunks)(streaming(io, chunks)(in, out)(io.perform(io.ops.close(out))))
        io.perform(io.ops.close(in))

      case "tcp-options" =>
        val listener =
          opening[io.ops.Listener](io)(into => io.ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), into))
        val address = io.ops.localAddress(listener).asInstanceOf[IpSocketAddress]
        val connecting = Future(opening[io.ops.Connection](io)(into => io.ops.connect(address, into)))
        val server = opening[io.ops.Connection](io)(into => io.ops.accept(listener, into))
        val client = connecting.await
        for (name, c) <- List(("client", client), ("server", server)) do
          println(s"options $name: nodelay ${io.ops.option(c, SocketOption.NoDelay)} snd ${io.ops
              .option(c, SocketOption.SendBuffer)} rcv ${io.ops.option(c, SocketOption.ReceiveBuffer)}")
        closeAll(io, Seq(client, server, listener))
      case "tcp-pingpong"    => socketEcho(tcp(io), 1, count(100_000), "tcp-pingpong")
      case "tcp-concurrent"  => socketEcho(tcp(io), many, count(5_000), "tcp-concurrent")
      case "tcp-stream"      => socketStream(tcp(io), count(20_000), "tcp-stream")
      case "tcp-connect"     => socketConnect(tcp(io), count(1_000), "tcp-connect")
      case "tcp-realistic"   => realistic(tcp(io), many, count(2_000), "tcp-realistic")
      case "tcp-realistic-bulk" =>
        realisticBulk(tcp(io), many, count(2_000), "tcp-realistic-bulk", gathering = false)
      case "tcp-realistic-gather" =>
        realisticBulk(tcp(io), many, count(2_000), "tcp-realistic-gather", gathering = true)
      case "work-realistic" =>
        val rounds = count(2_000)
        timed("work-realistic", rounds.toLong * many)(List(realisticWork(many, rounds)))
      case "unix-pingpong"   => socketEcho(unix(io), 1, count(100_000), "unix-pingpong")
      case "unix-concurrent" => socketEcho(unix(io), many, count(5_000), "unix-concurrent")
      case "unix-stream"     => socketStream(unix(io), count(20_000), "unix-stream")
      case "unix-connect"    => socketConnect(unix(io), count(5_000), "unix-connect")

      case "udp-pingpong" =>
        val rounds = count(100_000)
        timed("udp-pingpong", rounds)(udpEcho(io, rounds))
      case "udp-concurrent" =>
        val rounds = count(5_000)
        timed("udp-concurrent", rounds.toLong * many)(List.fill(many)(udpEcho(io, rounds)).flatten)
      case "udp-connected" =>
        val rounds = count(100_000)
        timed("udp-connected", rounds)(udpConnectedEcho(io, rounds))
      case "udg-pingpong" =>
        io.ops match
          case _: UnixDatagrams =>
            val rounds = count(100_000)
            timed("udg-pingpong", rounds)(udgEcho(io.asInstanceOf[Io[UnixDatagrams]], rounds))
          case _ => ()
      case "udg-concurrent" =>
        io.ops match
          case _: UnixDatagrams =>
            val rounds = count(5_000)
            val udg = io.asInstanceOf[Io[UnixDatagrams]]
            timed("udg-concurrent", rounds.toLong * many)(List.fill(many)(udgEcho(udg, rounds)).flatten)
          case _ => ()

      case "fifo-pingpong" =>
        io.ops match
          case _: Fifos =>
            val rounds = count(100_000)
            timed("fifo-pingpong", rounds)(fifoEcho(io.asInstanceOf[Io[Fifos]], rounds))
          case _ => ()

      case "file-read" =>
        val at = newFile(4)
        val blocks = count(50_000)
        timed("file-read", blocks)(List(fileBlocks(io, at, blocks, write = false)))
        remove(at)
      case "file-write" =>
        val at = newFile(4)
        val blocks = count(50_000)
        timed("file-write", blocks)(List(fileBlocks(io, at, blocks, write = true)))
        remove(at)
      case "file-concurrent" =>
        val at = newFile(4)
        val blocks = count(2_000)
        timed("file-concurrent", blocks.toLong * many)(List.fill(many)(fileBlocks(io, at, blocks, write = false)))
        remove(at)
      case "file-open" =>
        val at = newFile(1)
        val opens = count(20_000)
        timed("file-open", opens)(List(fileOpens(io, at, opens)))
        remove(at)

      case "blocking" =>
        val calls = count(50_000)
        timed("blocking", calls)(List(blockingCalls(io, calls)))
      case "blocking-concurrent" =>
        val calls = count(2_000)
        timed("blocking-concurrent", calls.toLong * many)(List.fill(many)(blockingCalls(io, calls)))
      case "resolve" =>
        val lookups = count(2_000)
        timed("resolve", lookups)(List(resolving(io, lookups)))

      case "timer-zero" =>
        val turns = count(200_000)
        timed("timer-zero", turns)(List(timers(io, turns, 0)))
      case "timers" =>
        // A thousand futures each sleeping a millisecond at a time: ideally a microsecond an op.
        val sleeps = count(200)
        timed("timers", sleeps.toLong * 1000)(List.fill(1000)(timers(io, sleeps, 1)))

      case other => throw new IllegalArgumentException(s"no scenario $other")
    }
    BenchFiles.remove(tmp)
  }
}
