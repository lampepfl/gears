package gears.async.io
package uring

import gears.async.Async
import gears.async.AsyncOperations
import gears.async.Future
import gears.async.io.posix.NativeBuffer

import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.scalanative.posix.fcntl
import scala.scalanative.posix.unistd
import scala.scalanative.runtime.ByteArray
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

class UringSuite extends munit.FunSuite {
  // - await outside munit's assert: it holds the suite's lock, which a suspension takes to another thread
  override def munitTimeout: Duration = 60.seconds

  private def onPool[T](workers: Int)(program: Io[UringOps.type] ?=> Async.Spawn ?=> T): T =
    UringBackend.runOn(workers)(program)

  private def ops = UringOps

  private def pipe()(using io: Io[UringOps.type], async: Async): (UringReadable, UringWritable) = {
    val read = Slot[UringReadable]()
    val write = Slot[UringWritable]()
    io.perform(ops.pipe(read, write))
    (read.clear(), write.clear())
  }

  private def close(handle: UringHandle)(using io: Io[UringOps.type], async: Async): Unit =
    io.perform(ops.close(handle))

  private def rawWrite(handle: UringHandle, text: String): Unit = {
    val bytes = text.getBytes("UTF-8")
    assertEquals(unistd.write(handle.fd, bytes.asInstanceOf[ByteArray].at(0), bytes.length.toUSize).toInt, bytes.length)
  }

  private def rawRead(handle: UringHandle): String = {
    val bytes = new Array[Byte](64)
    val n = unistd.read(handle.fd, bytes.asInstanceOf[ByteArray].at(0), 64.toUSize).toInt
    if n <= 0 then "" else new String(bytes, 0, n, "UTF-8")
  }

  private def text(buf: ByteBuffer): String = {
    val copy = buf.duplicate()
    copy.flip()
    val bytes = new Array[Byte](copy.remaining())
    copy.get(bytes)
    new String(bytes, "UTF-8")
  }

  private def performing(op: UringOp)(using io: Io[UringOps.type], spawn: Async.Spawn) = {
    val performed = new AtomicReference[Try[Unit] | Null](null)
    val future = Future {
      performed.set(Try(io.perform(op)))
      performed.get().nn.get
    }
    (future, performed)
  }

  private def returnsWithin(limit: FiniteDuration)(body: => Unit): Unit = {
    val failure = new AtomicReference[Throwable | Null](null)
    val thread = new Thread(() =>
      try body
      catch case e: Throwable => failure.set(e)
    )
    thread.setDaemon(true)
    thread.start()
    thread.join(limit.toMillis)
    assert(!thread.isAlive, s"it had not returned after $limit")
    val e = failure.get()
    if e != null then throw e
  }

  test("an op whose time runs out while its failing completion waits in the ring fails, and does not hang") {
    returnsWithin(20.seconds) {
      onPool(1) { io ?=>
        for _ <- 1 to 10 do {
          val (r, w) = pipe()
          val filler = new Array[Byte](65536)
          while unistd.write(w.fd, filler.asInstanceOf[ByteArray].at(0), filler.length.toUSize).toInt > 0 do ()
          val writer = Future(Try(io.perform(ops.write(w, ByteBuffer.wrap(Array[Byte](1))), 20.millis)))
          AsyncOperations.sleep(5)
          close(r)
          val until = System.nanoTime() + 40_000_000L
          while System.nanoTime() < until do ()
          writer.await match {
            case Failure(_: java.util.concurrent.TimeoutException | _: java.io.IOException) => ()
            case other => fail(s"expected the write to fail, not $other")
          }
          close(w)
        }
      }
    }
  }

  test("a blocking op that throws an Error fails with it, and does not hang") {
    returnsWithin(20.seconds) {
      onPool(2) { io ?=>
        val e = intercept[NotImplementedError](io.perform(ops.blocking(() => throw new NotImplementedError("boom"))))
        assertEquals(e.getMessage, "boom")
      }
    }
  }

  private def withTempDir[T](body: java.nio.file.Path => T): T = {
    val dir = java.nio.file.Files.createTempDirectory("gears-uring-test")
    try body(dir)
    finally {
      java.nio.file.Files.list(dir).forEach(f => java.nio.file.Files.deleteIfExists(f): Unit)
      java.nio.file.Files.deleteIfExists(dir): Unit
    }
  }

  private def mkfifo(path: java.nio.file.Path): Unit = Zone.acquire { implicit z =>
    val mode = Integer.parseInt("600", 8).toUInt
    assertEquals(scala.scalanative.posix.sys.stat.mkfifo(toCString(path.toString), mode), 0)
  }

  test("every handle is close-on-exec: pipes, listeners, connections, accepted ones, files, FIFOs, datagram sockets") {
    withTempDir { dir =>
      onPool(2) { io ?=>
        def cloexec(h: UringHandle) = (fcntl.fcntl(h.fd, fcntl.F_GETFD, 0) & fcntl.FD_CLOEXEC) != 0
        val (r, w) = pipe()
        val listener = Slot[ListenerHandle]()
        io.perform(ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), listener))
        val l = listener.clear()
        val accepted = Slot[ConnectionHandle]()
        val accepting = Future(io.perform(ops.accept(l, accepted)))
        val client = Slot[ConnectionHandle]()
        io.perform(ops.connect(ops.localAddress(l).asInstanceOf[IpSocketAddress], client))
        accepting.await
        val file = Slot[FileHandle]()
        io.perform(ops.open("/proc/self/stat", write = false, file))
        val fifo = dir.resolve("fifo")
        mkfifo(fifo)
        val fifoReader = Slot[UringReadable]()
        io.perform(ops.openFifoForReading(fifo.toString, fifoReader))
        val fifoWriter = Slot[UringWritable]()
        io.perform(ops.openFifoForWriting(fifo.toString, fifoWriter))
        val bound = Slot[UnixBoundDatagramHandle]()
        val path = UnixAddress(dir.resolve("bound.sock").toString)
        io.perform(ops.bindDatagram(path, bound))
        val connected = Slot[UnixConnectedDatagramHandle]()
        io.perform(ops.connectDatagram(null, path, connected))
        val handles = List(r, w, l, client.clear(), accepted.clear(), file.clear()) ++
          List(fifoReader.clear(), fifoWriter.clear(), bound.clear(), connected.clear())
        for h <- handles do assert(cloexec(h), s"$h is not close-on-exec")
        handles.foreach(close)
      }
    }
  }

  test("a Unix datagram socket that fails to connect leaves nothing at the path it bound") {
    withTempDir { dir =>
      onPool(1) { io ?=>
        val local = dir.resolve("client.sock")
        val into = Slot[UnixConnectedDatagramHandle]()
        val nowhere = UnixAddress(dir.resolve("none").toString)
        val failure = Try(io.perform(ops.connectDatagram(UnixAddress(local.toString), nowhere, into)))
        assert(failure.failed.toOption.exists(_.isInstanceOf[java.io.IOException]), failure)
        assert(into.isEmpty)
        assert(!java.nio.file.Files.exists(local), "the bound path was left")
      }
    }
  }

  test("a FIFO's write end fails to open with no reader, and a read waiting on the FIFO, cancelled, takes nothing") {
    withTempDir { dir =>
      onPool(2) { io ?=>
        val fifo = dir.resolve("fifo")
        mkfifo(fifo)
        val noReader = Slot[UringWritable]()
        val failure = Try(io.perform(ops.openFifoForWriting(fifo.toString, noReader)))
        assert(failure.failed.toOption.exists(_.isInstanceOf[java.io.IOException]), failure)
        assert(noReader.isEmpty)
        val reader = Slot[UringReadable]()
        io.perform(ops.openFifoForReading(fifo.toString, reader))
        val r = reader.clear()
        val writer = Slot[UringWritable]()
        io.perform(ops.openFifoForWriting(fifo.toString, writer))
        val w = writer.clear()
        val buf = ByteBuffer.allocate(16)
        val (reading, performed) = performing(ops.read(r, buf))
        AsyncOperations.sleep(20)
        reading.cancel()
        reading.awaitResult: Unit
        assert(performed.get().nn.failed.toOption.exists(_.isInstanceOf[CancellationException]), performed.get())
        rawWrite(w, "later")
        AsyncOperations.sleep(20)
        assertEquals(buf.position(), 0)
        assertEquals(rawRead(r), "later")
        close(r)
        close(w)
      }
    }
  }

  test("a read cancelled as data arrives either completes with it, or takes nothing and leaves it") {
    onPool(1) { io ?=>
      for _ <- 1 to 50 do {
        val (r, w) = pipe()
        val buf = ByteBuffer.allocate(16)
        val (reader, performed) = performing(ops.read(r, buf))
        AsyncOperations.sleep(5)
        rawWrite(w, "data")
        reader.cancel()
        reader.awaitResult: Unit
        performed.get() match {
          case Success(_)                        => assertEquals(text(buf), "data")
          case Failure(_: CancellationException) =>
            assertEquals(buf.position(), 0, "cancelled, so it took nothing")
            assertEquals(rawRead(r), "data", "and the bytes are still there")
          case other => fail(s"unexpected: $other")
        }
        close(r)
        close(w)
      }
    }
  }

  test("a take-back meant for an earlier performance of an op leaves a later one alone") {
    onPool(1) { io ?=>
      for _ <- 1 to 100 do {
        val (r, w) = pipe()
        val buf = ByteBuffer.allocate(16)
        val read = ops.read(r, buf)
        val command = read.asInstanceOf[Command]
        rawWrite(w, "data")
        io.perform(read)
        val first = command.performance
        buf.clear()
        val (second, performed) = performing(read)
        AsyncOperations.sleep(5)
        val foreign = new Thread(() => command.owner.nn.takeBack(command, first))
        foreign.start()
        foreign.join()
        AsyncOperations.sleep(5)
        rawWrite(w, "more")
        second.awaitResult: Unit
        assertEquals(performed.get(), Success(()), "the later performance was taken back")
        assertEquals(text(buf), "more")
        close(r)
        close(w)
      }
    }
  }

  test("a timed read cancelled from another worker as its data arrives ends, and does not hang") {
    onPool(2) { io ?=>
      for _ <- 1 to 2000 do {
        val (r, w) = pipe()
        val buf = ByteBuffer.allocate(16)
        val reader = Future(io.perform(ops.read(r, buf), 5.seconds))
        AsyncOperations.sleep(1)
        val writer = new Thread(() => rawWrite(w, "x"))
        writer.start()
        reader.cancel()
        val until = System.nanoTime() + 10_000_000_000L
        while reader.poll().isEmpty && System.nanoTime() < until do AsyncOperations.sleep(1)
        assert(reader.poll().isDefined, "the cancelled read never ended")
        writer.join()
        close(r)
        close(w)
      }
    }
  }

  private final class ReadInKernelWorker(val handle: UringHandle, buf: ByteBuffer)
      extends Transfer(Array(buf), copyIn = false) {
    override protected def direction = Command.Direction.Reading
    protected def failing: String = s"read from $handle"
    protected def prepare(sqe: Ptr[uring.io_uring_sqe]): Unit = {
      import uring.*
      import uringStructOps.*
      val b = staging.begin()(0)
      io_uring_prep_read(sqe, fd, NativeBuffer.atPosition(b), b.remaining().toUInt, Results.currentPosition)
      val IoSqeAsync = 1 << 4
      sqe.flags = IoSqeAsync.toUByte
    }
  }

  test("a ring that fails has the kernel let go of a read running in its worker before the read's buffer is back") {
    val buf = ByteBuffer.allocate(16)
    var read: Command | Null = null
    val performed = new AtomicReference[Try[Unit] | Null](null)
    val leftInPipe = new java.util.concurrent.atomic.AtomicInteger(-2)
    val failure = Try(onPool(1) { io ?=>
      val (r, w) = pipe()
      // - blocking, so that the kernel's worker waits in the read
      assertEquals(fcntl.fcntl(r.fd, fcntl.F_SETFL, 0), 0)
      read = ReadInKernelWorker(r, buf)
      Future(performed.set(Try(io.perform(read.nn))))
      AsyncOperations.sleep(50)
      io.asInstanceOf[UringScheduler].failRing(new java.io.IOException("failed for the test"))
      // - the ring is torn down, the read failed: bytes written now must stay in the pipe
      val late = "late".getBytes("UTF-8")
      unistd.write(w.fd, late.asInstanceOf[ByteArray].at(0), late.length.toUSize): Unit
      Thread.sleep(50)
      fcntl.fcntl(r.fd, fcntl.F_SETFL, fcntl.O_NONBLOCK): Unit
      val left = new Array[Byte](16)
      leftInPipe.set(unistd.read(r.fd, left.asInstanceOf[ByteArray].at(0), 16.toUSize).toInt)
    })
    assert(failure.failed.toOption.exists(_.isInstanceOf[java.io.IOException]), failure)
    assert(performed.get().nn.failed.toOption.exists(_.isInstanceOf[java.io.IOException]), performed.get())
    assert(!UringScheduler.kept.contains(read), "the kernel did not say it had let go of the read")
    assertEquals(leftInPipe.get(), 4, "the bytes written after the failure were taken")
    assertEquals(buf.position(), 0)
    assert(buf.array().forall(_ == 0), "the kernel wrote into the buffer after it was given back")
  }

  test("a pooled read started again as a close's commands are dropped, then cancelled, takes nothing") {
    onPool(1) { io ?=>
      for round <- 1 to 20 do {
        val pool = UringBackend.pool(16, 1)
        val (r1, w1) = pipe()
        val (r2, w2) = pipe()
        val first = Future(Try(io.perform(ops.read(r1, pool, Slot[Received]()))))
        val (second, performed) = performing(ops.read(r2, pool, Slot[Received]()))
        Future {
          close(r1)
          second.cancel()
        }.await
        second.awaitResult: Unit
        first.awaitResult: Unit
        rawWrite(w2, "x")
        AsyncOperations.sleep(20)
        assertEquals(rawRead(r2), "x", s"round $round: the cancelled read ($performed) took the byte")
        assertEquals(pool.available, 1)
        List(r2, w1, w2).foreach(close)
        pool.close()
      }
    }
  }

  test("a ring that fails with an accept waiting its turn fails that accept too, rather than submitting to it") {
    val second = new AtomicReference[Try[Unit] | Null](null)
    val failure = Try(onPool(1) { io ?=>
      val into = Slot[ListenerHandle]()
      io.perform(ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), into))
      val l = into.clear()
      Future(Try(io.perform(ops.accept(l, Slot[ConnectionHandle]()))))
      AsyncOperations.sleep(20)
      Future(second.set(Try(io.perform(ops.accept(l, Slot[ConnectionHandle]())))))
      AsyncOperations.sleep(20)
      io.asInstanceOf[UringScheduler].failRing(new java.io.IOException("failed for the test"))
      AsyncOperations.sleep(50)
    })
    assert(failure.failed.toOption.exists(_.isInstanceOf[java.io.IOException]), failure)
    assert(second.get() != null && second.get().nn.isFailure, s"the waiting accept: ${second.get()}")
  }

  test("an entry counts as pending until a submission takes it, also once the queue's counters wrap") {
    import uringStructOps.*
    val ring = new Ring(8, 16)
    try {
      var id = 100L
      var before = Seq.empty[(Ptr[uring.io_uring_sqe], Long)]
      for round <- 1 to 5 do {
        val entries = Seq.fill(7) {
          val sqe = ring.nextSqe()
          ring.nop(sqe)
          id += 1
          sqe.user_data = id.toULong
          (sqe, id)
        }
        entries.foreach((sqe, id) => assert(ring.pending(sqe, id), s"round $round: prepared, not yet submitted"))
        // - the slots the last round's entries had, now another entry's
        before.foreach((sqe, id) => assert(!ring.pending(sqe, id), s"round $round: taken by the kernel, slot reused"))
        ring.await(System.nanoTime(), hasWork = () => true, spin = false)((_, _) => ())
        entries.foreach((sqe, id) => assert(!ring.pending(sqe, id), s"round $round: submitted"))
        before = entries
      }
    } finally ring.close()
  }

  test("a cancel of a read whose slot another entry now has cancels that read, and leaves the other entry") {
    onPool(1) { io ?=>
      val (ra, wa) = pipe()
      val bufA = ByteBuffer.allocate(16)
      val readA = ops.read(ra, bufA).asInstanceOf[Command]
      val (reader, performed) = performing(readA)
      AsyncOperations.sleep(20)
      assertEquals(readA.state, Command.Stage.Submitted)
      var other: (Future[Unit], UringReadable, UringWritable) | Null = null
      var rounds = 0
      while other == null && rounds < 600 do {
        rounds += 1
        val (rb, wb) = pipe()
        val readB = ops.read(rb, ByteBuffer.allocate(16)).asInstanceOf[Command]
        val b = Future(io.perform(readB))
        Future {
          // - B prepared in A's old slot, not yet submitted: cancel A now, on the same worker
          if readB.sqe == readA.sqe && readB.state == Command.Stage.Prepared then {
            other = (b, rb, wb)
            reader.cancel()
          }
        }.await
        if other == null then {
          rawWrite(wb, "b")
          b.await
          close(rb)
          close(wb)
        }
      }
      assert(other != null, s"no slot reused in $rounds rounds")
      reader.awaitResult: Unit
      assert(performed.get().nn.failed.toOption.exists(_.isInstanceOf[CancellationException]), performed.get())
      rawWrite(wa, "a")
      AsyncOperations.sleep(20)
      assertEquals(bufA.position(), 0)
      assertEquals(rawRead(ra), "a")
      val (b, rb, wb) = other.nn
      rawWrite(wb, "b")
      val bEnded = gears.async.withTimeoutOption(2.seconds)(b.awaitResult)
      assert(bEnded.exists(_.isSuccess), s"the other entry's read: $bEnded")
      List(ra, wa, rb, wb).foreach(close)
    }
  }

  test("a read cancelled while it waits in the kernel takes nothing written after") {
    onPool(2) { io ?=>
      val (r, w) = pipe()
      val buf = ByteBuffer.allocate(16)
      val (reader, performed) = performing(ops.read(r, buf))
      AsyncOperations.sleep(20)
      reader.cancel()
      reader.awaitResult: Unit
      assert(performed.get().nn.failed.toOption.exists(_.isInstanceOf[CancellationException]), performed.get())
      rawWrite(w, "later")
      AsyncOperations.sleep(20)
      assertEquals(buf.position(), 0)
      assertEquals(rawRead(r), "later")
      close(r)
      close(w)
    }
  }

  test("a close between a read's preparation and its submission fails the read, and completes") {
    onPool(1) { io ?=>
      for _ <- 1 to 50 do {
        val (r, w) = pipe()
        val (reader, performed) = performing(ops.read(r, ByteBuffer.allocate(16)))
        val closer = Future(close(r))
        closer.await
        reader.awaitResult: Unit
        assert(performed.get().nn.failed.toOption.exists(_.isInstanceOf[ClosedChannelException]), performed.get())
        close(w)
      }
    }
  }

  test("a close frees a listener's port as it returns, while another worker has an accept on it") {
    onPool(4) { io ?=>
      for _ <- 1 to 20 do {
        val into = Slot[ListenerHandle]()
        io.perform(ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), into))
        val l = into.clear()
        val address = ops.localAddress(l).asInstanceOf[IpSocketAddress]
        val accepts = List.fill(4)(performing(ops.accept(l, Slot[ConnectionHandle]())))
        AsyncOperations.sleep(5)
        close(l)
        val again = Slot[ListenerHandle]()
        io.perform(ops.listen(address, again))
        close(again.clear())
        for (f, performed) <- accepts do {
          f.awaitResult: Unit
          assert(performed.get().nn.failed.toOption.exists(_.isInstanceOf[ClosedChannelException]), performed.get())
        }
      }
    }
  }

  test("accepts waiting on one listener are served first come, first served, across workers") {
    onPool(4) { io ?=>
      val into = Slot[ListenerHandle]()
      io.perform(ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), into))
      val l = into.clear()
      val address = ops.localAddress(l).asInstanceOf[IpSocketAddress]
      val slots = List.fill(4)(Slot[ConnectionHandle]())
      val accepts = slots.map { slot =>
        val f = Future(io.perform(ops.accept(l, slot)))
        AsyncOperations.sleep(20)
        f
      }
      val clients = List.fill(4) {
        val client = Slot[ConnectionHandle]()
        io.perform(ops.connect(address, client))
        client.clear()
      }
      for ((f, slot), (c, i)) <- accepts.zip(slots).zip(clients.zipWithIndex) do {
        gears.async.withTimeout(2.seconds)(f.await)
        val accepted = slot.clear()
        assertEquals(ops.peerAddress(accepted), ops.localAddress(c), s"accept ${i + 1} took connection ${i + 1}")
        close(accepted)
      }
      clients.foreach(close)
      close(l)
    }
  }

  test("a close ends the peer's stream as it returns, while another worker has a read on it") {
    onPool(4) { io ?=>
      for _ <- 1 to 20 do {
        val into = Slot[ListenerHandle]()
        io.perform(ops.listen(IpSocketAddress(IpAddress.IPv4Loopback, 0), into))
        val l = into.clear()
        val accepted = Slot[ConnectionHandle]()
        val accepting = Future(io.perform(ops.accept(l, accepted)))
        val client = Slot[ConnectionHandle]()
        io.perform(ops.connect(ops.localAddress(l).asInstanceOf[IpSocketAddress], client))
        accepting.await
        val (a, b) = (accepted.clear(), client.clear())
        val (reader, _) = performing(ops.read(a, ByteBuffer.allocate(16)))
        AsyncOperations.sleep(5)
        close(a)
        val end = ByteBuffer.allocate(16)
        io.perform(ops.read(b, end), 5.seconds)
        assertEquals(end.position(), 0)
        reader.awaitResult: Unit
        close(b)
        close(l)
      }
    }
  }

  test("idle streams with pooled reads pending hold at most half the pool, however many there are") {
    onPool(2) { io ?=>
      val pool = UringBackend.pool(256, 8)
      val pipes = List.fill(12)(pipe())
      val slots = pipes.map(_ => Slot[Received]())
      val reads = pipes.zip(slots).map { case ((r, _), into) => performing(ops.read(r, pool, into)) }
      val until = System.nanoTime() + 1_000_000_000L
      while pool.available < 4 && System.nanoTime() < until do AsyncOperations.sleep(5)
      assert(pool.available >= 4, s"${pool.available} of 8 free")
      for (((_, w), into), (f, performed)) <- pipes.zip(slots).zip(reads) do {
        rawWrite(w, "x")
        f.awaitResult: Unit
        assert(performed.get().nn.isSuccess, performed.get())
        into.clear().release()
      }
      assertEquals(pool.available, 8)
      pipes.foreach((r, w) => { close(r); close(w) })
    }
  }

  test("pooled reads wait while the pool is empty, take nothing meanwhile, and all go on as buffers come back") {
    onPool(2) { io ?=>
      val pool = UringBackend.pool(16, 1)
      val (r0, w0) = pipe()
      rawWrite(w0, "a")
      val first = Slot[Received]()
      io.perform(ops.read(r0, pool, first))
      val held = first.clear()
      val pipes = List.fill(3)(pipe())
      val slots = pipes.map(_ => Slot[Received]())
      val reads = pipes.zip(slots).map { case ((r, w), into) =>
        rawWrite(w, "b")
        performing(ops.read(r, pool, into))
      }
      AsyncOperations.sleep(50)
      for (f, performed) <- reads do assert(performed.get() == null, s"it should wait, not ${performed.get()}")
      for (r, w) <- pipes do {
        assertEquals(rawRead(r), "b", "a read that waits takes nothing")
        rawWrite(w, "b")
      }
      held.release()
      var served = 0
      val until = System.nanoTime() + 5_000_000_000L
      while served < reads.length && System.nanoTime() < until do {
        slots.find(s => !s.isEmpty) match {
          case Some(into) =>
            val got = into.clear()
            assertEquals(got.buffer.remaining(), 1)
            got.release()
            served += 1
          case None => AsyncOperations.sleep(1)
        }
      }
      assertEquals(served, reads.length)
      for (f, performed) <- reads do {
        f.awaitResult: Unit
        assert(performed.get().nn.isSuccess, performed.get())
      }
      assertEquals(pool.available, 1)
      (r0, w0) :: pipes foreach ((r, w) => { close(r); close(w) })
      pool.close()
    }
  }

  test("a pooled read started again after parking counts once on its ring, and not at all once it is done") {
    onPool(1) { io ?=>
      val pool = UringBackend.pool(16, 2)
      def take(): Received = {
        val (r, w) = pipe()
        rawWrite(w, "a")
        val into = Slot[Received]()
        io.perform(ops.read(r, pool, into))
        into.clear()
      }
      val one = take()
      val (r, w) = pipe()
      val into = Slot[Received]()
      val (reader, performed) = performing(ops.read(r, pool, into))
      AsyncOperations.sleep(20)
      val two = take()
      rawWrite(w, "x")
      AsyncOperations.sleep(20)
      assertEquals(rawRead(r), "x")
      one.release()
      AsyncOperations.sleep(20)
      assert(performed.get() == null, s"it should wait, not ${performed.get()}")
      rawWrite(w, "y")
      reader.awaitResult: Unit
      assert(performed.get().nn.isSuccess, performed.get())
      into.clear().release()
      assertEquals(r.entriesOnRings, 0)
      two.release()
      close(r)
      close(w)
      pool.close()
    }
  }

  test("a pooled read waiting for a buffer is ended by a cancel, a time limit, or its handle's close") {
    onPool(2) { io ?=>
      val pool = UringBackend.pool(16, 1)
      val (r0, w0) = pipe()
      rawWrite(w0, "a")
      val first = Slot[Received]()
      io.perform(ops.read(r0, pool, first))
      val held = first.clear()
      val (r, w) = pipe()
      rawWrite(w, "b")

      val (cancelled, performed) = performing(ops.read(r, pool, Slot[Received]()))
      AsyncOperations.sleep(20)
      cancelled.cancel()
      cancelled.awaitResult: Unit
      assert(performed.get().nn.failed.toOption.exists(_.isInstanceOf[CancellationException]), performed.get())

      Try(io.perform(ops.read(r, pool, Slot[Received]()), 20.millis)) match {
        case Failure(_: java.util.concurrent.TimeoutException) => ()
        case other                                             => fail(s"expected a time-out, not $other")
      }

      val (closed, closedPerformed) = performing(ops.read(r, pool, Slot[Received]()))
      AsyncOperations.sleep(20)
      close(r)
      closed.awaitResult: Unit
      assert(
        closedPerformed.get().nn.failed.toOption.exists(_.isInstanceOf[ClosedChannelException]),
        closedPerformed.get()
      )

      held.release()
      assertEquals(pool.available, 1, "none of them holds a buffer")
      close(w)
      close(r0)
      close(w0)
      pool.close()
    }
  }
}
