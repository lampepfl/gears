package gears.io.demos

import gears.async.Async
import gears.async.Future
import gears.async.io.Backend
import gears.async.io.Io
import gears.async.io.IpAddress
import gears.async.io.IpDatagrams
import gears.async.io.Slot
import gears.async.io.UnixAddress
import gears.async.io.UnixDatagrams
import gears.async.io.testkit.BufferArena
import gears.io.higherlevel.HigherLevel

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import DemoIO.text

/** Mirrors of `example.KQueueExampleDatagramSocket` and `example.KQueueExampleDatagramServerSocket`, using the
  * reactor's `receive` and `send` ops.
  */
object GearsDatagram {

  // Client

  /** Exchanges a packet with a Unix datagram server; a Unix datagram client needs its own bound path for the reply. */
  def run(sock: String, localSock: String, message: String)(using io: Io[UnixDatagrams], async: Async): Unit = {
    require(sock != localSock, "The client and server socket paths must be different")
    BufferArena.scoped { arena =>
      val request = arena.of(message.getBytes(StandardCharsets.UTF_8))
      val response = arena.allocate(65536)
      val socket = HigherLevel.connectDatagram(UnixAddress(localSock), UnixAddress(sock))
      val ops = io.backend.ops
      val receive = ops.receive(socket, response)
      try exchange(io, sock, ops.send(socket, request), receive, request, response)
      finally io.perform(ops.close(socket))
    }
  }

  /** Exchanges a packet with an IP datagram server; connecting the socket also assigns it an ephemeral local port. */
  def run(address: IpAddress, message: String)(using io: Io[IpDatagrams], async: Async): Unit = {
    BufferArena.scoped { arena =>
      val request = arena.of(message.getBytes(StandardCharsets.UTF_8))
      val response = arena.allocate(65536)
      val socket = HigherLevel.connectDatagram(null, address)
      val ops = io.backend.ops
      val receive = ops.receive(socket, response)
      try exchange(io, address, ops.send(socket, request), receive, request, response)
      finally io.perform(ops.close(socket))
    }
  }

  /** Sends `request` with `send`, then waits for the reply with `receive`, each performed once, within five seconds.
    */
  private def exchange(
      io: Io[Backend],
      remote: Any,
      send: io.Op,
      receive: io.Op,
      request: ByteBuffer,
      response: ByteBuffer
  )(using Async): Unit = {
    println(s"Connected datagram socket to $remote")
    // Scoped, so the exchange is cancelled and settled before the socket is closed.
    Async.group {
      val ping = Future {
        io.perform(send) // completes once the whole packet has gone
        println(s"Sent datagram of ${request.limit()} bytes.")
        io.perform(receive) // completes once a packet has arrived
        println(s"Received datagram of ${response.remaining()} bytes: `${text(response)}`")
      }
      // UDP has no delivery guarantee; make a missing reply visible in the demo.
      val deadline = Future(io.perform(io.backend.ops.timer(5000)))
      Async.select(
        ping.handle(_.get),
        deadline.handle(_ => throw new IOException("Timed out waiting for datagram socket readiness or a reply"))
      )
    }
  }

  // Server

  /** Echoes each packet on a Unix datagram socket to its sender. */
  def serve(address: UnixAddress)(using io: Io[UnixDatagrams], async: Async): Unit =
    BufferArena.scoped { arena =>
      val packet = arena.allocate(65536)
      val socket = HigherLevel.bindDatagram(address)
      val sender = Slot[UnixAddress]() // each packet's sender, written alongside the packet itself
      val ops = io.backend.ops
      try echo(io, ops.receive(socket, packet, sender), packet, () => ops.send(socket, packet, sender.clear()))
      finally io.perform(ops.close(socket))
    }

  /** Echoes each packet on an IP datagram socket to its sender. */
  def serve(address: IpAddress)(using io: Io[IpDatagrams], async: Async): Unit =
    BufferArena.scoped { arena =>
      val packet = arena.allocate(65536)
      val socket = HigherLevel.bindDatagram(address)
      val sender = Slot[IpAddress]()
      val ops = io.backend.ops
      try echo(io, ops.receive(socket, packet, sender), packet, () => ops.send(socket, packet, sender.clear()))
      finally io.perform(ops.close(socket))
    }

  /** Receives packets with `receive`, one repeatable op, and echoes each, keeping it until the whole echo has gone,
    * with a new op per sender from `reply`.
    */
  private def echo(io: Io[Backend], receive: io.Op, packet: ByteBuffer, reply: () => io.Op)(using Async): Unit = {
    println("Datagram socket is now waiting for messages.")
    while true do {
      io.perform(receive)
      val size = packet.remaining()
      println(s"Received datagram of $size bytes: `${text(packet)}`")
      io.perform(reply())
      println(s"Replied with datagram of $size bytes.")
    }
  }
}
