//> using scala 3.7.4
//> using jvm system
//> using dep io.netty:netty-codec-base:4.2.21.Final

import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.buffer.{ByteBuf, ByteBufUtil, Unpooled}
import io.netty.channel.*
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.{ChannelInputShutdownEvent, DatagramPacket, DuplexChannel, SocketProtocolFamily}
import io.netty.channel.socket.nio.{NioDatagramChannel, NioServerSocketChannel, NioSocketChannel}
import io.netty.handler.codec.FixedLengthFrameDecoder
import io.netty.util.ReferenceCountUtil

import java.net.{InetAddress, InetSocketAddress}
import java.nio.channels.spi.SelectorProvider
import java.util.concurrent.{CountDownLatch, LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import Cache.*

/** The IP socket scenarios of `CapBench` over Netty's NIO transport: see `../README.md`.
  *
  * One event loop group runs everything, servers and clients. The main thread only sets a scenario up, begins it and
  * waits for it on a latch.
  */
object NettyBench {
  private inline val MessageSize = 64
  private inline val Chunk = 64 * 1024

  private def fail(cause: Throwable): Unit = {
    cause.printStackTrace()
    Runtime.getRuntime().halt(1)
  }

  /** Fired through a client's pipeline to begin its work. */
  private object Start

  private abstract class Handler extends ChannelInboundHandlerAdapter {
    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = fail(cause)
  }

  private def listener(f: ChannelFuture => Unit): ChannelFutureListener = new ChannelFutureListener {
    def operationComplete(future: ChannelFuture): Unit = if future.isSuccess() then f(future) else fail(future.cause())
  }

  // ---- TCP ping-pong: a frame decoder ahead of each of these makes whole messages of what is read

  /** Writes each whole message back. `accepted` is told of the connection once it is active. */
  private final class Echo(accepted: LinkedBlockingQueue[Channel]) extends Handler {
    override def channelActive(ctx: ChannelHandlerContext): Unit = {
      accepted.add(ctx.channel())
      ctx.fireChannelActive()
    }
    override def channelRead(ctx: ChannelHandlerContext, message: AnyRef): Unit = {
      val _ = ctx.write(message, ctx.voidPromise())
    }
    override def channelReadComplete(ctx: ChannelHandlerContext): Unit = { val _ = ctx.flush() }
  }

  /** Writes a message, and the next when a whole one has come back, `rounds` times over. */
  private final class Ask(rounds: Int, done: CountDownLatch) extends Handler {
    private var message: ByteBuf = null
    private var left = rounds

    override def handlerAdded(ctx: ChannelHandlerContext): Unit =
      message = ctx.alloc().directBuffer(MessageSize).writeZero(MessageSize)
    override def handlerRemoved(ctx: ChannelHandlerContext): Unit = { val _ = message.release() }

    override def userEventTriggered(ctx: ChannelHandlerContext, event: AnyRef): Unit =
      if event eq Start then { val _ = ctx.writeAndFlush(message.retainedDuplicate(), ctx.voidPromise()) }
      else ctx.fireUserEventTriggered(event)

    override def channelRead(ctx: ChannelHandlerContext, reply: AnyRef): Unit = {
      ReferenceCountUtil.release(reply)
      left -= 1
      if left == 0 then done.countDown()
      else { val _ = ctx.write(message.retainedDuplicate(), ctx.voidPromise()) }
    }
    override def channelReadComplete(ctx: ChannelHandlerContext): Unit = { val _ = ctx.flush() }
  }

  // ---- TCP stream

  /** Writes `chunks` chunks, each when the write before it has completed, and shuts down the output. */
  private final class StreamOut(chunks: Int, done: CountDownLatch) extends Handler {
    private var chunk: ByteBuf = null
    private var left = chunks
    private val written = listener(future => next(future.channel()))

    override def handlerAdded(ctx: ChannelHandlerContext): Unit = chunk = ctx.alloc().directBuffer(Chunk).writeZero(Chunk)
    override def handlerRemoved(ctx: ChannelHandlerContext): Unit = { val _ = chunk.release() }

    override def userEventTriggered(ctx: ChannelHandlerContext, event: AnyRef): Unit =
      if event eq Start then next(ctx.channel()) else ctx.fireUserEventTriggered(event)

    private def next(channel: Channel): Unit =
      if left == 0 then
        val _ = channel.asInstanceOf[DuplexChannel].shutdownOutput().addListener(listener(_ => done.countDown()))
      else
        left -= 1
        val _ = channel.writeAndFlush(chunk.retainedDuplicate()).addListener(written)
  }

  /** Drops what it reads, until the end of the stream. */
  private final class StreamIn(accepted: LinkedBlockingQueue[Channel], done: CountDownLatch) extends Handler {
    override def channelActive(ctx: ChannelHandlerContext): Unit = {
      accepted.add(ctx.channel())
      ctx.fireChannelActive()
    }
    override def channelRead(ctx: ChannelHandlerContext, bytes: AnyRef): Unit = { val _ = ReferenceCountUtil.release(bytes) }
    override def userEventTriggered(ctx: ChannelHandlerContext, event: AnyRef): Unit =
      if event eq ChannelInputShutdownEvent.INSTANCE then done.countDown() else ctx.fireUserEventTriggered(event)
  }

  // ---- TCP connect

  @ChannelHandler.Sharable
  private final class CloseAtOnce extends Handler {
    override def channelActive(ctx: ChannelHandlerContext): Unit = { val _ = ctx.close() }
  }

  @ChannelHandler.Sharable
  private final class Nothing extends Handler

  // ---- UDP: with a `peer` the sockets are not connected and each packet is addressed; with none they are connected

  private final class DatagramEcho extends Handler {
    @volatile var peer: InetSocketAddress = null

    override def channelRead(ctx: ChannelHandlerContext, message: AnyRef): Unit = {
      // The packet only holds its content, which goes on to the write.
      val content = message.asInstanceOf[DatagramPacket].content()
      val to = peer
      val _ = ctx.write(if to == null then content else new DatagramPacket(content, to), ctx.voidPromise())
    }
    override def channelReadComplete(ctx: ChannelHandlerContext): Unit = { val _ = ctx.flush() }
  }

  private final class DatagramAsk(rounds: Int, done: CountDownLatch) extends Handler {
    @volatile var peer: InetSocketAddress = null
    private var message: ByteBuf = null
    private var left = rounds

    override def handlerAdded(ctx: ChannelHandlerContext): Unit =
      message = ctx.alloc().directBuffer(MessageSize).writeZero(MessageSize)
    override def handlerRemoved(ctx: ChannelHandlerContext): Unit = { val _ = message.release() }

    private def send(ctx: ChannelHandlerContext): Unit = {
      val content = message.retainedDuplicate()
      val to = peer
      val _ = ctx.write(if to == null then content else new DatagramPacket(content, to), ctx.voidPromise())
    }

    override def userEventTriggered(ctx: ChannelHandlerContext, event: AnyRef): Unit =
      if event eq Start then { send(ctx); val _ = ctx.flush() }
      else ctx.fireUserEventTriggered(event)

    override def channelRead(ctx: ChannelHandlerContext, reply: AnyRef): Unit = {
      ReferenceCountUtil.release(reply)
      left -= 1
      if left == 0 then done.countDown() else send(ctx)
    }
    override def channelReadComplete(ctx: ChannelHandlerContext): Unit = { val _ = ctx.flush() }
  }

  // ---- the session cache: see `Cache.scala`, whose work is done on arrays

  /** Gathers what is read into an array and, when the reads of one turn of the loop are done, finds the whole RESP
    * values there: `value` for each, then `after`. The beginning of a value the rest of which has yet to come is kept
    * for the next turn.
    */
  private abstract class Values extends Handler {
    private var bytes = new Array[Byte](LargestBatch)
    private var have = 0

    /** The whole value of `in` from `from` to `end`. */
    protected def value(ctx: ChannelHandlerContext, in: Array[Byte], from: Int, end: Int): Unit

    /** Every whole value that has come has been given to `value`. */
    protected def after(ctx: ChannelHandlerContext): Unit

    override def channelRead(ctx: ChannelHandlerContext, message: AnyRef): Unit = {
      val read = message.asInstanceOf[ByteBuf]
      try
        val length = read.readableBytes()
        if have + length > bytes.length then
          bytes = java.util.Arrays.copyOf(bytes, math.max(bytes.length * 2, have + length))
        read.readBytes(bytes, have, length)
        have += length
      finally { val _ = read.release() }
    }

    override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {
      var at = 0
      var end = Resp.end(bytes, at, have)
      while end >= 0 do
        value(ctx, bytes, at, end)
        at = end
        end = Resp.end(bytes, at, have)
      if at > 0 then
        System.arraycopy(bytes, at, bytes, 0, have - at)
        have -= at
      after(ctx)
    }
  }

  /** `length` bytes of `from`, in a buffer of the channel's allocator, for a write. */
  private def copied(ctx: ChannelHandlerContext, from: Array[Byte], length: Int): ByteBuf =
    ctx.alloc().buffer(length).writeBytes(from, 0, length)

  /** The cache for one connection. `together`, every command that came together is answered in one buffer and one
    * write; otherwise each is answered in a write of its own. Either way there is one flush.
    */
  private final class CacheServing(accepted: LinkedBlockingQueue[Channel], together: Boolean) extends Values {
    private val cache = new CacheServer
    private val responses = new Array[Byte](if together then LargestBatch else LargestValue)
    private var written = 0
    private var wrote = false

    override def channelActive(ctx: ChannelHandlerContext): Unit = {
      accepted.add(ctx.channel())
      ctx.fireChannelActive()
    }

    protected def value(ctx: ChannelHandlerContext, in: Array[Byte], from: Int, end: Int): Unit =
      if together then written = cache.handle(in, from, responses, written)
      else
        wrote = true
        val _ = ctx.write(copied(ctx, responses, cache.handle(in, from, responses, 0)), ctx.voidPromise())

    protected def after(ctx: ChannelHandlerContext): Unit =
      if written > 0 then
        val _ = ctx.writeAndFlush(copied(ctx, responses, written), ctx.voidPromise())
        written = 0
      else if wrote then
        wrote = false
        val _ = ctx.flush()
  }

  /** Answers that are the same bytes every time: written as duplicates, which share them. */
  private def constant(text: String): ByteBuf = {
    val bytes = Resp.bytes(text)
    Unpooled.unreleasableBuffer(Unpooled.directBuffer(bytes.length).writeBytes(bytes))
  }
  private val Ok = constant("+OK\r\n")
  private val NoValue = constant("$-1\r\n")
  private val LineEnd = constant("\r\n")
  private val Unknown = constant("-ERR unknown command\r\n")

  /** The cache as `tcp-realistic-gather` keeps it: a value is a direct buffer of its own, and an answer is written as
    * its parts, a line for its length, the value's own buffer (a duplicate, sharing its bytes) and a line's end. The
    * parts of every answer that came together are flushed together, and the channel writes them with one gathering
    * write.
    */
  private final class GatheringCacheServing(accepted: LinkedBlockingQueue[Channel]) extends Values {
    private val values = new java.util.HashMap[String, ByteBuf]()
    private val counts = new java.util.HashMap[String, Integer]()
    private var wrote = false

    override def channelActive(ctx: ChannelHandlerContext): Unit = {
      accepted.add(ctx.channel())
      ctx.fireChannelActive()
    }

    // A write still to be done holds its value itself.
    override def handlerRemoved(ctx: ChannelHandlerContext): Unit = {
      values.values().forEach(value => { val _ = value.release() })
      values.clear()
    }

    /** `sign`, `number` in decimal and a line's end. */
    private def line(ctx: ChannelHandlerContext, sign: Char, number: Int): ByteBuf = {
      val buffer = ctx.alloc().buffer(16).writeByte(sign)
      ByteBufUtil.writeAscii(buffer, Integer.toString(number))
      buffer.writeByte('\r').writeByte('\n')
    }

    private def write(ctx: ChannelHandlerContext, part: ByteBuf): Unit = { val _ = ctx.write(part, ctx.voidPromise()) }

    protected def value(ctx: ChannelHandlerContext, request: Array[Byte], from: Int, end: Int): Unit = {
      wrote = true
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
      if nameLength == 3 && request(name) == 'S' && parts == 3 then
        val length = Resp.number(request, next + 1)
        val valueAt = Resp.lineEnd(request, next, until)
        val before = values.put(key, ctx.alloc().directBuffer(length).writeBytes(request, valueAt, length))
        if before != null then { val _ = before.release() }
        write(ctx, Ok.duplicate())
      else if nameLength == 3 && request(name) == 'G' then
        val value = values.get(key)
        if value == null then write(ctx, NoValue.duplicate())
        else
          write(ctx, line(ctx, '$', value.readableBytes()))
          write(ctx, value.retainedDuplicate())
          write(ctx, LineEnd.duplicate())
      else if nameLength == 4 && request(name) == 'I' then
        val held = counts.get(key)
        val now = (if held == null then 0 else held.intValue) + 1
        counts.put(key, now)
        write(ctx, line(ctx, ':', now))
      else write(ctx, Unknown.duplicate())
    }

    protected def after(ctx: ChannelHandlerContext): Unit =
      if wrote then
        wrote = false
        val _ = ctx.flush()
  }

  /** The client `id`: sends its requests `batch` at once, in one write, checks their answers as they come, and sends
    * the next when all of them have, until `rounds` requests are answered.
    */
  private final class CacheAsk(id: Int, rounds: Int, batch: Int, done: CountDownLatch) extends Values {
    private val user = new CacheClient(id)
    private val requests = new Array[Byte](batch * LargestValue)
    private val answers = new Array[Array[Byte]](batch)
    private var i = 0
    private var sent = 0
    private var checked = 0

    private def send(ctx: ChannelHandlerContext): Unit = {
      sent = math.min(batch, rounds - i)
      checked = 0
      var length = 0
      var k = 0
      while k < sent do
        length = user.request(i + k, requests, length)
        answers(k) = user.answer
        k += 1
      val _ = ctx.writeAndFlush(copied(ctx, requests, length), ctx.voidPromise())
    }

    override def userEventTriggered(ctx: ChannelHandlerContext, event: AnyRef): Unit =
      if event eq Start then send(ctx) else ctx.fireUserEventTriggered(event)

    protected def value(ctx: ChannelHandlerContext, in: Array[Byte], from: Int, end: Int): Unit = {
      if checked == sent then throw new IllegalStateException(s"client $id was answered more often than it asked")
      user.check(i + checked, answers(checked), in, from, end)
      checked += 1
    }

    protected def after(ctx: ChannelHandlerContext): Unit =
      if sent > 0 && checked == sent then
        i += sent
        sent = 0
        if i == rounds then done.countDown() else send(ctx)
  }

  // ---- setting up

  private var group: EventLoopGroup = null

  private val loopback = InetAddress.getByAddress(Array[Byte](127, 0, 0, 1))
  private def anyPort = new InetSocketAddress(loopback, 0)

  // IPv4 sockets, as the addresses are, where the JDK's default would be IPv6 sockets taking IPv4 too.
  private val serverSockets: ChannelFactory[NioServerSocketChannel] =
    () => new NioServerSocketChannel(SelectorProvider.provider(), SocketProtocolFamily.INET)
  private val sockets: ChannelFactory[NioSocketChannel] =
    () => new NioSocketChannel(SelectorProvider.provider(), SocketProtocolFamily.INET)
  private val datagrams: ChannelFactory[NioDatagramChannel] =
    () => new NioDatagramChannel(SocketProtocolFamily.INET)

  private def initializer[C <: Channel](init: C => Unit): ChannelInitializer[C] = new ChannelInitializer[C] {
    override def initChannel(channel: C): Unit = init(channel)
  }

  private def server(child: ChannelHandler): ServerBootstrap =
    new ServerBootstrap()
      .group(group)
      .channelFactory(serverSockets)
      .childOption(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
      .childHandler(child)

  private def client(handler: ChannelHandler): Bootstrap =
    new Bootstrap()
      .group(group)
      .channelFactory(sockets)
      .option(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
      .handler(handler)

  private def bindDatagram(handler: ChannelHandler): ChannelFuture =
    new Bootstrap().group(group).channelFactory(datagrams).handler(handler).bind(anyPort)

  private def address(channel: Channel): InetSocketAddress = channel.localAddress().asInstanceOf[InetSocketAddress]

  private def start(channel: Channel): Unit = { val _ = channel.pipeline().fireUserEventTriggered(Start) }

  private def close(channels: Seq[Channel]): Unit = channels.map(_.close()).foreach(_.sync())

  // ---- the scenarios

  private def timed(name: String, operations: Long)(body: => Unit): Unit = {
    val start = System.nanoTime()
    body
    val nanos = System.nanoTime() - start
    println(f"bench: $name%-20s $operations%9d ops ${nanos / 1e9}%8.3f s ${nanos / 1e3 / operations}%9.3f us/op")
  }

  private def tcpEcho(name: String, connections: Int, rounds: Int): Unit = {
    val accepted = new LinkedBlockingQueue[Channel]()
    val done = new CountDownLatch(connections)
    val listening = server(initializer[Channel] { channel =>
      val _ = channel.pipeline().addLast(new FixedLengthFrameDecoder(MessageSize), new Echo(accepted))
    }).bind(anyPort).sync().channel()
    val connecting = client(initializer[Channel] { channel =>
      val _ = channel.pipeline().addLast(new FixedLengthFrameDecoder(MessageSize), new Ask(rounds, done))
    })
    val pairs = Seq.fill(connections) {
      val connected = connecting.connect(address(listening)).sync().channel()
      (connected, accepted.take())
    }
    timed(name, rounds.toLong * connections) {
      pairs.foreach((asking, _) => start(asking))
      done.await()
    }
    close(pairs.flatMap((asking, echoing) => Seq(asking, echoing)) :+ listening)
  }

  private def tcpRealistic(name: String, connections: Int, rounds: Int, batch: Int)(
      serving: LinkedBlockingQueue[Channel] => ChannelHandler
  ): Unit = {
    val accepted = new LinkedBlockingQueue[Channel]()
    val done = new CountDownLatch(connections)
    val ids = new AtomicInteger()
    val listening = server(initializer[Channel] { channel =>
      val _ = channel.pipeline().addLast(serving(accepted))
    }).bind(anyPort).sync().channel()
    val connecting = client(initializer[Channel] { channel =>
      val _ = channel.pipeline().addLast(new CacheAsk(ids.getAndIncrement(), rounds, batch, done))
    })
    val pairs = Seq.fill(connections) {
      val connected = connecting.connect(address(listening)).sync().channel()
      (connected, accepted.take())
    }
    timed(name, rounds.toLong * connections) {
      pairs.foreach((asking, _) => start(asking))
      done.await()
    }
    close(pairs.flatMap((asking, answering) => Seq(asking, answering)) :+ listening)
  }

  /** The work of `tcp-realistic` with nothing between the two sides. */
  private def workRealistic(name: String, connections: Int, rounds: Int): Unit =
    timed(name, rounds.toLong * connections) {
      val request = new Array[Byte](LargestValue)
      val response = new Array[Byte](LargestValue)
      var id = 0
      while id < connections do
        val cache = new CacheServer
        val user = new CacheClient(id)
        var i = 0
        while i < rounds do
          val _ = user.request(i, request, 0)
          user.check(i, user.answer, response, 0, cache.handle(request, 0, response, 0))
          i += 1
        id += 1
    }

  private def tcpStream(chunks: Int): Unit = {
    val accepted = new LinkedBlockingQueue[Channel]()
    val done = new CountDownLatch(2)
    val listening = server(initializer[Channel] { channel =>
      val _ = channel.pipeline().addLast(new StreamIn(accepted, done))
    })
      // The end of the stream is an event, and not the closing of the connection.
      .childOption(ChannelOption.ALLOW_HALF_CLOSURE, java.lang.Boolean.TRUE)
      // Reads into a buffer of one chunk, where the default would grow to that from less.
      .childOption(ChannelOption.RECVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(Chunk))
      .bind(anyPort)
      .sync()
      .channel()
    val writing = client(new StreamOut(chunks, done)).connect(address(listening)).sync().channel()
    val reading = accepted.take()
    timed("tcp-stream", chunks) {
      start(writing)
      done.await()
    }
    close(Seq(writing, reading, listening))
  }

  private def tcpConnect(count: Int): Unit = {
    val done = new CountDownLatch(1)
    val listening = server(new CloseAtOnce).bind(anyPort).sync().channel()
    val connecting = client(new Nothing)
    val to = address(listening)
    // A connection with nothing to read and its end met is closed by Netty: the next begins when it has closed.
    def connect(left: Int): Unit = {
      val _ = connecting
        .connect(to)
        .addListener(listener { connected =>
          val _ = connected
            .channel()
            .closeFuture()
            .addListener(listener(_ => if left == 1 then done.countDown() else connect(left - 1)))
        })
    }
    timed("tcp-connect", count) {
      connect(count)
      done.await()
    }
    close(Seq(listening))
  }

  /** The sockets are bound (and connected, with `connect`) within the time, as `CapBench` has it; they are closed
    * outside it.
    */
  private def udpEcho(name: String, pairs: Int, rounds: Int, connect: Boolean): Unit = {
    val done = new CountDownLatch(pairs)
    var channels = Seq.empty[Channel]
    timed(name, rounds.toLong * pairs) {
      val binding = Seq.fill(pairs) {
        val echo = new DatagramEcho
        val ask = new DatagramAsk(rounds, done)
        (echo, bindDatagram(echo), ask, bindDatagram(ask))
      }
      val bound = binding.map { (echo, echoBinding, ask, askBinding) =>
        val echoing = echoBinding.sync().channel()
        val asking = askBinding.sync().channel()
        if connect then
          val connected = Seq(echoing.connect(address(asking)), asking.connect(address(echoing)))
          connected.foreach(_.sync())
        else
          echo.peer = address(asking)
          ask.peer = address(echoing)
        (echoing, asking)
      }
      channels = bound.flatMap((echoing, asking) => Seq(echoing, asking))
      bound.foreach((_, asking) => start(asking))
      done.await()
    }
    close(channels)
  }

  private val allScenarios =
    Seq(
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
    var workers = 1
    var scenarios = allScenarios
    var scale = 1.0
    var connections = 64
    var repeat = 1
    var i = 0
    while i < args.length do
      args(i) match
        case "--workers"     => workers = args(i + 1).toInt
        case "--scenarios"   => scenarios = args(i + 1).split(',').toSeq
        case "--scale"       => scale = args(i + 1).toDouble
        case "--connections" => connections = args(i + 1).toInt
        case "--repeat"      => repeat = args(i + 1).toInt
        case other           => throw new IllegalArgumentException(other)
      i += 2

    def n(x: Int): Int = math.max(1, (x * scale).toInt)

    group = new MultiThreadIoEventLoopGroup(workers, NioIoHandler.newFactory())
    try
      for _ <- 1 to repeat do
        scenarios.foreach {
          case "tcp-pingpong"   => tcpEcho("tcp-pingpong", 1, n(100_000))
          case "tcp-concurrent" => tcpEcho("tcp-concurrent", connections, n(5_000))
          case "tcp-stream"     => tcpStream(n(20_000))
          case "tcp-connect"    => tcpConnect(n(1_000))
          case "udp-pingpong"   => udpEcho("udp-pingpong", 1, n(100_000), connect = false)
          case "udp-concurrent" => udpEcho("udp-concurrent", connections, n(5_000), connect = false)
          case "udp-connected"  => udpEcho("udp-connected", 1, n(100_000), connect = true)
          case "tcp-realistic" =>
            tcpRealistic("tcp-realistic", connections, n(2_000), 1)(new CacheServing(_, together = false))
          case "tcp-realistic-bulk" =>
            tcpRealistic("tcp-realistic-bulk", connections, n(2_000), Batch)(new CacheServing(_, together = true))
          case "tcp-realistic-gather" =>
            tcpRealistic("tcp-realistic-gather", connections, n(2_000), Batch)(new GatheringCacheServing(_))
          case "work-realistic" => workRealistic("work-realistic", connections, n(2_000))
          case _                => () // not one this stack does
        }
    finally
      val _ = group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync()
  }
}
