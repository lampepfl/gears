package gears.async.io

import _root_.gears.async.Async

/** A way of doing I/O: the ops it can perform, the handles they name, and the pools its reads fill. Ops of another
  * backend are a different type. Building an op or using a handle performs nothing, so either may be done from any
  * thread, and a backend performs nothing itself: a program performs its ops with an `Io` over the backend
  * (`Io.backend`), which `Io.run` gives it. How an op is performed, and where a program runs, is the backend's.
  *
  * Every backend has an `Interface`, the type of its ops and timers; the rest are capabilities. The commonest are
  * streams, read and written, into the caller's buffers or into buffers of the backend's (`ByteStreams`, and `pool`),
  * and handles closed as ops (`Handles.close`), since a backend whose I/O facility closes asynchronously, such as
  * Network.framework or io_uring, reports a close done through a completion rather than by blocking the caller. Where
  * the streams come from is a capability, an `Interface` that the backend's `ops` mix in, with the ops that open its
  * handles and use them: IP stream and datagram sockets (`IpStreams`, `IpDatagrams`, both `IpSockets`), regular files
  * (`RegularFiles`), pipes (`Pipes`), name resolution (`Resolving`), blocking ops (`Blocking`), `UnixStreams`,
  * `UnixDatagrams` and `Fifos`. Code that needs a capability asks for an `Io` over ops that have it, by the capability
  * (`Io[IpSockets]`, or `Io[IpStreams & RegularFiles]` for more), or for a backend whose ops do (`Backend { type
  * Interface <: IpSockets }`), so a backend without it, such as one in a browser, which has no sockets or files of
  * these kinds, is refused when compiling, not when running.
  */
trait Backend {

  /** The interface of this backend's ops: what builds its ops, with the types of its ops and of its handles, each kind
    * of handle a type of its own (see `Interface`), and the capabilities the backend has. A program has the ops
    * themselves from its `Io` (`Io.ops`), which `Io.run` gives it, so it names a kind through that,
    * `io.ops.Connection`, and a backend without a capability has no such kind.
    */
  type Interface <: gears.async.io.Interface

  /** Opens a pool of `buffers` buffers of up to `bufferSize` bytes each, for the reads into buffers of the backend's
    * (`ByteStreams.read` with a pool). Close it once nothing of it is held.
    */
  def pool(bufferSize: Int, buffers: Int): BufferPool

  /** Runs `program` over this backend, giving it an `Io` over it, and returns what it returns, rethrowing its failure:
    * what `Io.run` does, each backend its own way, since where its ops are performed is its own.
    */
  private[io] def run[T](program: Io[Interface] ?=> Async.Spawn ?=> T): T
}

object Backend {

  /** No time limit, for the time limits in milliseconds within: any negative value means the same. */
  private[io] inline val NoTimeout = -1L
}
