package gears.async.io

import gears.async.io.internal.Reactor

import _root_.gears.async.Async

/** A way of doing I/O: the ops it can perform, the handles they name, and the pools its reads fill. How an op is
  * performed is its kind's, which is internal: a reactor backend submits its ops on reactors it opens, and a program
  * over it runs on a pool of workers driving them; a caller backend does each op itself on the calling thread, as NIO's
  * `NioOnCaller` does with non-blocking channels from a thread that may block. The runner knows which (`Io.run`). Ops
  * of another backend are a different type. Building an op or using a handle performs nothing, so either may be done
  * from any thread, and a backend performs nothing itself: a program performs its ops with an `Io` over the backend
  * (`Io.backend`), which says where they run.
  *
  * What every backend has is here: streams, read and written, into the caller's buffers or into buffers of the
  * backend's (`pool`), timers, and handles closed as ops (`Ops.close`), since a backend whose I/O facility closes
  * asynchronously, such as Network.framework or io_uring, reports a close done through a completion rather than by
  * blocking the caller. Where the streams come from is a capability the backend mixes in, with the ops that open its
  * handles and use them: IP stream and datagram sockets (`IpStreams`, `IpDatagrams`, both `IpSockets`), regular files
  * (`RegularFiles`), pipes (`Pipes`), name resolution (`Resolving`), blocking ops (`Blocking`), `UnixStreams`,
  * `UnixDatagrams` and `Fifos`. Code that needs a capability asks for a backend that has it, by the capability, each a
  * `Backend` (`IpSockets`, or `IpStreams & RegularFiles` for more), or for an `Io` over one (`Io[IpSockets]`), so a
  * backend without it, such as one in a browser, which has no sockets or files of these kinds, is refused when
  * compiling, not when running.
  */
trait Backend {

  /** What this performs. */
  type Op

  /** Names an open file, socket, or other I/O object of this backend: an object of the backend's own class, which only
    * the backend can make, so that a program cannot forge a handle, nor pass one backend's handle to another, whose
    * `Handle` is a different type. A program gets one from an op that opened something, and closes it with `Ops.close`;
    * the backend never closes it.
    */
  type Handle <: AnyRef

  /** The kinds of handle (see `Ops`): each a subtype of `Handle`, which its opener writes, so that an op on the wrong
    * kind of handle does not compile. A backend makes each its own type, so that, holding a backend of a known type, a
    * program cannot pass one kind where another is asked for either.
    */
  type Readable <: Handle
  type Writable <: Handle
  type Connection <: Readable & Writable
  type Listener <: Handle
  type BoundDatagram <: Handle
  type ConnectedDatagram <: Handle
  type UnixBoundDatagram <: Handle
  type UnixConnectedDatagram <: Handle
  type File <: Readable & Writable

  /** `O`, a kind of ops, building ops on this backend's kinds of handle. */
  type Kinded[O <: Ops[Op, Handle]] = O {
    type Readable = Backend.this.Readable
    type Writable = Backend.this.Writable
    type Connection = Backend.this.Connection
    type Listener = Backend.this.Listener
    type BoundDatagram = Backend.this.BoundDatagram
    type ConnectedDatagram = Backend.this.ConnectedDatagram
    type UnixBoundDatagram = Backend.this.UnixBoundDatagram
    type UnixConnectedDatagram = Backend.this.UnixConnectedDatagram
    type File = Backend.this.File
  }

  /** Builds this backend's ops. */
  def ops: Kinded[Ops[Op, Handle]]

  /** Opens a pool of `buffers` buffers of up to `bufferSize` bytes each, for the reads into buffers of the backend's
    * (`Ops.read` with a pool). Close it once nothing of it is held.
    */
  def pool(bufferSize: Int, buffers: Int): BufferPool
}

object Backend {

  /** No time limit, for the time limits in milliseconds within: any negative value means the same. */
  private[io] inline val NoTimeout = -1
}

/** Stream sockets of some family, IP or Unix, once opened (see `StreamSocketOps`): what code that accepts, reads,
  * writes and shuts down connections needs, whichever family opened them. A backend has it through `IpStreams` or
  * `UnixStreams`, neither of which implies the other.
  *
  * The socket capabilities go by address family and transport, one each: `IpStreams`, `IpDatagrams`, `UnixStreams` and
  * `UnixDatagrams`, so that a host with some of them, such as one with TCP and no UDP, offers just those, and code asks
  * for just what it uses; `IpSockets` is both IP ones.
  */
trait StreamSockets extends Backend {
  def ops: Kinded[StreamSocketOps[Op, Handle]]
}

/** IP stream sockets, TCP (see `IpStreamOps`): an operating system's, or a host's, such as Node's; a browser has none.
  */
trait IpStreams extends StreamSockets {
  def ops: Kinded[IpStreamOps[Op, Handle]]
}

/** IP datagram sockets, UDP (see `IpDatagramOps`), apart from IP stream sockets: some hosts have one and not the other.
  */
trait IpDatagrams extends Backend {
  def ops: Kinded[IpDatagramOps[Op, Handle]]
}

/** IP sockets of both transports, for code that uses both. */
type IpSockets = IpStreams & IpDatagrams

/** Regular files, opened, read, written and synced as ops (see `FileOps`): on a thread pool where the operating
  * system's file I/O blocks, as kqueue's, NIO's and Network.framework's do, asynchronously at the device where it does
  * not, as io_uring's would.
  */
trait RegularFiles extends Backend {
  def ops: Kinded[FileOps[Op, Handle]]
}

/** Host names resolved (see `ResolveOps`). */
trait Resolving extends Backend {
  def ops: Kinded[ResolveOps[Op, Handle]]
}

/** Pipes (see `PipeOps`). */
trait Pipes extends Backend {
  def ops: Kinded[PipeOps[Op, Handle]]
}

/** Blocking code run as an op (see `BlockingOps`): where it may block, with the op's time limit and cancellation
  * interrupting it, on every backend: off the threads that run the program's futures where those must not block, and on
  * the calling thread where it may, the op then adding only the limit and the interrupt.
  */
trait Blocking extends Backend {
  def ops: Kinded[BlockingOps[Op, Handle]]
}

/** Unix domain stream sockets (see `UnixStreamOps`), on which the stream socket ops run (`StreamSocketOps`). Apart from
  * IP sockets: code that uses both asks for both, `IpStreams & UnixStreams`.
  */
trait UnixStreams extends StreamSockets {
  def ops: Kinded[UnixStreamOps[Op, Handle]]
}

/** Unix domain datagram sockets (see `UnixDatagramOps`), apart from IP sockets, as `UnixStreams` are. */
trait UnixDatagrams extends Backend {
  def ops: Kinded[UnixDatagramOps[Op, Handle]]
}

/** FIFOs waited on as pipes are (see `FifoOps`). */
trait Fifos extends Backend {
  def ops: Kinded[FifoOps[Op, Handle]]
}
