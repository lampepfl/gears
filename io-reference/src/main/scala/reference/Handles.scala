package gears.async.io
package reference

import java.nio.channels.Channel
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel
import java.nio.channels.FileChannel
import java.nio.channels.GatheringByteChannel
import java.nio.channels.Pipe
import java.nio.channels.ReadableByteChannel
import java.nio.channels.ScatteringByteChannel
import java.nio.channels.SelectableChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** The failure of an op on a closed handle, naming it: a closed channel's, as `Ops.close` has it. */
final class HandleClosedException(handle: AnyRef) extends ClosedChannelException {
  override def getMessage: String = s"$handle is closed"
}

/** A handle of the reference backend: an I/O object of the JDK's, and the ops in progress on it, which a close fails
  * and waits for before it closes the object, so that no op uses it once closed.
  */
sealed abstract class RefHandle(kind: String) {
  private val id = RefHandle.ids.incrementAndGet()
  private var closing = false
  private var active = 0
  private val waits = scala.collection.mutable.Set.empty[Wait]
  private var closer: Wait | Null = null

  /** The I/O object, closed by `close`. */
  protected def channel: Channel

  /** Takes up an op performed on this handle, whose `wait` a close wakes; fails if the handle is closed. */
  private[reference] def enter(wait: Wait): Unit = synchronized {
    if closing then throw new HandleClosedException(this)
    active += 1
    waits += wait
  }

  /** Lets go of an op taken up by `enter`. */
  private[reference] def leave(wait: Wait): Unit = {
    val last = synchronized {
      active -= 1
      waits -= wait
      if active == 0 then closer else null
    }
    if last != null then last.signal()
  }

  /** Throws the failure of an op on this handle if it has been closed. */
  private[reference] def checkOpen(): Unit = if synchronized(closing) then throw new HandleClosedException(this)

  /** Closes this handle: fails the ops in progress on it, waits for them to stop, then closes its I/O object. Fails if
    * it was closed already.
    */
  private[reference] def close(wait: Wait): Unit = {
    val woken = synchronized {
      if closing then throw new HandleClosedException(this)
      closing = true
      closer = wait
      waits.toList
    }
    woken.foreach(_.signal())
    while synchronized(active > 0) do {
      wait.reset()
      if synchronized(active > 0) then wait.parkUntilSignalled()
    }
    channel match {
      case selectable: SelectableChannel => Poller.close(selectable, wait)
      case other                         => other.close()
    }
    closed()
  }

  /** What else closing does once the I/O object is closed. */
  protected def closed(): Unit = ()

  /** One read at a time on a stream, and one write. */
  private val reading = new AtomicBoolean()
  private val writing = new AtomicBoolean()

  /** Runs `body` as the stream's one read, or write if `write`; fails if another is in progress. */
  private[reference] def exclusively[T](write: Boolean, op: AnyRef)(body: => T): T = {
    val flag = if write then writing else reading
    if !flag.compareAndSet(false, true) then
      throw new IllegalArgumentException(s"An op reading, or writing, as $op does, is already pending on its handle")
    try body
    finally flag.set(false)
  }

  override def toString: String = s"$kind #$id"
}

object RefHandle {
  private val ids = new AtomicLong()
}

/** A handle read as a stream. */
sealed trait ReadEnd extends RefHandle {
  private[reference] def in: ReadableByteChannel & ScatteringByteChannel

  /** What the poller waits on for it to be readable, or null if it always is. */
  private[reference] def inReady: SelectableChannel | Null
}

/** A handle written as a stream. */
sealed trait WriteEnd extends RefHandle {
  private[reference] def out: WritableByteChannel & GatheringByteChannel

  /** What the poller waits on for it to be writable, or null if it always is. */
  private[reference] def outReady: SelectableChannel | Null
}

/** A connected stream socket, IP or Unix. */
final class ConnectionHandle(private[reference] val socket: SocketChannel, private[reference] val unix: Boolean)
    extends RefHandle(if unix then "Unix connection" else "connection")
    with ReadEnd
    with WriteEnd {
  protected def channel: Channel = socket
  private[reference] def in = socket
  private[reference] def inReady = socket
  private[reference] def out = socket
  private[reference] def outReady = socket
}

/** A listening stream socket, IP or Unix, with the options it gives the connections it accepts, and for a Unix one the
  * socket file it bound, which it removes as it closes if the file there is still its own.
  */
final class ListenerHandle(
    private[reference] val server: ServerSocketChannel,
    private[reference] val unix: Boolean,
    private[reference] val options: SocketOptions,
    path: Path | Null
) extends RefHandle(if unix then "Unix listener" else "listener") {
  private val fileKey: AnyRef | Null = if path == null then null else ListenerHandle.fileKey(path)
  protected def channel: Channel = server
  private[reference] val accepts = new Turns

  override protected def closed(): Unit =
    if path != null && fileKey != null && ListenerHandle.fileKey(path) == fileKey then Files.deleteIfExists(path): Unit
}

object ListenerHandle {
  private[reference] def fileKey(path: Path): AnyRef | Null =
    try Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS).fileKey()
    catch case _: java.io.IOException => null
}

/** An IP datagram socket: bound, or connected to one peer. Its receives and sends each take turns. */
sealed abstract class DatagramHandle(kind: String, private[reference] val datagrams: DatagramChannel)
    extends RefHandle(kind) {
  protected def channel: Channel = datagrams
  private[reference] val receives = new Turns
  private[reference] val sends = new Turns
}

final class BoundDatagramHandle(datagrams: DatagramChannel) extends DatagramHandle("bound datagram socket", datagrams)

final class ConnectedDatagramHandle(datagrams: DatagramChannel)
    extends DatagramHandle("connected datagram socket", datagrams)

/** A pipe's read end. */
final class PipeSourceHandle(source: Pipe.SourceChannel) extends RefHandle("pipe's read end") with ReadEnd {
  protected def channel: Channel = source
  private[reference] def in = source
  private[reference] def inReady = source
}

/** A pipe's write end. */
final class PipeSinkHandle(sink: Pipe.SinkChannel) extends RefHandle("pipe's write end") with WriteEnd {
  protected def channel: Channel = sink
  private[reference] def out = sink
  private[reference] def outReady = sink
}

/** A regular file, read and written as a stream at its own position, or at offsets. Always ready. */
final class FileHandle(private[reference] val file: FileChannel, path: String)
    extends RefHandle(s"file $path")
    with ReadEnd
    with WriteEnd {
  protected def channel: Channel = file
  private[reference] def in = file
  private[reference] def inReady = null
  private[reference] def out = file
  private[reference] def outReady = null
}
