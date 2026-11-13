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
  * blocking the caller. Where the streams come from is a capability, an `Interface` that the backend's `ops` mix in,
  * with the ops that open its handles and use them: IP stream and datagram sockets (`IpStreams`, `IpDatagrams`, both
  * `IpSockets`), regular files (`RegularFiles`), pipes (`Pipes`), name resolution (`Resolving`), blocking ops
  * (`Blocking`), `UnixStreams`, `UnixDatagrams` and `Fifos`. Code that needs a capability asks for an `Io` over ops
  * that have it, by the capability (`Io[IpSockets]`, or `Io[IpStreams & RegularFiles]` for more), or for a backend
  * whose ops do (`Backend { val ops: IpSockets }`), so a backend without it, such as one in a browser, which has no
  * sockets or files of these kinds, is refused when compiling, not when running.
  */
trait Backend {

  /** This backend's interface: what builds its ops, with the types of its ops and of its handles, each kind of handle a
    * type of its own (see `Interface`). A backend with a capability has ops of the interface that declares its ops and
    * its kinds, so a program names a kind through the backend it asked for, `backend.ops.Connection`, and a backend
    * without the capability has no such kind.
    */
  val ops: Interface

  /** Opens a pool of `buffers` buffers of up to `bufferSize` bytes each, for the reads into buffers of the backend's
    * (`Interface.read` with a pool). Close it once nothing of it is held.
    */
  def pool(bufferSize: Int, buffers: Int): BufferPool

  /** Runs `program` over this backend, giving it an `Io` over it, and returns what it returns, rethrowing its failure:
    * what `Io.run` does, each backend its own way, since where its ops are performed is its own.
    */
  private[io] def run[T](program: Io[ops.type] ?=> Async.Spawn ?=> T): T
}

object Backend {

  /** No time limit, for the time limits in milliseconds within: any negative value means the same. */
  private[io] inline val NoTimeout = -1
}
