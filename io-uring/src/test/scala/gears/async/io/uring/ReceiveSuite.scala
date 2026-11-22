package gears.async.io
package uring

import gears.async.Async
import gears.async.AsyncOperations
import gears.async.Future

import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Try

class ReceiveSuite extends munit.FunSuite {
  // - await outside munit's assert: it holds the suite's lock, which a suspension takes to another thread
  override def munitTimeout: Duration = 60.seconds

  private def ops = UringOps

  private def receiving(name: String)(body: => Any)(using munit.Location): Unit =
    test(name) {
      assume(Receive.enabled, "multishot receives are off")
      body
    }

  private def onPool[T](workers: Int)(program: Io[UringOps.type] ?=> Async.Spawn ?=> T): T =
    UringBackend.runOn(workers)(program)

  private def connection()(using io: Io[UringOps.type], spawn: Async.Spawn): (ConnectionHandle, ConnectionHandle) = {
    val listener = Slot[ListenerHandle]()
    io.perform(ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), listener))
    val l = listener.clear()
    val accepted = Slot[ConnectionHandle]()
    val accepting = Future(io.perform(ops.accept(l, accepted)))
    val client = Slot[ConnectionHandle]()
    io.perform(ops.connect(ops.localAddress(l).asInstanceOf[IpSocketAddress], client))
    accepting.await
    io.perform(ops.close(l))
    (client.clear(), accepted.clear())
  }

  private def bytes(n: Int, from: Int = 0): Array[Byte] = Array.tabulate(n)(i => ((from + i) % 251).toByte)

  private def send(to: ConnectionHandle, data: Array[Byte])(using io: Io[UringOps.type], async: Async): Unit = {
    val b = ByteBuffer.wrap(data)
    while b.hasRemaining do io.perform(ops.write(to, b))
  }

  private def send(to: ConnectionHandle, text: String)(using Io[UringOps.type], Async): Unit =
    send(to, text.getBytes("UTF-8"))

  private def contents(b: ByteBuffer): String = {
    val copy = b.duplicate()
    copy.flip()
    val out = new Array[Byte](copy.remaining())
    copy.get(out)
    new String(out, "UTF-8")
  }

  private def read(from: ConnectionHandle, size: Int)(using io: Io[UringOps.type], async: Async): String = {
    val b = ByteBuffer.allocate(size)
    io.perform(ops.read(from, b))
    contents(b)
  }

  private def readFully(from: ConnectionHandle, n: Int, chunk: Int)(using io: Io[UringOps.type], async: Async) = {
    val all = ByteBuffer.allocate(n)
    val b = ByteBuffer.allocate(chunk)
    while all.hasRemaining do {
      b.clear()
      b.limit(math.min(chunk, all.remaining()))
      io.perform(ops.read(from, b))
      if b.position() == 0 then fail(s"the stream ended after ${all.position()} of $n bytes")
      b.flip()
      all.put(b)
    }
    all.array()
  }

  private def until(what: => String)(done: => Boolean)(using io: Io[UringOps.type], async: Async): Unit = {
    val deadline = System.nanoTime() + 5_000_000_000L
    while !done do {
      if System.nanoTime() > deadline then fail(s"not within 5 s: $what")
      AsyncOperations.sleep(1)
    }
  }

  // - a receive is armed by a small read that finds nothing to take
  private def arm(
      client: ConnectionHandle,
      server: ConnectionHandle
  )(using io: Io[UringOps.type], spawn: Async.Spawn) = {
    val waiting = Future(read(server, 8))
    AsyncOperations.sleep(10)
    send(client, "!")
    assertEquals(waiting.await, "!")
  }

  private def receiverOf(h: ConnectionHandle): Receiver = {
    val r = h.receiver
    assert(r != null, s"$h has no receive")
    r.nn
  }

  private def state(r: Receiver)(using Async): String =
    onItsWorker(r)(s"queued ${r.queued}, armed ${r.armed}, ended ${r.ended}, ${r.worker.freeBuffers} buffers free")

  private def onItsWorker[T](r: Receiver)(body: => T)(using Async): T = {
    val result = Future.Promise[T]()
    r.worker.runOnRing(() => result.complete(Try(body)))
    result.await
  }

  receiving("small reads take what the connection's receive got, in order, however the reads cut it") {
    onPool(1) { io ?=>
      val (client, server) = connection()
      val first = Future(read(server, 64))
      AsyncOperations.sleep(10)
      send(client, "hello")
      assertEquals(first.await, "hello")
      val armed = onItsWorker(receiverOf(server))(receiverOf(server).armed)
      assert(armed, "the receive is not armed")
      send(client, "abcdefghij")
      val pieces = List.fill(4)(read(server, 3))
      assertEquals(pieces, List("abc", "def", "ghi", "j"))
      io.perform(ops.close(client))
      io.perform(ops.close(server))
    }
  }

  receiving("bytes that came while no read waited are the next reads', and nothing is read twice or lost") {
    onPool(1) { io ?=>
      val (client, server) = connection()
      arm(client, server)
      val data = bytes(40_000)
      send(client, data)
      until("the receive got it all")(onItsWorker(receiverOf(server))(receiverOf(server).queued) >= 10)
      val got = readFully(server, data.length, 1000)
      assert(got.sameElements(data), "the bytes differ")
      io.perform(ops.close(client))
      io.perform(ops.close(server))
    }
  }

  receiving("a large read stops the receive, takes what it had first, and then reads from the kernel itself") {
    onPool(1) { io ?=>
      val (client, server) = connection()
      arm(client, server)
      val head = bytes(100)
      send(client, head)
      until("the receive got the 100 bytes")(onItsWorker(receiverOf(server))(receiverOf(server).queued) == 1)
      val large = ByteBuffer.allocate(64 * 1024)
      io.perform(ops.read(server, large))
      assertEquals(large.position(), 100)
      assert(large.array().take(100).sameElements(head), "the queued bytes differ")
      val r = receiverOf(server)
      val waiting = Future {
        val b = ByteBuffer.allocate(64 * 1024)
        io.perform(ops.read(server, b))
        contents(b)
      }
      until(s"a large read with nothing queued stopped the receive: ${state(r)}")(onItsWorker(r)(!r.armed))
      send(client, "direct")
      assertEquals(waiting.await, "direct")
      val tail = bytes(200_000, from = 100)
      val sending = Future(send(client, tail))
      val got = readFully(server, tail.length, 64 * 1024)
      sending.await
      assert(got.sameElements(tail), "the bytes read from the kernel differ")
      arm(client, server)
      val armedAgain = onItsWorker(receiverOf(server))(receiverOf(server).armed)
      assert(armedAgain, "a small read did not arm it again")
      io.perform(ops.close(client))
      io.perform(ops.close(server))
    }
  }

  receiving("a vectored read and a pooled read take what was received before reading themselves") {
    onPool(1) { io ?=>
      val (client, server) = connection()
      arm(client, server)
      send(client, "0123456789")
      until(s"the receive got it: ${state(receiverOf(server))}")(
        onItsWorker(receiverOf(server))(receiverOf(server).queued) == 1
      )
      val bufs = Array(ByteBuffer.allocate(4), ByteBuffer.allocate(4))
      io.perform(ops.read(server, bufs))
      assertEquals(bufs.map(contents).toList, List("0123", "4567"))
      val pool = UringBackend.pool(16, 1)
      val into = Slot[Received]()
      io.perform(ops.read(server, pool, into))
      val received = into.clear()
      val rest = new Array[Byte](received.buffer.remaining())
      received.buffer.get(rest)
      received.release()
      assertEquals(new String(rest, "UTF-8"), "89")
      send(client, "after")
      val again = Slot[Received]()
      io.perform(ops.read(server, pool, again))
      val second = again.clear()
      val text = new Array[Byte](second.buffer.remaining())
      second.buffer.get(text)
      second.release()
      assertEquals(new String(text, "UTF-8"), "after")
      pool.close()
      io.perform(ops.close(client))
      io.perform(ops.close(server))
    }
  }

  receiving(
    "a waiting small read that is cancelled or runs out of time loses nothing: what comes later is the next read's"
  ) {
    onPool(1) { io ?=>
      val (client, server) = connection()
      arm(client, server)
      val waiting = Future(read(server, 8))
      AsyncOperations.sleep(10)
      waiting.cancel()
      val cancelled = waiting.awaitResult
      assert(cancelled.failed.toOption.exists(_.isInstanceOf[CancellationException]), cancelled)
      val timed = Try(io.perform(ops.read(server, ByteBuffer.allocate(8)), 20.millis))
      assert(timed.failed.toOption.exists(_.isInstanceOf[TimeoutException]), timed)
      arm(client, server)
      io.perform(ops.close(client))
      io.perform(ops.close(server))
    }
  }

  receiving("closing a connection fails its waiting read, ends its receive, and gives back every buffer it held") {
    onPool(1) { io ?=>
      val (client, server) = connection()
      arm(client, server)
      val r = receiverOf(server)
      val worker = r.worker
      send(client, bytes(20_000))
      until("the receive got some")(onItsWorker(r)(r.queued) > 0)
      assert(worker.freeBuffers < Receive.Entries, "no buffer is held")
      io.perform(ops.close(server))
      until(s"every buffer is back: ${state(r)}")(onItsWorker(r)(worker.freeBuffers) == Receive.Entries)
      val stillArmed = onItsWorker(r)(r.armed)
      assert(!stillArmed, "the receive is still armed")
      val (client2, server2) = connection()
      arm(client2, server2)
      val waiting = Future(read(server2, 8))
      AsyncOperations.sleep(10)
      io.perform(ops.close(server2))
      val closed = waiting.awaitResult
      assert(closed.failed.toOption.exists(_.isInstanceOf[ClosedChannelException]), closed)
      io.perform(ops.close(client))
      io.perform(ops.close(client2))
    }
  }

  receiving(
    "a close in the turn a receive is armed in, before the ring has submitted it, still closes the connection"
  ) {
    onPool(1) { io ?=>
      for i <- 1 to 20 do {
        val (client, server) = connection()
        val reading = Future(read(server, 8))
        val closing = Future(Try(io.perform(ops.close(server), 2.seconds)))
        val closed = closing.await
        assert(closed.isSuccess, s"round $i: the close did not complete: $closed")
        val waited = reading.awaitResult
        assert(waited.failed.toOption.exists(_.isInstanceOf[ClosedChannelException]), s"round $i: $waited")
        val end = Try(io.perform(ops.read(client, ByteBuffer.allocate(8)), 2.seconds))
        assert(end.isSuccess, s"round $i: the peer did not see the close: $end")
        io.perform(ops.close(client))
      }
      val worker = io.asInstanceOf[UringScheduler].workerOfThread(Thread.currentThread()).asInstanceOf[UringWorker]
      until(s"every buffer is back: ${worker.freeBuffers} free")(worker.freeBuffers == Receive.Entries)
    }
  }

  receiving("the end of the stream reaches the reads after what came before it") {
    onPool(1) { io ?=>
      val (client, server) = connection()
      arm(client, server)
      send(client, "last")
      io.perform(ops.shutdownOutput(client))
      assertEquals(read(server, 8), "last")
      assertEquals(read(server, 8), "")
      assertEquals(read(server, 8), "")
      io.perform(ops.close(client))
      io.perform(ops.close(server))
    }
  }

  receiving(
    "a connection whose unread bytes pass the cap stops receiving, holding the sender back, and loses nothing"
  ) {
    onPool(1) { io ?=>
      val (client, server) = connection()
      arm(client, server)
      val r = receiverOf(server)
      val data = bytes(32 * 1024 * 1024)
      val sending = Future(send(client, data))
      until(s"the receive stopped: ${state(r)}")(onItsWorker(r)(!r.armed && r.queued >= Receive.MostQueued))
      AsyncOperations.sleep(50)
      val queued = onItsWorker(r)(r.queued)
      AsyncOperations.sleep(50)
      val later = onItsWorker(r)(r.queued)
      assertEquals(later, queued, "it kept receiving after it stopped")
      assert(sending.poll().isEmpty, "the sender was not held back")
      val got = readFully(server, data.length, 8 * 1024)
      sending.await
      assert(got.sameElements(data), "the bytes differ")
      until(s"every buffer is back: ${state(r)}")(onItsWorker(r)(r.worker.freeBuffers) == Receive.Entries)
      io.perform(ops.close(client))
      io.perform(ops.close(server))
    }
  }

  receiving("a receive with no free buffer leaves its read to the kernel, and the read gets its bytes") {
    onPool(1) { io ?=>
      val holders = List.fill(Receive.Entries / Receive.MostQueued + 2)(connection())
      for (client, server) <- holders do {
        arm(client, server)
        send(client, bytes(Receive.MostQueued * Receive.Size))
      }
      val worker = receiverOf(holders.head._2).worker
      until("the buffers are all held")(onItsWorker(receiverOf(holders.head._2))(worker.freeBuffers) == 0)
      val (client, server) = connection()
      val waiting = Future(read(server, 8))
      AsyncOperations.sleep(10)
      send(client, "free")
      assertEquals(waiting.await, "free")
      for (c, s) <- holders do {
        readFully(s, Receive.MostQueued * Receive.Size, 4096): Unit
        io.perform(ops.close(c))
        io.perform(ops.close(s))
      }
      until("every buffer is back")(onItsWorker(receiverOf(server))(worker.freeBuffers) == Receive.Entries)
      io.perform(ops.close(client))
      io.perform(ops.close(server))
    }
  }

  receiving("reads performed from other workers are performed on the receive's, in order") {
    onPool(4) { io ?=>
      val (client, server) = connection()
      arm(client, server)
      val owner = receiverOf(server).worker
      val pool = io.asInstanceOf[UringScheduler]
      var turn = Future.Promise[Unit]()
      turn.complete(scala.util.Success(()))
      val readers = (1 to 200).map { i =>
        val mine = turn
        val next = Future.Promise[Unit]()
        turn = next
        Future {
          mine.await
          val here = pool.workerOfThread(Thread.currentThread())
          val got = read(server, 1)
          next.complete(scala.util.Success(()))
          (here ne owner, got)
        }
      }
      for i <- 1 to 200 do send(client, f"${i % 10}")
      val results = readers.map(_.await)
      val elsewhere = results.count(_._1)
      assertEquals(results.map(_._2).mkString.length, 200)
      assert(elsewhere > 0, "no read was performed from another worker: the test showed nothing")
      io.perform(ops.close(client))
      io.perform(ops.close(server))
    }
  }

  private def workerOf(here: Thread)(using io: Io[UringOps.type]): UringWorker =
    io.asInstanceOf[UringScheduler].workerOfThread(here).asInstanceOf[UringWorker]

  // - a future starts on the worker that spawns it
  private def onWorker[T](w: UringWorker)(body: Async ?=> T)(using spawn: Async.Spawn): Future[T] = {
    val started = Future.Promise[Future[T]]()
    w.runOnRing(() => started.complete(Try(Future(body))))
    started.await
  }

  // - a large read with nothing queued stops the receive, which then stays idle
  private def idle(client: ConnectionHandle, server: ConnectionHandle)(using
      io: Io[UringOps.type],
      spawn: Async.Spawn
  ) = {
    arm(client, server)
    val r = receiverOf(server)
    val waiting = Future {
      val b = ByteBuffer.allocate(64 * 1024)
      io.perform(ops.read(server, b))
      contents(b)
    }
    until(s"the receive stopped: ${state(r)}")(onItsWorker(r)(!r.armed))
    send(client, "idle")
    assertEquals(waiting.await, "idle")
  }

  receiving("a connection read to the end of its stream and then closed leaves no receive behind") {
    onPool(1) { io ?=>
      val (client, server) = connection()
      arm(client, server)
      val r = receiverOf(server)
      val worker = r.worker
      send(client, "last")
      io.perform(ops.close(client))
      assertEquals(read(server, 8), "last")
      assertEquals(read(server, 8), "")
      io.perform(ops.close(server))
      until(s"the receive is gone: ${state(r)}")(onItsWorker(r)(worker.receiverCount) == 0)
      assertEquals(onItsWorker(r)(worker.freeBuffers), Receive.Entries)
    }
  }

  receiving("a connection closed with what its stopped receive got unread gives back every buffer") {
    onPool(1) { io ?=>
      val (client, server) = connection()
      arm(client, server)
      val r = receiverOf(server)
      val worker = r.worker
      send(client, bytes(2 * Receive.MostQueued * Receive.Size))
      until(s"the receive stopped: ${state(r)}")(onItsWorker(r)(!r.armed && r.queued >= Receive.MostQueued))
      io.perform(ops.close(server))
      until(s"every buffer is back: ${state(r)}")(onItsWorker(r)(worker.freeBuffers) == Receive.Entries)
      assertEquals(onItsWorker(r)(worker.receiverCount), 0)
      io.perform(ops.close(client))
    }
  }

  receiving("reads from two workers in turn on an idle connection are each performed where they were made, in order") {
    onPool(2) { io ?=>
      val (client, server) = connection()
      idle(client, server)
      val pool = io.asInstanceOf[UringScheduler]
      val results = (1 to 100).map { i =>
        val reader = onWorker(pool.worker(i % 2)) {
          val here = workerOf(Thread.currentThread())
          val b = ByteBuffer.allocate(64 * 1024)
          io.perform(ops.read(server, b))
          (here, receiverOf(server).worker, contents(b))
        }
        if i % 3 == 0 then AsyncOperations.sleep(1)
        send(client, f"${i % 10}")
        reader.await
      }
      assertEquals(results.map(_._3).mkString, (1 to 100).map(_ % 10).mkString)
      assert(results.map(_._1).distinct.size == 2, "every read was made on one worker: the test showed nothing")
      val elsewhere = results.count((here, on, _) => here ne on)
      assertEquals(elsewhere, 0, "reads were performed on another worker than the one they were made on")
      io.perform(ops.close(client))
      io.perform(ops.close(server))
    }
  }

  receiving("a pooled read waiting for its pool does not keep the receive past its cap") {
    onPool(1) { io ?=>
      val (client, server) = connection()
      arm(client, server)
      val r = receiverOf(server)
      val pool = UringBackend.pool(4096, 1)
      send(client, "held")
      until(s"the receive got it: ${state(r)}")(onItsWorker(r)(r.queued) == 1)
      val held = Slot[Received]()
      io.perform(ops.read(server, pool, held))
      val holding = held.clear()
      send(client, "x")
      until(s"the receive got it: ${state(r)}")(onItsWorker(r)(r.queued) == 1)
      val into = Slot[Received]()
      val waiting = Future(io.perform(ops.read(server, pool, into)))
      AsyncOperations.sleep(10)
      val data = bytes(4 * Receive.MostQueued * Receive.Size)
      for piece <- data.grouped(Receive.Size) do {
        send(client, piece)
        AsyncOperations.sleep(1)
      }
      until(s"the receive stopped: ${state(r)}")(onItsWorker(r)(!r.armed))
      val queued = onItsWorker(r)(r.queued)
      assert(queued < 3 * Receive.MostQueued, s"the receive kept $queued buffers")
      holding.release()
      waiting.await
      val first = into.clear()
      val head = new Array[Byte](first.buffer.remaining())
      first.buffer.get(head)
      first.release()
      assertEquals(head(0), 'x'.toByte)
      val got = head.drop(1) ++ readFully(server, data.length - head.length + 1, 4096)
      assert(got.sameElements(data), "the bytes differ")
      pool.close()
      io.perform(ops.close(client))
      io.perform(ops.close(server))
    }
  }

  receiving("a close whose stop the kernel refused stops the receive at its next completion") {
    onPool(1) { io ?=>
      val (client, server) = connection()
      arm(client, server)
      val r = receiverOf(server)
      onItsWorker(r)(r.worker.stopsToRefuse = 1)
      val sending = Future {
        var going = true
        while going do {
          going = Try(send(client, "more")).isSuccess
          AsyncOperations.sleep(2)
        }
      }
      AsyncOperations.sleep(10)
      val closed = Try(io.perform(ops.close(server), 2.seconds))
      assert(closed.isSuccess, s"the close did not complete: $closed")
      io.perform(ops.close(client))
      sending.awaitResult: Unit
    }
  }

  receiving("a large read whose stop the kernel refused stops the receive at its next completion") {
    onPool(1) { io ?=>
      val (client, server) = connection()
      arm(client, server)
      val r = receiverOf(server)
      onItsWorker(r)(r.worker.stopsToRefuse = 1)
      val waiting = Future {
        val b = ByteBuffer.allocate(64 * 1024)
        io.perform(ops.read(server, b))
        contents(b)
      }
      AsyncOperations.sleep(10)
      send(client, "data")
      assertEquals(waiting.await, "data")
      until(s"the receive stopped: ${state(r)}")(onItsWorker(r)(!r.armed))
      io.perform(ops.close(client))
      io.perform(ops.close(server))
    }
  }

  receiving("a failing ring fails the reads waiting on its receives, and those that come after") {
    val outcome = Try(onPool(1) { io ?=>
      val (client, server) = connection()
      arm(client, server)
      val waiting = Future(read(server, 8))
      AsyncOperations.sleep(10)
      io.asInstanceOf[UringScheduler].failRing(new java.io.IOException("failed for the test"))
      val failed = waiting.awaitResult
      assert(failed.failed.toOption.exists(_.isInstanceOf[java.io.IOException]), failed)
    })
    assert(outcome.failed.toOption.exists(_.isInstanceOf[java.io.IOException]), outcome)
  }
}
