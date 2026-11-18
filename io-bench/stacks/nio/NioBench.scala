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

  private val allScenarios =
    Seq("tcp-pingpong", "tcp-concurrent", "tcp-stream", "tcp-connect", "udp-pingpong", "udp-concurrent", "udp-connected")

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
        case _                => () // not one this stack does
      }
    selector.close()
  }
}
