package gearsbench

import java.net.SocketAddress
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.LockSupport

/** How `JdkBench` does its I/O on virtual threads: the JDK's blocking channels, or non-blocking ones and a selector. */
trait Io {
  def connect(address: SocketAddress): SocketChannel
  def accept(server: ServerSocketChannel): SocketChannel

  /** The bytes read, or -1 at end of stream; waits until there are some. */
  def read(channel: SocketChannel, buf: ByteBuffer): Int

  /** Writes at least one byte of `buf`, waiting for room. */
  def write(channel: SocketChannel, buf: ByteBuffer): Unit

  /** Closes `channel`, and whatever this keeps for it. */
  def close(channel: SocketChannel): Unit = channel.close()
}

/** Blocking channels: a virtual thread blocked on one parks on the JDK's own poller. */
object BlockingIo extends Io {
  def connect(address: SocketAddress): SocketChannel = {
    val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
    channel.connect(address)
    channel
  }
  def accept(server: ServerSocketChannel): SocketChannel = server.accept()
  def read(channel: SocketChannel, buf: ByteBuffer): Int = channel.read(buf)
  def write(channel: SocketChannel, buf: ByteBuffer): Unit = channel.write(buf): Unit
}

/** The least a `Selector` design can do with virtual threads, to measure the API's own cost: non-blocking channels,
  * each operation tried on the calling virtual thread first; one that would block hands a registration to the
  * selector's own thread and parks, and that thread unparks it once the channel is ready. No futures, completions or
  * reactor: what any design on `Selector` pays at least, as `NioReactor` on a reactor thread beside the futures would.
  * The selector is woken only when its thread is blocked in it, or about to be.
  */
final class SelectorIo extends Io {
  private val selector = Selector.open()

  /** A virtual thread waiting for `ops` on `channel`. */
  private final class Wait(val channel: SelectableChannel, val ops: Int, val thread: Thread) {
    @volatile var ready = false
  }

  /** The waits on one channel's key, at most one for each direction. */
  private final class Waiters {
    var read: Wait | Null = null
    var write: Wait | Null = null
  }

  private val requests = new ConcurrentLinkedQueue[Wait]()
  @volatile private var parked = false

  private val loop = Thread.ofPlatform().daemon().name("selector").start(() => run())

  private def run(): Unit =
    while true do {
      var wait = requests.poll()
      while wait != null do {
        register(wait)
        wait = requests.poll()
      }
      parked = true
      if requests.isEmpty then selector.select(key => dispatch(key)) else selector.selectNow(key => dispatch(key))
      parked = false
    }

  /** Wakes a waiter whose channel was closed meanwhile, so that it tries again and meets the failure itself. */
  private def release(wait: Wait): Unit = {
    wait.ready = true
    LockSupport.unpark(wait.thread)
  }

  private def register(wait: Wait): Unit =
    try {
      var key = wait.channel.keyFor(selector)
      if key == null then key = wait.channel.register(selector, 0, new Waiters)
      val waiters = key.attachment().asInstanceOf[Waiters]
      if (wait.ops & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 then waiters.read = wait
      else waiters.write = wait
      key.interestOps(key.interestOps() | wait.ops)
    } catch
      case _: (java.nio.channels.CancelledKeyException | java.nio.channels.ClosedChannelException) => release(wait)

  private def dispatch(key: SelectionKey): Unit =
    try ready(key)
    catch case _: java.nio.channels.CancelledKeyException => () // closed after it was selected; its waiters retried

  private def ready(key: SelectionKey): Unit = {
    val waiters = key.attachment().asInstanceOf[Waiters]
    val ready = key.readyOps()
    var interest = key.interestOps()
    val reader = waiters.read
    if reader != null && (ready & reader.ops) != 0 then {
      waiters.read = null
      interest &= ~reader.ops
      reader.ready = true
      LockSupport.unpark(reader.thread)
    }
    val writer = waiters.write
    if writer != null && (ready & writer.ops) != 0 then {
      waiters.write = null
      interest &= ~writer.ops
      writer.ready = true
      LockSupport.unpark(writer.thread)
    }
    key.interestOps(interest)
  }

  /** Parks the caller until `channel` is ready for `ops`. */
  private def waitUntilReady(channel: SelectableChannel, ops: Int): Unit = {
    val wait = new Wait(channel, ops, Thread.currentThread())
    requests.add(wait)
    if parked then selector.wakeup()
    while !wait.ready do LockSupport.park(this)
  }

  def connect(address: SocketAddress): SocketChannel = {
    val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
    channel.configureBlocking(false)
    if !channel.connect(address) then {
      while !channel.finishConnect() do waitUntilReady(channel, SelectionKey.OP_CONNECT)
    }
    channel
  }

  def accept(server: ServerSocketChannel): SocketChannel = {
    server.configureBlocking(false)
    var client = server.accept()
    while client == null do {
      waitUntilReady(server, SelectionKey.OP_ACCEPT)
      client = server.accept()
    }
    client.configureBlocking(false)
    client
  }

  def read(channel: SocketChannel, buf: ByteBuffer): Int = {
    var n = channel.read(buf)
    while n == 0 do {
      waitUntilReady(channel, SelectionKey.OP_READ)
      n = channel.read(buf)
    }
    n
  }

  def write(channel: SocketChannel, buf: ByteBuffer): Unit =
    while channel.write(buf) == 0 && buf.hasRemaining do waitUntilReady(channel, SelectionKey.OP_WRITE)
}

/** A `Selector` design with no selector thread: non-blocking channels, each operation tried on the calling virtual
  * thread first; one that would block waits on a selector of the channel's own, one for each direction, in which the
  * waiting virtual thread itself selects, so registering a wait wakes no other thread. A wait could be cancelled with
  * `Selector.wakeup`, leaving the channel open. Measures what that costs over the JDK's blocking channels, which park
  * on the JDK's poller directly.
  */
object SelectorHereIo extends Io {

  /** A channel's selectors, made the first time it waits in each direction, with the channel registered for it. */
  private final class Selectors(channel: SelectableChannel) {
    private var reading: Selector | Null = null
    private var writing: Selector | Null = null

    def forOps(ops: Int): Selector =
      if (ops & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 then {
        if reading == null then reading = opened(ops)
        reading.nn
      } else {
        if writing == null then writing = opened(ops)
        writing.nn
      }

    private def opened(ops: Int): Selector = {
      val selector = Selector.open()
      channel.register(selector, ops)
      selector
    }

    def close(): Unit = {
      if reading != null then reading.nn.close()
      if writing != null then writing.nn.close()
    }
  }

  // Keyed by channel: what a backend would keep on the handle's entry.
  private val selectors = new ConcurrentHashMap[SelectableChannel, Selectors]()

  /** Waits on the caller until `channel` is ready for `ops`. One thread waits in each direction at a time. */
  private def waitUntilReady(channel: SelectableChannel, ops: Int): Unit = {
    val waiting = selectors.computeIfAbsent(channel, new Selectors(_))
    // The `Selectors` of a channel are used by one reader and one writer, each on its own selector.
    val selector = waiting.synchronized(waiting.forOps(ops))
    selector.select(_ => ()): Unit
  }

  def connect(address: SocketAddress): SocketChannel = {
    val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
    channel.configureBlocking(false)
    if !channel.connect(address) then {
      while !channel.finishConnect() do waitUntilReady(channel, SelectionKey.OP_CONNECT)
    }
    channel
  }

  def accept(server: ServerSocketChannel): SocketChannel = {
    server.configureBlocking(false)
    var client = server.accept()
    while client == null do {
      waitUntilReady(server, SelectionKey.OP_ACCEPT)
      client = server.accept()
    }
    client.configureBlocking(false)
    client
  }

  /** Reads that would block, each followed by `Thread.yield`, before waiting on the selector
    * (`-Dgears.selectorHere.yields`): other virtual threads run meanwhile, and the data may arrive, so most reads never
    * wait, each wait costing two `kevent`s on the channel's selector and a registration with the JDK's poller. With
    * nothing else to run, a yield returns at once, so this spins, briefly: 32, where the bench scenarios level off.
    */
  private val Yields = Integer.getInteger("gears.selectorHere.yields", 32).intValue

  def read(channel: SocketChannel, buf: ByteBuffer): Int = {
    var n = channel.read(buf)
    var yields = Yields
    while n == 0 do {
      if yields > 0 then {
        yields -= 1
        Thread.`yield`()
      } else waitUntilReady(channel, SelectionKey.OP_READ)
      n = channel.read(buf)
    }
    n
  }

  def write(channel: SocketChannel, buf: ByteBuffer): Unit =
    while channel.write(buf) == 0 && buf.hasRemaining do waitUntilReady(channel, SelectionKey.OP_WRITE)

  override def close(channel: SocketChannel): Unit = {
    val kept = selectors.remove(channel)
    try channel.close()
    finally if kept != null then kept.close()
  }
}
