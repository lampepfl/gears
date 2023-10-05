package PosixLikeIO

import gears.async.{Async, Future}
import Future.Promise

import java.net.{DatagramPacket, DatagramSocket, InetAddress, InetSocketAddress, ServerSocket, Socket}
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousFileChannel, CompletionHandler, SocketChannel}
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.{Path, StandardOpenOption}
import scala.Tuple.Union
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}


class File(val path: String) {
  private var channel: Option[AsynchronousFileChannel] = None

  def isOpened: Boolean = channel.isDefined && channel.get.isOpen

  def open(options: StandardOpenOption*): File =
    assert(channel.isEmpty)
    val options1 = if (options.isEmpty) Seq(StandardOpenOption.READ) else options
    channel = Some(
      AsynchronousFileChannel.open(Path.of(path), options1*))
    this

  def close(): Unit =
    if (channel.isDefined)
      channel.get.close()
      channel = None

  def read(buffer: ByteBuffer): Future[Int] =
    assert(channel.isDefined)

    val p = Promise[Int]()
    channel.get.read(buffer, 0, buffer, new CompletionHandler[Integer, ByteBuffer] {
      override def completed(result: Integer, attachment: ByteBuffer): Unit = p.complete(Success(result))
      override def failed(e: Throwable, attachment: ByteBuffer): Unit = p.complete(Failure(e))
    })
    p.future

  def readString(size: Int, charset: Charset = StandardCharsets.UTF_8): Future[String] =
    assert(channel.isDefined)
    assert(size >= 0)

    val buffer = ByteBuffer.allocate(size)
    val p = Promise[String]()
    channel.get.read(buffer, 0, buffer, new CompletionHandler[Integer, ByteBuffer] {
      override def completed(result: Integer, attachment: ByteBuffer): Unit =
        p.complete(Success(charset.decode(attachment.slice(0, result)).toString()))
      override def failed(e: Throwable, attachment: ByteBuffer): Unit = p.complete(Failure(e))
    })
    p.future

  def write(buffer: ByteBuffer): Future[Int] =
    assert(channel.isDefined)

    val p = Promise[Int]()
    channel.get.write(buffer, 0, buffer, new CompletionHandler[Integer, ByteBuffer] {
      override def completed(result: Integer, attachment: ByteBuffer): Unit = p.complete(Success(result))
      override def failed(e: Throwable, attachment: ByteBuffer): Unit = p.complete(Failure(e))
    })
    p.future


  def writeString(s: String, charset: Charset = StandardCharsets.UTF_8): Future[Int] =
    write(ByteBuffer.wrap(s.getBytes(charset)))

  override def finalize(): Unit = {
    super.finalize()
    if (channel.isDefined)
      channel.get.close()
  }
}

class SocketUDP() {
  private var socket: Option[DatagramSocket] = None

  def isOpened: Boolean = socket.isDefined && !socket.get.isClosed

  def bindAndOpen(port: Int): SocketUDP =
    assert(socket.isEmpty)
    socket = Some(DatagramSocket(port))
    this

  def open(): SocketUDP =
    assert(socket.isEmpty)
    socket = Some(DatagramSocket())
    this

  def close(): Unit =
    if (socket.isDefined)
      socket.get.close()
      socket = None

  def send(data: ByteBuffer, address: String, port: Int): Future[Unit] =
    assert(socket.isDefined)

    Async.blocking:
      Future:
        val packet: DatagramPacket = new DatagramPacket(data.array(), data.limit(), InetAddress.getByName(address), port)
        socket.get.send(packet)

  def receive(): Future[DatagramPacket] =
    assert(socket.isDefined)

    Async.blocking:
      Future[DatagramPacket]:
        val buffer = Array.fill[Byte](10 * 1024)(0)
        val packet: DatagramPacket = DatagramPacket(buffer, 10 * 1024)
        socket.get.receive(packet)
        packet

  override def finalize(): Unit = {
    super.finalize()
    if (socket.isDefined)
      socket.get.close()
  }
}

object PIOHelper {
  def withFile[T](path: String, options: StandardOpenOption*)(f: File => T): T =
    val file = File(path).open(options*)
    val ret = f(file)
    file.close()
    ret

  def withSocketUDP[T]()(f: SocketUDP => T): T =
    val s = SocketUDP().open()
    val ret = f(s)
    s.close()
    ret

  def withSocketUDP[T](port: Int)(f: SocketUDP => T): T =
    val s = SocketUDP().bindAndOpen(port)
    val ret = f(s)
    s.close()
    ret
}
