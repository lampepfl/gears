package example

import gears.async.io.internal.Bracket
import gears.async.io.internal.Bracket.FileOperation
import gears.async.io.internal.NativeBuffer
import gears.async.io.internal.PosixErr.cError
import gears.async.io.internal.PosixSockets
import gears.async.io.kqueue.KqueueHandles

import java.io.IOException
import java.nio.ByteBuffer
import scala.scalanative.posix.fcntl
import scala.scalanative.posix.sys.socket
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Building blocks shared by the kqueue demos: the stream framing (from `gears.io.demos.DemoIO`), non-blocking I/O that
  * reports would-block and EOF as values instead of exceptions, and datagram peers.
  */
object KQueueExampleIO {

  /** Runs `r` until it is stopped, or until a completion throws, which includes a failure no completion handled (the
    * default `onFailure` throws it); the reactor reports that (`Reactor.reportFailure`) rather than throwing it, and
    * these demos take any such failure as fatal: it stops the loop, and is thrown here.
    */
  def runFailingFast(r: gears.async.io.internal.Reactor): Unit = {
    var failure: Throwable | Null = null
    r.reportFailure = t => {
      if failure == null then failure = t
      r.stop()
    }
    r.run()
    failure match {
      case null                                               => ()
      case reported: gears.async.io.internal.CompletionFailed => throw reported.getCause
      case other                                              => throw other.nn
    }
  }

  // Shared with the gears demos, which run on every platform.
  export gears.io.demos.DemoIO.{FrameParser, FrameReader, InvalidFrame, StreamProtocol, drainTo, text}

  /** A connecting stream socket; the connection is verified before its first use, which must wait for the socket to be
    * writable first: that is when a non-blocking connect has finished, and checking before it passes while the
    * connection is still in progress.
    */
  final class ClientSock(val fd: Int) {
    private var connected = false

    /** Whether the connection has been verified (`ensureConnected`). */
    def isConnected: Boolean = connected

    def ensureConnected(): Unit = {
      if !connected then {
        PosixSockets.checkConnect(fd)
        connected = true
      }
    }
  }

  // Non-blocking I/O, now provided by gears' Native core (`NonBlocking`) under the names the demos used.
  export gears.async.io.internal.NonBlocking.{EOF, accept as nioAccept, read as nioReadBytes, write as nioWriteBytes}

  /** A non-blocking pipe, closed after `use`. */
  def withPipe(use: (Int, Int) => Unit): Unit = {
    val ends = stackalloc[CInt](2)
    if unistd.pipe(ends) < 0 then throw new IOException(s"Failed to create pipe: ${cError()}")
    val (readFd, writeFd) = (ends(0), ends(1))
    try {
      PosixSockets.setNonBlocking(readFd)
      PosixSockets.setNonBlocking(writeFd)
      use(readFd, writeFd)
    } finally {
      KqueueHandles.close(KqueueHandles.handle(readFd))
      KqueueHandles.close(KqueueHandles.handle(writeFd))
    }
  }

  /** Opens `path` non-blocking for reading or writing and closes it after `use`. */
  def withFile(path: String, write: Boolean)(use: FileOperation): Unit = {
    println(s"attempt to open file $path")
    val fd = Zone.acquire { implicit z =>
      fcntl.open(toCString(path), (if write then fcntl.O_WRONLY else fcntl.O_RDONLY) | fcntl.O_NONBLOCK)
    }
    if fd < 0 then throw new IOException(s"Failed to open file: ${cError()}")
    println(s"opened file descriptor: $fd (file: $path)")
    Bracket.fileResource(fd)(closeFile)(use)
  }

  private def closeFile(fd: Int): Unit =
    KqueueHandles.close(KqueueHandles.handle(fd)) // which tells the reactor the descriptor is gone

  // Datagrams

  /** The address of a datagram's sender, captured on receive and reusable as a reply destination. */
  final class PeerAddress(using Zone) {
    private val storage = alloc[socket.sockaddr_storage]()
    private val lengthPtr = alloc[socket.socklen_t]()

    def address: Ptr[socket.sockaddr] = storage.asInstanceOf[Ptr[socket.sockaddr]]
    def length: socket.socklen_t = !lengthPtr

    /** Receives one packet into `buf`, leaving it flipped for draining, and records the sender. Returns the packet
      * size, or -1 if no packet is ready.
      */
    def receive(fd: Int, buf: ByteBuffer): Int = {
      buf.clear()
      !lengthPtr = sizeOf[socket.sockaddr_storage].toUInt
      val read = PosixSockets.receiveDatagram(fd, NativeBuffer.atPosition(buf), buf.remaining(), address, lengthPtr)
      if read >= 0 then {
        NativeBuffer.advance(buf, read)
        buf.flip()
      }
      read
    }

    /** Sends the bytes between position and limit back to the recorded sender, consuming them. Returns false, leaving
      * the buffer intact for a retry, if it would block.
      */
    def reply(fd: Int, buf: ByteBuffer): Boolean =
      consumeIfSent(buf, PosixSockets.sendDatagram(fd, NativeBuffer.atPosition(buf), buf.remaining(), address, length))
  }

  /** Sends the bytes between position and limit to the peer of a connected datagram socket, consuming them. Returns
    * false, leaving the buffer intact for a retry, if it would block.
    */
  def sendDatagram(fd: Int, buf: ByteBuffer): Boolean =
    consumeIfSent(buf, PosixSockets.sendDatagram(fd, NativeBuffer.atPosition(buf), buf.remaining()))

  /** A datagram is sent whole or not at all, so a successful send drains the entire buffer. */
  private def consumeIfSent(buf: ByteBuffer, sent: Boolean): Boolean = {
    if sent then buf.position(buf.limit())
    sent
  }
}
