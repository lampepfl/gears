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

  /** What every backend benched has. */
  type All = Pipes & IpStreams & UnixStreams & IpDatagrams & RegularFiles & Blocking & Resolving

  def buffer(size: Int): ByteBuffer = BenchPlatform.buffer(size)

  final case class Args(scenarios: Seq[String], scale: Double, connections: Int, repeat: Int)

  val allScenarios: Seq[String] = Seq(
    "pipe-pingpong",
    "pipe-concurrent",
    "pipe-stream",
    "tcp-pingpong",
    "tcp-concurrent",
    "tcp-stream",
    "tcp-connect",
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
    var i = 0
    while i < args.length do
      args(i) match
        case "--workers"     => workers = args(i + 1).toInt
        case "--policy"      => policy = args(i + 1)
        case "--scenarios"   => scenarios = args(i + 1).split(',').toSeq
        case "--scale"       => scale = args(i + 1).toDouble
        case "--connections" => connections = args(i + 1).toInt
        case "--repeat"      => repeat = args(i + 1).toInt
        case other           => throw new IllegalArgumentException(other)
      i += 2
    (workers, policy, Args(scenarios, scale, connections, repeat))
  }

  private inline val MessageSize = 64
  private inline val Chunk = 64 * 1024
  private inline val Block = 4096

  private val unique = new java.util.concurrent.atomic.AtomicInteger(0)
  private val tmp = s"/tmp/gcb-${System.nanoTime() % 100000000L}"
  private def path(kind: String): String = {
    new java.io.File(tmp).mkdirs()
    s"$tmp/$kind-${unique.incrementAndGet()}"
  }
  private def remove(path: String): Unit = { val _ = new java.io.File(path).delete() }

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
      if System.getenv("GEARS_TRACE") != null then
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

  private def mkfifo(path: String): Unit =
    if new ProcessBuilder("mkfifo", path).start().waitFor() != 0 then throw new java.io.IOException(s"mkfifo $path")

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
    java.nio.file.Files.write(java.nio.file.Paths.get(at), new Array[Byte](Block * blocks))
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

  /** Runs the scenarios `args` name, in order, `args.repeat` times over. */
  def run(io: Io[All], args: Args)(using Async.Spawn): Unit = for _ <- 1 to args.repeat do once(io, args)

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
    val _ = new java.io.File(tmp).delete()
  }
}
