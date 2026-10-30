package gears.async.io

import _root_.gears.async.Async
import _root_.gears.async.AsyncOperations

/** What a program performs its ops with: where its backend's ops run. It has a backend (`backend`), whose ops, handles
  * and capabilities are what the program uses, and adds only where they run: `perform`. It is not a backend and has no
  * ops of its own; its type names the backend it is over, so a program's helper asks for an `Io` over a backend with
  * the capabilities it needs, `Io[IpSockets]`, and one over a backend with more, `Io[IpSockets & RegularFiles]`, is one
  * of those. A program gets its `Io` from `Io.run`, since a backend alone performs nothing; how it performs an op is
  * the backend's. A program's `Io` is lexical: a future it spawns captures it, and runs where the program runs.
  *
  * It is also the program's gears `AsyncOperations`, given to it by the runner beside its `Async`: `sleep` is a timer
  * op performed, so gears' `sleep`, `withTimeout` and `Timer` run on the backend's timers.
  */
trait Io[+B <: Backend] extends AsyncOperations {

  /** The backend whose ops this performs. */
  val backend: B

  /** What this performs: its backend's ops. */
  type Op = backend.Op

  /** The handles of the backend's I/O objects, and the kinds every backend has (see `Ops`): the kinds a capability adds
    * are its backend's, `io.backend.Connection` for one with stream sockets.
    */
  type Handle = backend.Handle
  type Readable = backend.Readable
  type Writable = backend.Writable

  /** Performs `op` and suspends the caller until it is done: it returns when the op finishes, throws its failure, or
    * throws `CancellationException` when the caller is cancelled first. Whichever way it ends, once it returns or
    * throws the op is no longer in use anywhere: its buffers and slots are the caller's again, and its handle is as
    * open as it was. An op marked `Repeatable` may be performed again once it has returned; any other op only once.
    */
  final def perform(op: Op)(using Async): Unit = performWithin(op, Backend.NoTimeout)

  /** Performs `op` as the other `perform` does, and throws `java.util.concurrent.TimeoutException` if it is not done
    * `timeout` after it began: never sooner, and to the millisecond. A cancelled or timed-out op has stopped, and an op
    * that waits for its handle before touching anything (a read, a write, an accept, a receive, a send) has then done
    * nothing: no bytes taken, none written, nothing accepted, so a read that timed out is a wait that gave up, not a
    * connection lost, and is how a read is given a timeout, with no timer to race it against. An op that opens leaves
    * its slot empty and nothing open. One already running elsewhere when it is stopped, a blocking op, a file's or a
    * name lookup, finishes, its outcome dropped unless it had already written it (see each op). A timer whose own time
    * is longer times out the same way; a zero `timeout` gives the op until the next turn of the caller's worker.
    */
  final def perform(op: Op, timeout: scala.concurrent.duration.FiniteDuration)(using Async): Unit = {
    require(timeout.length >= 0, s"A time limit is not negative: $timeout")
    val nanos = timeout.toNanos
    val millis = math.min((nanos + 999_999L) / 1_000_000L, Int.MaxValue.toLong).toInt // rounded up, never sooner
    performWithin(op, millis)
  }

  /** Performs `op` with a time limit of `timeoutMillis`, none if it is negative: what each backend's `Io` does. */
  private[io] def performWithin(op: Op, timeoutMillis: Int)(using Async): Unit

  /** Sleeps on a timer op of the backend's. */
  override def sleep(millis: Long)(using Async): Unit = {
    require(millis >= 0 && millis <= Int.MaxValue, s"A sleep of $millis ms is out of a timer's range")
    perform(backend.ops.timer(millis.toInt))
  }
}

object Io {

  /** Runs `program` over `backend` and returns what it returns, rethrowing its failure: the program is given an `Io`
    * over the backend, as a context parameter, with which it performs its ops and which is its `AsyncOperations`,
    * beside its `Async`, where the backend runs it (`Backend.run`). A program's `Io` is not a `Backend`, so starting a
    * run over one is refused when compiling.
    */
  def run[T](using backend: Backend)(program: Io[backend.type] ?=> (Async.Spawn, AsyncOperations) ?=> T): T =
    backend.run(program)
}
