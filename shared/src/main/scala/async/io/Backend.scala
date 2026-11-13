package gears.async.io

import _root_.gears.async.Async

/** A way of doing I/O: the ops it can perform, the handles they name, and the pools its reads fill. Ops of another
  * backend are a different type. Building an op or using a handle performs nothing, so either may be done from any
  * thread, and a backend performs nothing itself: a program performs its ops with an `Io` over the backend
  * (`Io.backend`), which `Io.run` gives it. How an op is performed, and where a program runs, is the backend's.
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

  /** The kinds of handle every backend's ops take (see `Ops`): streams, read and written. Each a subtype of `Handle`,
    * which its opener writes, so that an op on the wrong kind of handle does not compile. A backend makes each its own
    * type, so that, holding a backend of a known type, a program cannot pass one kind where another is asked for
    * either. The other kinds, a connection, a listener, a datagram socket or a file, are the capabilities': each is
    * declared by the capability whose ops open it (`StreamSockets.Connection`, `RegularFiles.File` and the rest), so a
    * backend without the capability has no such kind.
    */
  type Readable <: Handle
  type Writable <: Handle

  /** Builds this backend's ops. */
  def ops: Ops {
    type Op = Backend.this.Op
    type Handle = Backend.this.Handle
    type Readable = Backend.this.Readable
    type Writable = Backend.this.Writable
  }

  /** Opens a pool of `buffers` buffers of up to `bufferSize` bytes each, for the reads into buffers of the backend's
    * (`Ops.read` with a pool). Close it once nothing of it is held.
    */
  def pool(bufferSize: Int, buffers: Int): BufferPool

  /** Runs `program` over this backend, giving it an `Io` over it, and returns what it returns, rethrowing its failure:
    * what `Io.run` does, each backend its own way, since where its ops are performed is its own.
    */
  private[io] def run[T](program: Io[this.type] ?=> Async.Spawn ?=> T): T
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

  /** A connected stream socket, from a connect or an accept: read, written and shut down for writing. */
  type Connection <: Readable & Writable

  /** A listening stream socket, from a listen, which accepts connections. */
  type Listener <: Handle

  def ops: StreamSocketOps {
    type Op = StreamSockets.this.Op
    type Handle = StreamSockets.this.Handle
    type Readable = StreamSockets.this.Readable
    type Writable = StreamSockets.this.Writable
    type Connection = StreamSockets.this.Connection
    type Listener = StreamSockets.this.Listener
  }
}

/** IP stream sockets, TCP (see `IpStreamOps`): an operating system's, or a host's, such as Node's; a browser has none.
  */
trait IpStreams extends StreamSockets {
  def ops: IpStreamOps {
    type Op = IpStreams.this.Op
    type Handle = IpStreams.this.Handle
    type Readable = IpStreams.this.Readable
    type Writable = IpStreams.this.Writable
    type Connection = IpStreams.this.Connection
    type Listener = IpStreams.this.Listener
  }
}

/** IP datagram sockets, UDP (see `IpDatagramOps`), apart from IP stream sockets: some hosts have one and not the other.
  */
trait IpDatagrams extends Backend {

  /** An IP datagram socket bound to a local address, which sends to and receives from anyone. */
  type BoundDatagram <: Handle

  /** An IP datagram socket connected to one peer, which it alone sends to and receives from. */
  type ConnectedDatagram <: Handle

  def ops: IpDatagramOps {
    type Op = IpDatagrams.this.Op
    type Handle = IpDatagrams.this.Handle
    type Readable = IpDatagrams.this.Readable
    type Writable = IpDatagrams.this.Writable
    type BoundDatagram = IpDatagrams.this.BoundDatagram
    type ConnectedDatagram = IpDatagrams.this.ConnectedDatagram
  }
}

/** IP sockets of both transports, for code that uses both. */
type IpSockets = IpStreams & IpDatagrams

/** Regular files, opened, read, written and synced as ops (see `FileOps`): on a thread where the operating system's
  * file I/O blocks, asynchronously at the device where it does not, as io_uring's would.
  */
trait RegularFiles extends Backend {

  /** A regular file: read and written as a stream and at offsets, and synced. */
  type File <: Readable & Writable

  def ops: FileOps {
    type Op = RegularFiles.this.Op
    type Handle = RegularFiles.this.Handle
    type Readable = RegularFiles.this.Readable
    type Writable = RegularFiles.this.Writable
    type File = RegularFiles.this.File
  }
}

/** Host names resolved (see `ResolveOps`). */
trait Resolving extends Backend {
  def ops: ResolveOps {
    type Op = Resolving.this.Op
    type Handle = Resolving.this.Handle
    type Readable = Resolving.this.Readable
    type Writable = Resolving.this.Writable
  }
}

/** Pipes (see `PipeOps`). */
trait Pipes extends Backend {
  def ops: PipeOps {
    type Op = Pipes.this.Op
    type Handle = Pipes.this.Handle
    type Readable = Pipes.this.Readable
    type Writable = Pipes.this.Writable
  }
}

/** Blocking code run as an op (see `BlockingOps`): where it may block, with the op's time limit and cancellation
  * interrupting it, on every backend: off the threads that run the program's futures where those must not block, and on
  * the calling thread where it may, the op then adding only the limit and the interrupt.
  */
trait Blocking extends Backend {
  def ops: BlockingOps {
    type Op = Blocking.this.Op
    type Handle = Blocking.this.Handle
    type Readable = Blocking.this.Readable
    type Writable = Blocking.this.Writable
  }
}

/** Unix domain stream sockets (see `UnixStreamOps`), on which the stream socket ops run (`StreamSocketOps`). Apart from
  * IP sockets: code that uses both asks for both, `IpStreams & UnixStreams`.
  */
trait UnixStreams extends StreamSockets {
  def ops: UnixStreamOps {
    type Op = UnixStreams.this.Op
    type Handle = UnixStreams.this.Handle
    type Readable = UnixStreams.this.Readable
    type Writable = UnixStreams.this.Writable
    type Connection = UnixStreams.this.Connection
    type Listener = UnixStreams.this.Listener
  }
}

/** Unix domain datagram sockets (see `UnixDatagramOps`), apart from IP sockets, as `UnixStreams` are. */
trait UnixDatagrams extends Backend {

  /** A Unix domain datagram socket bound to a path, as `IpDatagrams.BoundDatagram` is to an IP address. */
  type UnixBoundDatagram <: Handle

  /** A Unix domain datagram socket connected to one peer, as `IpDatagrams.ConnectedDatagram` is. */
  type UnixConnectedDatagram <: Handle

  def ops: UnixDatagramOps {
    type Op = UnixDatagrams.this.Op
    type Handle = UnixDatagrams.this.Handle
    type Readable = UnixDatagrams.this.Readable
    type Writable = UnixDatagrams.this.Writable
    type UnixBoundDatagram = UnixDatagrams.this.UnixBoundDatagram
    type UnixConnectedDatagram = UnixDatagrams.this.UnixConnectedDatagram
  }
}

/** FIFOs waited on as pipes are (see `FifoOps`). */
trait Fifos extends Backend {
  def ops: FifoOps {
    type Op = Fifos.this.Op
    type Handle = Fifos.this.Handle
    type Readable = Fifos.this.Readable
    type Writable = Fifos.this.Writable
  }
}
