package gearsbench

import gears.async.io.IpAddress
import gears.async.io.UnixAddress
import gears.async.io.internal.NativeBuffer
import gears.async.io.internal.NonBlocking
import gears.async.io.internal.PosixSockets
import gears.async.io.kqueue.KqueueHandles
import gears.async.io.kqueue.KqueueLoop
import gears.async.io.kqueue.KqueueLoop.EventQueue

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** The scenarios of `GearsBench`, written against `KqueueLoop` directly, with no reactor: a reference for what the same
  * work costs in a hand-written loop. It is written the way such a loop usually is:
  *   - one kqueue per thread;
  *   - each socket registered once for reads, level-triggered;
  *   - reads and writes called directly, with write interest registered, one-shot, only after a write would block;
  *   - a deadline timer added and deleted with `kevent`, where the reactor keeps its own in a heap;
  *   - blocking tasks run on a worker thread that signals through a pipe.
  *
  * The scenario names, counts, and output match `GearsBench`, so `bench/compare.sc` can put them side by side.
  */
object KQueueExampleBench {

  private def connectTo(address: IpAddress | UnixAddress): Int = address match {
    case unix: UnixAddress => KqueueHandles.descriptor(KqueueHandles.connect(unix))
    case ip: IpAddress     => KqueueHandles.descriptor(KqueueHandles.connect(ip))
  }

  private def listenOn(address: IpAddress | UnixAddress): Int = address match {
    case unix: UnixAddress => KqueueHandles.descriptor(KqueueHandles.listen(unix))
    case ip: IpAddress     => KqueueHandles.descriptor(KqueueHandles.listen(ip))
  }

  private inline val MessageSize = GearsBench.MessageSize
  private inline val MaxEvents = 256
  private val DeadlineTimer = 1.toUSize

  // This bench drives descriptors itself; `KqueueHandles` hands out and takes `Handle`s, so convert at its edge.
  private def closeFd(fd: Int): Unit = KqueueHandles.close(KqueueHandles.handle(fd))

  private def pipeFds(): (Int, Int) = {
    val (readEnd, writeEnd) = KqueueHandles.pipe()
    (KqueueHandles.descriptor(readEnd), KqueueHandles.descriptor(writeEnd))
  }

  private def register(kq: Int, fd: Int, read: Boolean, oneShot: Boolean): Unit =
    KqueueLoop.createAndRegisterEvents(kq, 1)(events =>
      KqueueLoop.addFile(events(0), fd, read = read, clear = false, oneShot = oneShot)
    )

  private def unregister(kq: Int, fd: Int, read: Boolean): Unit = KqueueLoop.deleteFileIfOpen(kq, fd, read)

  /** Polls `kq` until it reports `fd` writable, ignoring anything else it reports meanwhile. */
  private def waitUntilWritable(kq: Int, events: EventQueue, fd: Int): Unit = {
    register(kq, fd, read = false, oneShot = true)
    var writable = false
    while !writable do {
      val polled = KqueueLoop.pollEventsForever(kq, events, MaxEvents)
      var i = 0
      while i < polled do {
        val event = events(i)
        if KqueueLoop.isWriteEvent(event) && KqueueLoop.fileIdent(event) == fd then writable = true
        i += 1
      }
    }
  }

  /** Writes all of `buf`, waiting for writability whenever a write would block. */
  private def writeAll(kq: Int, events: EventQueue, fd: Int, buf: ByteBuffer): Unit =
    while buf.hasRemaining() do if NonBlocking.write(fd, buf) < 0 then waitUntilWritable(kq, events, fd)

  /** Connects to `address`, waiting for the connection to complete, and registers it for reads. */
  private def connect(kq: Int, events: EventQueue, address: IpAddress | UnixAddress): Int = {
    val fd = connectTo(address)
    waitUntilWritable(kq, events, fd)
    PosixSockets.checkConnect(fd)
    register(kq, fd, read = true, oneShot = false)
    fd
  }

  /** Opens a kqueue for one scenario, runs `body` with it and an event buffer, and closes it. */
  private def withLoop(body: (Int, EventQueue) => Unit): Unit = {
    val kq = KqueueLoop.open()
    try KqueueLoop.pollQueue(MaxEvents)(events => body(kq, events))
    finally KqueueLoop.close(kq)
  }

  private def pingPong(address: IpAddress | UnixAddress, count: Int, deadline: Boolean): Unit = withLoop {
    (kq, events) =>
      val fd = connect(kq, events, address)
      val zone = Zone.open()
      try {
        val buf = NativeBuffer.allocate(MessageSize)(using zone)
        var i = 0
        while i < count do {
          if deadline then
            KqueueLoop.createAndRegisterEvents(kq, 1)(e => KqueueLoop.addTimerOneShot(e(0), DeadlineTimer, 60_000))
          buf.clear()
          writeAll(kq, events, fd, buf)
          buf.clear()
          while buf.hasRemaining() do {
            KqueueLoop.pollEventsForever(kq, events, MaxEvents) // only this socket is registered for reads
            if NonBlocking.read(fd, buf) < 0 then throw new IOException("The server closed the connection")
          }
          if deadline then KqueueLoop.createAndRegisterEvents(kq, 1)(e => KqueueLoop.deleteTimer(e(0), DeadlineTimer))
          i += 1
        }
      } finally {
        zone.close()
        closeFd(fd)
      }
  }

  private def concurrent(address: IpAddress | UnixAddress, count: Int, connections: Int): Unit = withLoop {
    (kq, events) =>
      val each = math.max(1, count / connections)
      val zone = Zone.open()
      val open = mutable.HashMap.empty[Int, (ByteBuffer, Array[Int])] // fd -> its buffer and remaining round trips
      try {
        for _ <- 1 to connections do {
          val fd = connect(kq, events, address)
          val buf = NativeBuffer.allocate(MessageSize)(using zone)
          writeAll(kq, events, fd, buf) // the first message
          buf.clear()
          open(fd) = (buf, Array(each))
        }
        while open.nonEmpty do {
          val polled = KqueueLoop.pollEventsForever(kq, events, MaxEvents)
          var i = 0
          while i < polled do {
            val event = events(i)
            val fd = KqueueLoop.fileIdent(event)
            open.get(fd) match {
              case Some((buf, remaining)) if KqueueLoop.isReadEvent(event) =>
                if NonBlocking.read(fd, buf) < 0 then throw new IOException("The server closed the connection")
                if !buf.hasRemaining() then {
                  remaining(0) -= 1
                  if remaining(0) == 0 then {
                    open.remove(fd)
                    closeFd(fd)
                  } else {
                    buf.clear()
                    writeAll(kq, events, fd, buf)
                    buf.clear()
                  }
                }
              case _ => ()
            }
            i += 1
          }
        }
      } finally {
        open.keys.foreach(closeFd)
        zone.close()
      }
  }

  /** The `connections` scenario: a new connection for each round trip, on one kqueue. */
  private def newConnections(address: IpAddress | UnixAddress, count: Int): Unit = withLoop { (kq, events) =>
    val zone = Zone.open()
    try {
      val buf = NativeBuffer.allocate(MessageSize)(using zone)
      var i = 0
      while i < count do {
        val fd = connect(kq, events, address)
        try {
          buf.clear()
          writeAll(kq, events, fd, buf)
          buf.clear()
          while buf.hasRemaining() do {
            KqueueLoop.pollEventsForever(kq, events, MaxEvents) // only this socket is registered for reads
            if NonBlocking.read(fd, buf) < 0 then throw new IOException("The server closed the connection")
          }
        } finally closeFd(fd) // which also removes its filters from the kqueue
        i += 1
      }
    } finally zone.close()
  }

  private def pipelined(address: IpAddress | UnixAddress, count: Int): Unit = withLoop { (kq, events) =>
    val fd = connect(kq, events, address)
    val zone = Zone.open()
    try {
      val total = count.toLong * MessageSize
      val out = NativeBuffer.allocate(MessageSize)(using zone)
      val in = NativeBuffer.allocate(8192)(using zone)
      out.limit(0) // nothing half-written yet
      var sent = 0
      var received = 0L
      var writeBlocked = false
      while received < total do {
        // Write messages, each with its own write, until the socket is full or all are sent.
        while !writeBlocked && (out.hasRemaining() || sent < count) do {
          if !out.hasRemaining() then {
            out.clear()
            sent += 1
          }
          if NonBlocking.write(fd, out) < 0 then {
            writeBlocked = true
            register(kq, fd, read = false, oneShot = true)
          }
        }
        val polled = KqueueLoop.pollEventsForever(kq, events, MaxEvents)
        var i = 0
        while i < polled do {
          val event = events(i)
          if KqueueLoop.isWriteEvent(event) then writeBlocked = false
          else if KqueueLoop.isReadEvent(event) then {
            in.clear()
            if NonBlocking.read(fd, in) < 0 then throw new IOException("The server closed the connection")
            received += in.position()
          }
          i += 1
        }
      }
    } finally {
      zone.close()
      closeFd(fd)
    }
  }

  /** The `timers` scenario with kernel timers: one `kevent` call to add each and one to delete each that is cancelled.
    */
  private def timerStress(count: Int): Unit = withLoop { (kq, events) =>
    var nextId = 1L
    var done = 0
    while done < count do {
      val batch = math.min(GearsBench.TimerBatch, count - done)
      val firstId = nextId
      var i = 0
      while i < batch do {
        val milliseconds = if i % 2 == 0 then 0 else 60_000
        val id = nextId.toUSize
        KqueueLoop.createAndRegisterEvents(kq, 1)(e => KqueueLoop.addTimerOneShot(e(0), id, milliseconds))
        nextId += 1
        i += 1
      }
      var fired = 0
      val firing = (batch + 1) / 2
      while fired < firing do {
        val polled = KqueueLoop.pollEventsForever(kq, events, MaxEvents)
        var j = 0
        while j < polled do {
          if KqueueLoop.isTimerEvent(events(j)) then fired += 1
          j += 1
        }
      }
      i = 1
      while i < batch do {
        val id = (firstId + i).toUSize
        KqueueLoop.createAndRegisterEvents(kq, 1)(e => KqueueLoop.deleteTimer(e(0), id))
        i += 2
      }
      done += batch
    }
  }

  /** Starts a worker thread that runs the tasks queued on the returned queue, writing a byte to `writeEnd` after each.
    */
  private def blockingWorker(writeEnd: Int): LinkedBlockingQueue[Runnable] = {
    val tasks = new LinkedBlockingQueue[Runnable]()
    val worker = new Thread(
      () => {
        val signal = NativeBuffer.allocate(1)(using Zone.open()) // kept for the worker's life
        while true do {
          tasks.take().run()
          signal.clear()
          NonBlocking.write(writeEnd, signal) // a full pipe already guarantees a wake-up
        }
      },
      "bench-worker"
    )
    worker.setDaemon(true)
    worker.start()
    tasks
  }

  /** Runs a trivial task on the worker and polls `kq`, which has `readEnd` registered, until the worker signals. */
  private def runBlocking(
      kq: Int,
      events: EventQueue,
      tasks: LinkedBlockingQueue[Runnable],
      readEnd: Int,
      drain: ByteBuffer
  ): Unit = {
    tasks.put(() => ())
    var signalled = false
    while !signalled do {
      KqueueLoop.pollEventsForever(kq, events, MaxEvents)
      drain.clear()
      signalled = NonBlocking.read(readEnd, drain) > 0
    }
  }

  private def blocking(count: Int): Unit = withLoop { (kq, events) =>
    val (readEnd, writeEnd) = pipeFds()
    val tasks = blockingWorker(writeEnd)
    register(kq, readEnd, read = true, oneShot = false)
    val zone = Zone.open()
    try {
      val drain = NativeBuffer.allocate(64)(using zone)
      var done = 0
      while done < count do {
        runBlocking(kq, events, tasks, readEnd, drain)
        done += 1
      }
    } finally {
      zone.close()
      closeFd(readEnd)
      closeFd(writeEnd)
    }
  }

  /** A trivial blocking task on the worker thread and a round trip in turn, on one kqueue. */
  private def blockingIo(address: IpAddress | UnixAddress, count: Int): Unit = withLoop { (kq, events) =>
    val fd = connect(kq, events, address)
    val (readEnd, writeEnd) = pipeFds()
    val tasks = blockingWorker(writeEnd)
    register(kq, readEnd, read = true, oneShot = false)
    val zone = Zone.open()
    try {
      val drain = NativeBuffer.allocate(64)(using zone)
      val buf = NativeBuffer.allocate(MessageSize)(using zone)
      var i = 0
      while i < count do {
        runBlocking(kq, events, tasks, readEnd, drain)
        buf.clear()
        writeAll(kq, events, fd, buf)
        buf.clear()
        while buf.hasRemaining() do {
          KqueueLoop.pollEventsForever(kq, events, MaxEvents)
          if NonBlocking.read(fd, buf) < 0 then throw new IOException("The server closed the connection")
        }
        i += 1
      }
    } finally {
      zone.close()
      closeFd(fd)
      closeFd(readEnd)
      closeFd(writeEnd)
    }
  }

  /** The echo server: echoes every connection's bytes until it closes. A connection whose echo would block stops being
    * read until the echo has been written.
    */
  private def serve(
      address: IpAddress | UnixAddress,
      listening: CountDownLatch,
      failure: AtomicReference[Throwable | Null]
  ): Unit =
    try {
      val kq = KqueueLoop.open()
      val server =
        try listenOn(address)
        finally listening.countDown()
      register(kq, server, read = true, oneShot = false)
      val zone = Zone.open() // connection buffers, kept for the life of the process
      val pending = mutable.HashMap.empty[Int, ByteBuffer] // fd -> its buffer; position to limit is not yet echoed
      val spare = mutable.Stack.empty[ByteBuffer] // buffers of closed connections, for the next ones

      def close(fd: Int): Unit = {
        pending.remove(fd).foreach(spare.push)
        closeFd(fd)
      }

      /** Writes what is left of `fd`'s echo; if that would block, waits for writability instead of reading. */
      def flush(fd: Int, buf: ByteBuffer): Unit = {
        var blocked = false
        while !blocked && buf.hasRemaining() do blocked = NonBlocking.write(fd, buf) < 0
        if blocked then {
          unregister(kq, fd, read = true)
          register(kq, fd, read = false, oneShot = true)
        }
      }

      KqueueLoop.pollQueue(MaxEvents) { events =>
        while true do {
          val polled = KqueueLoop.pollEventsForever(kq, events, MaxEvents)
          var i = 0
          while i < polled do {
            val event = events(i)
            val fd = KqueueLoop.fileIdent(event)
            if fd == server then {
              var client = NonBlocking.accept(server)
              while client >= 0 do {
                val buf = if spare.nonEmpty then spare.pop() else NativeBuffer.allocate(8192)(using zone)
                buf.limit(0)
                pending(client) = buf
                register(kq, client, read = true, oneShot = false)
                client = NonBlocking.accept(server)
              }
            } else
              pending.get(fd) match {
                case Some(buf) =>
                  try
                    if KqueueLoop.isWriteEvent(event) then {
                      flush(fd, buf)
                      if !buf.hasRemaining() then register(kq, fd, read = true, oneShot = false)
                    } else if !buf.hasRemaining() then {
                      buf.clear()
                      val read = NonBlocking.read(fd, buf)
                      buf.flip()
                      if read < 0 then close(fd) else flush(fd, buf)
                    }
                  catch case _: IOException => close(fd) // a client that goes away ends only its own connection
                case None => ()
              }
            i += 1
          }
        }
      }
    } catch {
      case t: Throwable =>
        failure.set(t)
        listening.countDown()
    }

  private def startServer(address: IpAddress | UnixAddress): Unit = {
    val listening = new CountDownLatch(1)
    val failure = new AtomicReference[Throwable | Null](null)
    val thread = new Thread(() => serve(address, listening, failure), "bench-server")
    thread.setDaemon(true) // it serves until the process exits
    thread.start()
    listening.await()
    val failed = failure.get()
    if failed != null then throw failed
  }

  /** Runs the named scenarios, or all of them, each with `GearsBench`'s base count times `scale`. */
  def run(names: Seq[String], scale: Double, connections: Int, address: IpAddress | UnixAddress): Unit = {
    val known = GearsBench.scenarios.map(_.name)
    for name <- names if !known.contains(name) do
      throw new IllegalArgumentException(s"Unknown scenario `$name`; try ${known.mkString(", ")}")
    // `mixed`, `fairness`, `starvation` and the compute variants of `concurrent` measure gears scheduling against
    // compute work, and `pingpong-fresh-ops` building ops through the reactor interface, none of which a hand-written
    // loop has a counterpart for; nor does it write `pingpong-pooled`, `large` and `large-pooled`, which measure the
    // backend's pool and the copies of large reads, nor the `rearm` scenarios, which measure re-arming a gears timer.
    val gearsOnly =
      Set(
        "mixed",
        "pingpong-fresh-ops",
        "pingpong-pooled",
        "large",
        "large-pooled",
        "fairness",
        "starvation",
        "concurrent-compute",
        "concurrent-offload",
        "rearm-cancel",
        "rearm-later",
        "rearm-earlier"
      )
    val selected = GearsBench.scenarios.filter(s => !gearsOnly(s.name) && (names.isEmpty || names.contains(s.name)))
    address match {
      case UnixAddress(path) => Files.deleteIfExists(Paths.get(path))
      case _                 => ()
    }
    try {
      startServer(address)
      println(f"bench: ${"scenario"}%-18s ${"ops"}%10s ${"seconds"}%9s ${"ops/s"}%12s ${"us/op"}%8s")
      for scenario <- selected do {
        val count = math.max(1, (scenario.baseCount * scale).toInt)
        val start = System.nanoTime()
        scenario.name match {
          case "pingpong"          => pingPong(address, count, deadline = false)
          case "pingpong-deadline" => pingPong(address, count, deadline = true)
          case "concurrent"        => concurrent(address, count, connections)
          case "connections"       => newConnections(address, count)
          case "pipelined"         => pipelined(address, count)
          case "blocking"          => blocking(count)
          case "blocking-io"       => blockingIo(address, count)
          case "timers"            => timerStress(count)
        }
        val seconds = (System.nanoTime() - start) / 1e9
        println(
          f"bench: ${scenario.name}%-18s $count%10d $seconds%9.3f ${count / seconds}%12.0f ${seconds * 1e6 / count}%8.2f"
        )
      }
    } finally
      address match {
        case UnixAddress(path) => Files.deleteIfExists(Paths.get(path))
        case _                 => ()
      }
  }
}
