package gears.async.io
package testkit

import gears.async.Future
import gears.async.Future.awaitAll

import java.nio.charset.StandardCharsets

/** Concurrent datagram programs requiring only IpDatagrams. */
trait IpDatagramFuturesContract extends ContractSuite {
  val backend: Backend & IpDatagrams
  protected type BoundDatagram = backend.BoundDatagram
  protected type ConnectedDatagram = backend.ConnectedDatagram

  test("a datagram socket refuses an empty packet when connected, and a receive with no room") {
    val outcome = running { io ?=>
      val boundInto = Slot[BoundDatagram]()
      val server =
        opening(io, boundInto, io.backend.ops.bindDatagram(IpSocketAddress(IpAddress.IPv4Loopback, 0), boundInto))
      val clientInto = Slot[ConnectedDatagram]()
      val client =
        opening(
          io,
          clientInto,
          io.backend.ops.connectDatagram(null, io.backend.ops.localAddress(server), clientInto)
        )
      try {
        def refused(op: io.Op): Boolean =
          try {
            io.perform(op)
            false
          } catch case _: IllegalArgumentException => true
        val empty = arena.allocate(4)
        empty.limit(0)
        val none = arena.allocate(4)
        none.limit(0)
        List(
          refused(io.backend.ops.send(client, empty)),
          refused(io.backend.ops.receive(server, none.slice(), Slot[IpSocketAddress]()))
        ).mkString(" ")
      } finally {
        io.perform(io.backend.ops.close(client))
        io.perform(io.backend.ops.close(server))
      }
    }
    assertEquals(outcome, "true true")
  }

  test(
    "futures receiving on one datagram socket at once take turns, each its own packet, one cancelled leaving the queue"
  ) {
    val outcome = running { io ?=>
      val boundInto = Slot[BoundDatagram]()
      val server =
        opening(io, boundInto, io.backend.ops.bindDatagram(IpSocketAddress(IpAddress.IPv4Loopback, 0), boundInto))
      val clientInto = Slot[ConnectedDatagram]()
      val client =
        opening(
          io,
          clientInto,
          io.backend.ops.connectDatagram(null, io.backend.ops.localAddress(server), clientInto)
        )
      try {
        def receiving(): Future[String] = {
          val buf = arena.allocate(16)
          val receiver = Future {
            io.perform(io.backend.ops.receive(server, buf, Slot[IpSocketAddress]()))
            StandardCharsets.UTF_8.decode(buf).toString
          }
          io.perform(io.backend.ops.timer(20)) // so that it waits, behind those before it
          receiver
        }
        val first = receiving()
        val leaving = receiving()
        val second = receiving()
        leaving.cancel()
        leaving.awaitResult
        for packet <- List("one", "two") do
          io.perform(io.backend.ops.send(client, arena.of(packet.getBytes(StandardCharsets.UTF_8))))
        s"${first.await} ${second.await}"
      } finally {
        io.perform(io.backend.ops.close(client))
        io.perform(io.backend.ops.close(server))
      }
    }
    assertEquals(outcome, "one two")
  }

  test("futures sending on one datagram socket at once each send every packet, whole") {
    val outcome = running { io ?=>
      val boundInto = Slot[BoundDatagram]()
      val server =
        opening(io, boundInto, io.backend.ops.bindDatagram(IpSocketAddress(IpAddress.IPv4Loopback, 0), boundInto))
      val clientInto = Slot[ConnectedDatagram]()
      val client =
        opening(
          io,
          clientInto,
          io.backend.ops.connectDatagram(null, io.backend.ops.localAddress(server), clientInto)
        )
      try {
        val senders = 4
        val each = 25
        val received = Future {
          val buf = arena.allocate(32)
          val from = Slot[IpSocketAddress]()
          val receive = io.backend.ops.receive(server, buf, from)
          (1 to senders * each).map { _ =>
            buf.clear()
            io.perform(receive)
            from.clear()
            StandardCharsets.UTF_8.decode(buf).toString
          }
        }
        val sending = (1 to senders).map { s =>
          Future {
            val buf = arena.allocate(32)
            for i <- 1 to each do {
              buf.clear()
              buf.put(s"sender $s packet $i".getBytes(StandardCharsets.UTF_8)).flip()
              io.perform(io.backend.ops.send(client, buf))
            }
          }
        }
        sending.awaitAll
        val packets = received.await
        val expected = for s <- 1 to senders; i <- 1 to each yield s"sender $s packet $i"
        s"${packets.size} ${packets.sorted == expected.sorted}"
      } finally {
        io.perform(io.backend.ops.close(client))
        io.perform(io.backend.ops.close(server))
      }
    }
    assertEquals(outcome, "100 true")
  }
}
