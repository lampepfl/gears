package FIO

import concurrent.Future.Promise
import concurrent.{Async, Future}

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousFileChannel, CompletionHandler, SocketChannel}
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, StandardOpenOption}
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}


/** Example of integrating non-blocking interfaces via the use of promises. */
class File(val path: String) {
  private var channel: Option[AsynchronousFileChannel] = None

  def open(options: StandardOpenOption*): File =
    val options1 = if (options.isEmpty) Seq(StandardOpenOption.READ) else options
    channel = Some(
      AsynchronousFileChannel.open(Path.of(path), options1*))
    this

  def close(): Unit =
    if (channel.isDefined)
      channel.get.close()
      channel = None

  def read(position: Int = 0): Future[String] =
    assert(channel.isDefined)

    val buffer = ByteBuffer.allocate(1024)
    val p = Promise[String]()
    channel.get.read(buffer, position, buffer, new CompletionHandler[Integer, ByteBuffer] {

      override def completed(result: Integer, attachment: ByteBuffer): Unit = {
        attachment.flip
        val data: Array[Byte] = new Array[Byte](attachment.limit)
        attachment.get(data)
        val s = String(data)
        attachment.clear()
        p.complete(Success(s))
      }

      override def failed(e: Throwable, attachment: ByteBuffer): Unit = p.complete(Failure(e))
    })
    p.future

  def write(content: String, position: Int = 0): Future[Int] =
    assert(channel.isDefined)

    val p = Promise[Int]()
    val buffer = ByteBuffer.allocate(1024)
    buffer.put(content.getBytes)
    buffer.flip()
    channel.get.write(buffer, position, buffer, new CompletionHandler[Integer, ByteBuffer] {
      override def completed(result: Integer, attachment: ByteBuffer): Unit = p.complete(Success(result))

      override def failed(e: Throwable, attachment: ByteBuffer): Unit = p.complete(Failure(e))
    })

    p.future


  override def finalize(): Unit = {
    super.finalize()
    if (channel.isDefined)
      channel.get.close()
  }
}

/** Example of integrating blocking interfaces via the use of virtual threads and promises. */
class Socket(val address: String, val port: Int = 80) {
  private var channel: Option[SocketChannel] = None

  def open(): Socket =
    channel = Some(SocketChannel.open())
    assert(channel.get.connect(InetSocketAddress(address, port)))
    this

  def close(): Unit =
    if (channel.isDefined)
      channel.get.close()
      channel = None

  def write(data: String): Future[Unit] =
    assert(channel.isDefined)

    val p = Promise[Unit]()
    Thread.startVirtualThread: () =>
      val buf = ByteBuffer.allocate(data.length)
      buf.clear
      buf.put(data.getBytes)
      buf.flip()
      while (buf.hasRemaining) channel.get.write(buf)
      p.complete(null)
    p.future

  def read(): Future[String] =
    assert(channel.isDefined)

    val p = Promise[String]()
    Thread.startVirtualThread: () =>
      val buf = ByteBuffer.allocate(1024 * 1024)
      val bytesRead: Int = channel.get.read(buf)
      val buf2 = Array.fill[Byte](bytesRead)(0)
      buf.get(buf2)
      p.complete(Success(String(buf2)))

    p.future

  override def finalize(): Unit = {
    super.finalize()
    if (channel.isDefined)
      channel.get.close()
  }
}

object FIO {
  def withFile[T](path: String, options: StandardOpenOption*)(f: File => T): T =
    val file = File(path).open(options*)
    val ret = f(file)
    file.close()
    ret

  def withSocket[T](address: String, port: Int = 80)(f: Socket => T): T =
    val sock = Socket(address, port).open()
    val ret = f(sock)
    sock.close()
    ret
}


@main def main(): Unit =
  given ExecutionContext = ExecutionContext.global

  Async.blocking:
    FIO.withFile("/home/julian/Desktop/x.txt", StandardOpenOption.READ, StandardOpenOption.WRITE): f =>
      Async.await(f.write("hello world!"))
      println("READ: " + f.read().value)
      Async.await(f.write("ABC"))
      println("READ: " + f.read().value)

    FIO.withSocket("example.com"): f =>
      Async.await(f.write("GET / HTTP/1.1\nHost: example.com\nConnection: close\n\n"))
      println(f.read().value)
