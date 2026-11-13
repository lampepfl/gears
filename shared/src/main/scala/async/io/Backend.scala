package gears.async.io

import _root_.gears.async.Async

/** A way of doing I/O: the ops it can perform, the handles they name, and the pools its reads fill. Ops of another
  * backend are a different type. Building an op or using a handle performs nothing, so either may be done from any
  * thread, and a backend performs nothing itself: a program performs its ops with an `Io` over the backend
  * (`Io.backend`), which `Io.run` gives it. How an op is performed, and where a program runs, is the backend's.
  *
  * What every backend has is here: streams, read and written, into the caller's buffers or into buffers of the
  * backend's (`pool`), timers, and handles closed as ops (`Interface.close`), since a backend whose I/O facility closes
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

  /** This backend's interface: what builds its ops, with the types of its ops and of its handles, each kind of handle a
    * type of its own (see `Interface`). A capability narrows it to the interface that has its ops and its kinds, so a
    * program names a kind through the backend it asked for, `backend.ops.Connection`, and a backend without the
    * capability has no such kind.
    */
  val ops: Interface

  /** Opens a pool of `buffers` buffers of up to `bufferSize` bytes each, for the reads into buffers of the backend's
    * (`Interface.read` with a pool). Close it once nothing of it is held.
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
  val ops: StreamSocketOps
}

/** IP stream sockets, TCP (see `IpStreamOps`): an operating system's, or a host's, such as Node's; a browser has none.
  */
trait IpStreams extends StreamSockets {
  val ops: IpStreamOps
}

/** IP datagram sockets, UDP (see `IpDatagramOps`), apart from IP stream sockets: some hosts have one and not the other.
  */
trait IpDatagrams extends Backend {
  val ops: IpDatagramOps
}

/** IP sockets of both transports, for code that uses both. */
type IpSockets = IpStreams & IpDatagrams

/** Regular files, opened, read, written and synced as ops (see `FileOps`): on a thread where the operating system's
  * file I/O blocks, asynchronously at the device where it does not, as io_uring's would.
  */
trait RegularFiles extends Backend {
  val ops: FileOps
}

/** Host names resolved (see `ResolveOps`). */
trait Resolving extends Backend {
  val ops: ResolveOps
}

/** Pipes (see `PipeOps`). */
trait Pipes extends Backend {
  val ops: PipeOps
}

/** Blocking code run as an op (see `BlockingOps`): where it may block, with the op's time limit and cancellation
  * interrupting it, on every backend: off the threads that run the program's futures where those must not block, and on
  * the calling thread where it may, the op then adding only the limit and the interrupt.
  */
trait Blocking extends Backend {
  val ops: BlockingOps
}

/** Unix domain stream sockets (see `UnixStreamOps`), on which the stream socket ops run (`StreamSocketOps`). Apart from
  * IP sockets: code that uses both asks for both, `IpStreams & UnixStreams`.
  */
trait UnixStreams extends StreamSockets {
  val ops: UnixStreamOps
}

/** Unix domain datagram sockets (see `UnixDatagramOps`), apart from IP sockets, as `UnixStreams` are. */
trait UnixDatagrams extends Backend {
  val ops: UnixDatagramOps
}

/** FIFOs waited on as pipes are (see `FifoOps`). */
trait Fifos extends Backend {
  val ops: FifoOps
}
