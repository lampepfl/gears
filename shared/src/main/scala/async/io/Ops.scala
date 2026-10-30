package gears.async.io

import java.nio.ByteBuffer

/** A mutable outcome of an operation, written by the op and taken by the caller with `clear`. Like a buffer, it belongs
  * to the backend while the op is being performed: the caller clears it once `perform` has returned, which is what
  * makes the op's write visible. An op performed with a slot not yet cleared fails with an `IllegalArgumentException`,
  * doing nothing, since what it opened or took would have nowhere to go.
  */
final class Slot[A <: AnyRef] {
  private var value: A | Null = null

  def clear(): A = {
    val v = value
    if v == null then throw new IllegalStateException("Nothing has been written to this slot yet")
    value = null
    v.nn
  }

  private[io] def set(outcome: A): Unit = {
    if value != null then throw new IllegalStateException("Slot was already set")
    value = outcome.nn
  }

  /** Whether nothing has been written here, or what was has been cleared: what an op checks as it is performed. */
  private[io] def isEmpty: Boolean = value == null
}

/** Marks an op that may be performed again once `perform` has returned, instead of building a new one each time: an
  * accept loop can perform one accept op again and again, and a connection one read on its buffer. What the op writes,
  * into its buffers and slots, is its latest performance's.
  */
trait Repeatable

/** The ops every backend can build, as values of its `Op` type, on I/O objects named by its handles, values of its
  * `Handle` type, `H` here (see `Backend.Handle`): reads and writes of a stream, timers, and closing. Buffers are used
  * between position and limit, and belong to the backend while the op is being performed, until `perform` returns. Any
  * buffer is taken, on every backend: on the heap or not, and a read-only one to write from, though never to read into,
  * which fails with an `IllegalArgumentException` as the op is built; a backend whose system calls need memory of their
  * own copies through it, so that buffers with native memory behind them (`Backend.pool`'s, or on Scala Native a
  * `PointerBuffer`'s) are the ones that cost no copy.
  *
  * A handle's kind is in its type, a subtype of `H`, so that an op on the wrong kind of handle does not compile: a
  * stream is read through a `Readable` and written through a `Writable`, the kinds every backend has. The other kinds
  * are declared by the ops of the capability that opens them: a `Connection` (a connected stream socket, from
  * `StreamSocketOps`) and a `File` (from `FileOps`) are both readable and writable; a `Listener` accepts; datagram
  * sockets, bound (`BoundDatagram`, `UnixBoundDatagram`) or connected (`ConnectedDatagram`, `UnixConnectedDatagram`),
  * send and receive packets. Every handle is closed with `close`. The opener of each kind writes a handle of that kind
  * into its slot, so a program holds the kind it opened.
  *
  * Handles are opened by ops too, each kind by the capability that has it (`IpStreamOps.connect`, `PipeOps.pipe`,
  * `FileOps.open` and the rest), since opening may wait, for a connection, a bind or the disk, and some environments
  * can only open asynchronously. Such an op writes what it opened into a slot when it completes; one that fails, is
  * cancelled or times out leaves its slot empty and nothing open, so a handle is the caller's from the moment it is
  * taken from the slot, and the caller closes it with `close`. Failures are `java.io.IOException`s.
  *
  * At most one op reading a stream (a read) and one writing it is performed at a time, from whichever future: another
  * performed meanwhile fails with an `IllegalArgumentException`, doing nothing, since two reads would take the stream's
  * bytes in no order, and two writes would interleave theirs. A datagram socket's receives and sends, each a whole
  * packet, and a listener's accepts, each a whole connection, take turns instead, as Go queues a socket's concurrent
  * callers: one performed while another receiving, sending or accepting is under way on the handle waits behind it, in
  * the order they came, and goes on once it finishes, its time limit running meanwhile, and a cancel taking it out of
  * the queue. An op that is not `Repeatable`, every opener and `close` among them, is performed once; performed again
  * it throws `IllegalStateException`.
  */
trait Ops[Op, H <: AnyRef] {

  /** A handle that can be read as a stream: a connection, a pipe's or FIFO's read end, or a file. */
  type Readable <: H

  /** A handle that can be written as a stream: a connection, a pipe's or FIFO's write end, or a file. */
  type Writable <: H

  /** Fills `buf`, which must have room. Completes once at least one byte has been read, or at end of stream, when
    * nothing was added: compare the buffer's position before and after. A read into a buffer with no room fails with an
    * `IllegalArgumentException` as it is performed, reading nothing, since it could only add nothing, which means the
    * end of the stream.
    */
  def read(handle: Readable, buf: ByteBuffer): Op & Repeatable

  /** Drains `buf`. Completes once at least one byte has been written; perform it again while `buf` has bytes remaining.
    * With nothing remaining, completes at once, writing nothing.
    */
  def write(handle: Writable, buf: ByteBuffer): Op & Repeatable

  /** Fills `bufs` in order, each between position and limit, as the other `read` fills one (a scattering read):
    * completes once at least one byte has been read, each buffer filled before the next is begun, or at end of stream,
    * adding nothing. Fails with an `IllegalArgumentException`, reading nothing, if no buffer has room. Whether it costs
    * a copy more than reading into one is the backend's: kqueue reads with `readv`, NIO with its scattering channels.
    */
  def read(handle: Readable, bufs: Array[ByteBuffer]): Op & Repeatable

  /** Drains `bufs` in order, each between position and limit, as the other `write` drains one (a gathering write), so
    * that a frame's header and its payload go in one call: completes once at least one byte has been written, each
    * buffer drained before the next is begun, and at once, writing nothing, if none has bytes remaining.
    */
  def write(handle: Writable, bufs: Array[ByteBuffer]): Op & Repeatable

  /** Completes once `milliseconds` have passed (see the other `timer`). */
  final def timer(milliseconds: Int): Op & Repeatable = {
    require(milliseconds >= 0, s"A timer's time is not negative: $milliseconds ms")
    timer(scala.concurrent.duration.Duration(milliseconds.toLong, java.util.concurrent.TimeUnit.MILLISECONDS))
  }

  /** Completes once `duration` has passed since each submission, never before: to the nanosecond the backend can wait,
    * which on kqueue is what `kevent` takes, and on NIO the millisecond its selector waits in. A time limit on its
    * submission shorter than `duration` fails it with `TimeoutException` when the limit comes, as any op's does; one no
    * shorter leaves it to complete. Not negative.
    */
  def timer(duration: scala.concurrent.duration.FiniteDuration): Op & Repeatable

  /** A timer that completes at `nanoTime`, on `System.nanoTime`'s clock, a deadline that may be moved while it is
    * pending (`Deadline.moveTo`), from any thread, so that a timer re-armed again and again, as QUIC's loss and pacing
    * timers are for almost every packet, is one op performed once rather than one cancelled and performed anew each
    * time. Performed again, it completes at its deadline as it stands then, at once if that has passed. Its time limit
    * is as `timer`'s.
    */
  def deadlineTimer(nanoTime: Long): Op & Repeatable & Deadline

  /** Fills a buffer of `pool`'s, from `Backend.pool`, as the other `read` fills the caller's: completes once at least
    * one byte has been read, or at the end of the stream, writing what it read into `into`, which must be empty, as a
    * `Received` to release. Fails if `pool` has no buffer free when the read needs one; a backend that controls its
    * source's flow may instead stop taking bytes while none is free, so that the read waits (see `BufferPool`). A
    * completion-based backend, such as Network.framework's or io_uring's, receives straight into the pool's buffer and
    * hands it over, where a read into the caller's buffer costs it a copy; one whose facility hands it chunks of its
    * own, as Node's streams do, hands such a chunk over.
    */
  def read(handle: Readable, pool: BufferPool, into: Slot[Received]): Op & Repeatable

  /** Closes `handle`, completing once it is closed. The ops under way on it, from whichever future, have failed with an
    * `IOException`, a closed channel's (`java.nio.channels.ClosedChannelException` on the JVM and Scala Native, naming
    * the handle), before this completes, as the JDK fails a thread blocked on a channel that is closed: none of them
    * can succeed once the close has begun, and a `perform` waiting on one in another future throws once that future
    * runs again, perhaps after the close's `perform` returns. Its number is then free for reuse, and a listener's port
    * for listening on again. A Unix socket path that a listener or datagram socket bound is removed with it, if the
    * file there is still its own, not one a later listener bound in its place: checked, then removed, as no system call
    * removes a file only if it is a given one, so a listener binding the path between the two, a few microseconds,
    * loses its file. Fails if `handle` is not open, with the failure an op submitted on a closed handle has: a closed
    * channel's, as above.
    */
  def close(handle: H): Op
}

/** The ops of stream sockets, IP or Unix, once opened (see `IpStreamOps` and `UnixStreamOps`, which open them). */
trait StreamSocketOps[Op, H <: AnyRef] extends Ops[Op, H] {

  /** A connected stream socket, IP or Unix, from `connect` or `accept`: read, written and shut down for writing. */
  type Connection <: Readable & Writable

  /** A listening stream socket, IP or Unix, from `listen`, which accepts connections. */
  type Listener <: H

  /** Accepts one connection on a listening socket. Completes after writing the new connection into `into`, which must
    * be empty.
    */
  def accept(handle: Listener, into: Slot[Connection]): Op & Repeatable

  /** Accepts the connections pending on a listening socket, as `accept` does one: completes once at least one has been
    * accepted, after writing them, at most `max`, into `into`, which must be empty. A server under a burst of
    * connections takes them in one op each time the listener is ready rather than one op apiece, which keeps its listen
    * queue from filling, and the kernel from resetting what arrives meanwhile, while its other work runs.
    */
  def acceptAll(handle: Listener, into: Slot[Array[Connection]], max: Int): Op & Repeatable

  /** Shuts down a connected stream socket for writing, as `shutdown(SHUT_WR)` does, leaving it open for reading: what
    * was written before is still sent, then the end of the stream, which the peer reads as it would a close. Completes
    * once the end is queued; writes after it fail. A server that closes once it has replied shuts down first and reads
    * until the client's end before closing, since closing with bytes from the peer unread resets the connection, and a
    * reset can discard the reply the peer has not read yet; a client shuts down once its request is out, so that the
    * server sees where it ends.
    */
  def shutdownOutput(handle: Connection): Op

  /** The address a listener or a connection is bound to, an IP one with the port it got if it asked for port 0, or the
    * path a Unix one is bound to, empty for one with none. Not an op: every backend answers at once, since opening was
    * the op, completed once the socket was bound. Fails with an `IOException` on a closed handle.
    */
  def localAddress(handle: Listener | Connection): IpAddress | UnixAddress

  /** The address of a connection's peer: the address connected to, or for one accepted, the client's, the empty path
    * for a Unix client with none. Not an op, as `localAddress`. Fails with an `IOException` on a closed handle.
    */
  def peerAddress(handle: Connection): IpAddress | UnixAddress
}

/** The ops that open IP stream sockets, for a backend that has them (see `IpStreams`), used once open as any stream
  * socket is (`StreamSocketOps`, and `read` and `write`).
  */
trait IpStreamOps[Op, H <: AnyRef] extends StreamSocketOps[Op, H] with SocketOptionOps[Op, H] {

  /** Opens a stream socket and connects it to `address`. Completes once connected, after writing the socket into
    * `into`, which must be empty; fails if the connection is refused or cannot be made.
    */
  final def connect(address: IpAddress, into: Slot[Connection]): Op = connect(address, SocketOptions.default, into)

  /** As the other `connect`, the socket opening with `options` (see `SocketOptions`). */
  def connect(address: IpAddress, options: SocketOptions, into: Slot[Connection]): Op

  /** Opens a stream socket listening on `address`. Completes once it is listening, after writing it into `into`, which
    * must be empty; fails if the address is in use or cannot be bound. A port another listener holds is in use, but not
    * one whose earlier connections linger in `TIME_WAIT`, so a restarted server need not wait for them to expire: every
    * backend binds as `SO_REUSEADDR` does, as Go, libuv and Rust's standard library always do, since Network.framework
    * cannot refuse such a port.
    */
  final def listen(address: IpAddress, into: Slot[Listener]): Op = listen(address, SocketOptions.default, into)

  /** As the other `listen`, the listener opening with `options`, and each connection it accepts with those of them that
    * apply to a connection, `NoDelay` and `KeepAlive` (see `SocketOptions`).
    */
  def listen(address: IpAddress, options: SocketOptions, into: Slot[Listener]): Op
}

/** The ops of IP datagram sockets, for a backend that has them (see `IpDatagrams`). A datagram socket is bound,
  * receiving from and sending to anyone (`bindDatagram`), or connected, to one peer (`connectDatagram`): two kinds, so
  * that sending a connected socket's packet elsewhere, or a bound socket's to nobody, does not compile.
  */
trait IpDatagramOps[Op, H <: AnyRef] extends SocketOptionOps[Op, H] {

  /** An IP datagram socket bound to a local address, from `bindDatagram`, which sends to and receives from anyone. */
  type BoundDatagram <: H

  /** An IP datagram socket connected to one peer, from `connectDatagram`, which sends to and receives from it alone. */
  type ConnectedDatagram <: H

  /** Opens a datagram socket bound to `local`, port 0 for any. Completes once it is bound, after writing it into
    * `into`, which must be empty; fails if `local` is in use or cannot be bound.
    */
  final def bindDatagram(local: IpAddress, into: Slot[BoundDatagram]): Op =
    bindDatagram(local, SocketOptions.default, into)

  /** As the other `bindDatagram`, the socket opening with `options`, of those that apply to a datagram socket. */
  def bindDatagram(local: IpAddress, options: SocketOptions, into: Slot[BoundDatagram]): Op

  /** Opens a datagram socket connected to `remote`, bound to `local` if given, of the same family, else to an ephemeral
    * port. Completes once it is connected, after writing it into `into`, which must be empty; fails if `local` is in
    * use or cannot be bound. Connecting sends nothing: it only fixes the peer, which alone it then hears.
    */
  final def connectDatagram(local: IpAddress | Null, remote: IpAddress, into: Slot[ConnectedDatagram]): Op =
    connectDatagram(local, remote, SocketOptions.default, into)

  /** As the other `connectDatagram`, the socket opening with `options`, of those that apply to a datagram socket. */
  def connectDatagram(
      local: IpAddress | Null,
      remote: IpAddress,
      options: SocketOptions,
      into: Slot[ConnectedDatagram]
  ): Op

  /** Receives one packet on a bound datagram socket into `buf`, which is cleared first and left flipped for reading,
    * and writes its sender into `from`, which must be empty. Completes once a packet has arrived; its size is
    * `buf.remaining`, the bytes beyond `buf`'s capacity being dropped, silently, as every system's `recvfrom` and the
    * JDK drop them: a protocol over datagrams fixes the largest packet it sends, and a receiver gives a buffer that
    * holds it. A buffer of no capacity fails the op with an `IllegalArgumentException`, receiving nothing, since it
    * would drop a whole packet.
    */
  def receive(handle: BoundDatagram, buf: ByteBuffer, from: Slot[IpAddress]): Op & Repeatable

  /** Receives one packet on a connected datagram socket, from its peer, as the other `receive` does. */
  def receive(handle: ConnectedDatagram, buf: ByteBuffer): Op & Repeatable

  /** Sends `buf` as one packet on a bound datagram socket, to `to`. Completes once the whole packet has been sent,
    * consuming `buf`; an empty `buf` sends an empty packet. Sent means handed to the system, not delivered: as anywhere
    * in UDP, a packet may still be lost, and on NIO an empty one counts as sent even if the socket had no room for it
    * then, since the JDK reports both as 0 bytes (as Netty does, the same as a loss). A packet lost on the way, to a
    * port nothing listens on say, fails neither this send nor a later one, to that peer or another, as no system tells
    * a socket that is not connected of its packets' fate.
    */
  def send(handle: BoundDatagram, buf: ByteBuffer, to: IpAddress): Op & Repeatable

  /** Sends `buf` as one packet on a connected datagram socket, to its peer, as the other `send` does. Fails with an
    * `IllegalArgumentException`, sending nothing, if `buf` is empty: no backend sends an empty packet on a connected
    * socket, since the JDK cannot.
    */
  def send(handle: ConnectedDatagram, buf: ByteBuffer): Op & Repeatable

  /** Sends `bufs`, in order, as one packet, to `to`, as the other `send` sends one buffer, consuming them all. */
  def send(handle: BoundDatagram, bufs: Array[ByteBuffer], to: IpAddress): Op & Repeatable

  /** Sends `bufs`, in order, as one packet, to the peer, as the other `send` sends one buffer: never an empty one. */
  def send(handle: ConnectedDatagram, bufs: Array[ByteBuffer]): Op & Repeatable

  /** The address a datagram socket is bound to, with the port it got if it asked for port 0, or, for one connected with
    * no local address, the ephemeral port connecting gave it. Not an op, as `StreamSocketOps.localAddress`.
    */
  @annotation.targetName("datagramLocalAddress")
  def localAddress(handle: BoundDatagram | ConnectedDatagram): IpAddress

  /** The peer a datagram socket is connected to, the only sender it hears. Not an op. */
  @annotation.targetName("datagramPeerAddress")
  def peerAddress(handle: ConnectedDatagram): IpAddress
}

/** Regular files as ops, for a backend that has them (see `RegularFiles`). A file is opened by an op, since opening
  * does disk I/O, for the path, as reads do, and some environments can only open asynchronously; its reads and writes
  * are positional, as `pread` and `pwrite` are, so that one handle serves concurrent reads at different offsets and no
  * op depends on a shared file position, which io_uring has none of either. `Ops.read` and `Ops.write` on a file handle
  * read and write at the position the handle carries, as a stream, for code that treats a file as one.
  *
  * Cancellation follows `BlockingOps.blocking`: an op not yet started is skipped, one running finishes on its thread
  * with its result discarded, and `perform` returns once the thread has stopped touching the buffer. A close that comes
  * while an op on the file is in its system call waits for it, and the op fails as one on a closed handle does
  * (`Ops.close`), on every backend, its buffer and offset left as they were even if the call finished first, as the JDK
  * fails a call on a channel closed under it: a write's bytes may have reached the file. The close completes after the
  * call, so the descriptor is never reused under the op.
  */
trait FileOps[Op, H <: AnyRef] extends Ops[Op, H] {

  /** A regular file, from `open`: read and written as a stream and at offsets, and synced. */
  type File <: Readable & Writable

  /** Opens the regular file at `path` for reading, or for writing with `write`, and completes after writing its handle
    * into `into`, which must be empty: a file that exists, which opening for writing neither creates nor truncates.
    * Anything but a regular file is refused, failing the op with an `IOException`, as a path that cannot be opened
    * does; a path with a NUL in it is refused as the op is built, with an `IllegalArgumentException`. A cancel that
    * arrives before the write leaves `into` empty and the file closed; one after it is too late, and the op completes
    * (see `BlockingOps.blocking`).
    */
  def open(path: String, write: Boolean, into: Slot[File]): Op

  /** Fills `buf` from the file at `offset`, as `Ops.read` fills it from a stream: completes once at least one byte has
    * been read, or at the end of the file, when nothing was added, so a caller performs it again with the offset
    * advanced; and fails, as it does, if `buf` has no room. A negative `offset` is refused as the op is built, with an
    * `IllegalArgumentException`, as it is for `write`.
    */
  def read(handle: File, buf: ByteBuffer, offset: Long): Op & Repeatable

  /** Drains `buf` into the file at `offset`, as `Ops.write` drains it into a stream: completes once at least one byte
    * has been written, or, if nothing remains, without writing; perform it again while `buf` has bytes remaining, with
    * the offset advanced.
    */
  def write(handle: File, buf: ByteBuffer, offset: Long): Op & Repeatable

  /** Completes once the file's written bytes, and its metadata, have been handed to the device, as `fsync` does: a
    * drive may hold them in its own cache a while longer (on macOS, `F_FULLFSYNC` would flush that), so this is the
    * system's promise, not one against losing power.
    */
  def sync(handle: File): Op & Repeatable
}

/** Name resolution, for a backend that has it (see `Resolving`). */
trait ResolveOps[Op, H <: AnyRef] extends Ops[Op, H] {

  /** Resolves a host name, without blocking the caller's worker. Completes after writing its addresses, in numeric form
    * (an IPv6 one as `inet_ntop` writes it), into `into`, which must be empty; fails with an `IOException` if the name
    * cannot be resolved, or has no IPv4 or IPv6 address. An empty name, or one with a NUL in it, is refused as the op
    * is built, with an `IllegalArgumentException`. A cancel that arrives before the write leaves `into` empty, and is
    * delivered at once, though the lookup, which cannot be interrupted, runs on to its end on a thread of its own, its
    * result dropped; one after the write is too late, and the op completes. A time limit is a cancel, so it holds to
    * its time.
    */
  def resolve(host: String, into: Slot[List[ResolvedAddress]]): Op
}

/** The ops of pipes, for a backend that has them (see `Pipes`): `read` on its read end, `write` on its write end.
  */
trait PipeOps[Op, H <: AnyRef] extends Ops[Op, H] {

  /** Opens a pipe. Completes after writing its read end into `read` and its write end into `write`, which must both be
    * empty; one that fails or is cancelled leaves both empty and neither end open.
    */
  def pipe(read: Slot[Readable], write: Slot[Writable]): Op
}

/** The ops of FIFOs, for a backend that has them (see `Fifos`), which are then read and written as pipes are. */
trait FifoOps[Op, H <: AnyRef] extends Ops[Op, H] {

  /** Opens the FIFO at `path` for reading, without waiting for a writer, and completes after writing it into `into`,
    * which must be empty. Read before a writer has opened it, it is at its end.
    */
  def openFifoForReading(path: String, into: Slot[Readable]): Op

  /** Opens the FIFO at `path` for writing, without waiting for a reader, and completes after writing it into `into`,
    * which must be empty; fails while the FIFO has no reader.
    */
  def openFifoForWriting(path: String, into: Slot[Writable]): Op
}

/** The ops of Unix domain stream sockets, for a backend that has them (see `UnixStreams`): opened here, and used as IP
  * stream sockets are, by the stream ops (`StreamSocketOps`, and `read` and `write`).
  */
trait UnixStreamOps[Op, H <: AnyRef] extends StreamSocketOps[Op, H] with SocketOptionOps[Op, H] {

  /** Opens a stream socket and connects it to `address`, as `IpStreamOps.connect` does to an IP address. */
  final def connect(address: UnixAddress, into: Slot[Connection]): Op = connect(address, SocketOptions.default, into)

  /** As the other `connect`, the socket opening with `options`, of those that apply to a Unix connection: the buffer
    * sizes (see `SocketOptions`).
    */
  def connect(address: UnixAddress, options: SocketOptions, into: Slot[Connection]): Op

  /** Opens a stream socket listening on `address`, as `IpStreamOps.listen` does on an IP address, replacing a socket
    * file already there, as a restarted server's would be, and one a running server uses too; any other kind of file
    * there fails the op with an `IOException`, and is left. Checked, then replaced: a file put there between the two, a
    * few microseconds, is replaced as well.
    */
  final def listen(address: UnixAddress, into: Slot[Listener]): Op = listen(address, SocketOptions.default, into)

  /** As the other `listen`, the listener opening with `options`, of those that apply to a Unix listener, and each
    * connection it accepts with its send buffer (see `SocketOptions`).
    */
  def listen(address: UnixAddress, options: SocketOptions, into: Slot[Listener]): Op
}

/** Blocking code as an op, for a backend that has it (see `Blocking`). */
trait BlockingOps[Op, H <: AnyRef] extends Ops[Op, H] {

  /** Runs `body` where it may block, off the threads that run the program's futures, completing when it returns.
    * Cancelling it skips `body` if it has not started and interrupts it if it is running; interruption is cooperative,
    * and `perform` returns only once `body` has.
    */
  def blocking(body: () => Unit): Op

  /** Like the other `blocking`, writing `body`'s value into `into`, which must be empty, before completing. A cancel
    * that arrives before the write discards the value, leaving `into` empty; nothing is written after a cancel. One
    * that arrives after the write is too late: the op completes, with `into` holding the value, as an op that finished
    * before its cancel does, and the same for a time limit.
    */
  def blocking[A <: AnyRef](body: () => A, into: Slot[A]): Op
}

/** The ops of Unix domain datagram sockets, for a backend that has them (see `UnixDatagrams`), as `IpDatagramOps` are
  * for IP ones: bound, or connected to one peer.
  */
trait UnixDatagramOps[Op, H <: AnyRef] extends SocketOptionOps[Op, H] {

  /** A Unix domain datagram socket bound to a path, from `bindDatagram`, as `IpDatagramOps.BoundDatagram` is for IP. */
  type UnixBoundDatagram <: H

  /** A Unix domain datagram socket connected to one peer, from `connectDatagram`, as `ConnectedDatagram` is for IP. */
  type UnixConnectedDatagram <: H

  /** Opens a datagram socket bound to `local`, as `IpDatagramOps.bindDatagram` does. */
  final def bindDatagram(local: UnixAddress, into: Slot[UnixBoundDatagram]): Op =
    bindDatagram(local, SocketOptions.default, into)

  /** As the other `bindDatagram`, the socket opening with `options`, of those that apply to it: the buffer sizes. */
  def bindDatagram(local: UnixAddress, options: SocketOptions, into: Slot[UnixBoundDatagram]): Op

  /** Opens a datagram socket connected to `remote`, bound to `local` if given, as `IpDatagramOps.connectDatagram` does.
    * A client that wants replies on a socket of its own, rather than this one, binds a `local` path.
    */
  final def connectDatagram(local: UnixAddress | Null, remote: UnixAddress, into: Slot[UnixConnectedDatagram]): Op =
    connectDatagram(local, remote, SocketOptions.default, into)

  /** As the other `connectDatagram`, the socket opening with `options`, of those that apply to it. */
  def connectDatagram(
      local: UnixAddress | Null,
      remote: UnixAddress,
      options: SocketOptions,
      into: Slot[UnixConnectedDatagram]
  ): Op

  /** Receives one packet on a bound Unix datagram socket, as `IpDatagramOps.receive` does on an IP one; an unnamed
    * sender has the empty path.
    */
  @annotation.targetName("receiveUnix")
  def receive(handle: UnixBoundDatagram, buf: ByteBuffer, from: Slot[UnixAddress]): Op & Repeatable

  /** Receives one packet on a connected Unix datagram socket, from its peer. */
  @annotation.targetName("receiveUnixConnected")
  def receive(handle: UnixConnectedDatagram, buf: ByteBuffer): Op & Repeatable

  /** Sends one packet on a bound Unix datagram socket, to `to`, as `IpDatagramOps.send` does on an IP one. */
  def send(handle: UnixBoundDatagram, buf: ByteBuffer, to: UnixAddress): Op & Repeatable

  /** Sends one packet on a connected Unix datagram socket, to its peer, as `IpDatagramOps.send` does on an IP one. */
  @annotation.targetName("sendUnixConnected")
  def send(handle: UnixConnectedDatagram, buf: ByteBuffer): Op & Repeatable

  /** Sends `bufs`, in order, as one packet, to `to`, as the other `send` sends one buffer. */
  def send(handle: UnixBoundDatagram, bufs: Array[ByteBuffer], to: UnixAddress): Op & Repeatable

  /** Sends `bufs`, in order, as one packet, to the peer, as the other `send` sends one buffer: never an empty one. */
  @annotation.targetName("sendUnixConnectedMany")
  def send(handle: UnixConnectedDatagram, bufs: Array[ByteBuffer]): Op & Repeatable

  /** The path a Unix datagram socket is bound to, the empty path for one with none. Not an op. */
  @annotation.targetName("unixDatagramLocalAddress")
  def localAddress(handle: UnixBoundDatagram | UnixConnectedDatagram): UnixAddress

  /** The path a Unix datagram socket is connected to. Not an op. */
  @annotation.targetName("unixDatagramPeerAddress")
  def peerAddress(handle: UnixConnectedDatagram): UnixAddress
}
