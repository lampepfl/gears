package gears.async.io
package eventpolling

import gears.async.Async

import scala.scalanative.meta.LinktimeInfo

/** The native backend, over the system's own event queue: kqueue on macOS and FreeBSD, epoll on Linux.
  *
  * A program runs on a pool of workers (`EventPollingScheduler`), each with its own inbox, heap of timers and event
  * poller (`EventPoller`), the one for the system the program is linked for (see `scheduler`). An op is performed on
  * the worker of the thread performing it, whose heap holds its time limit. What it then does is its kind's:
  *
  *   - a read, a write, an accept, a connect or a datagram's send or receive is tried at once, and if its handle is not
  *     ready waits for the poller of the worker that listens for that handle, which does the op's work and resumes the
  *     performer. A stream allows one read and one write at a time; a listener's accepts and a datagram socket's sends
  *     and receives take turns in a queue (see `HandleReadiness`);
  *   - blocking code, a file's calls and name lookups, which block, are done on a thread of `BlockingPool`'s, and the
  *     op is completed back on the performer's worker (see `WorkOffThread`);
  *   - opening a listener, a datagram socket, a pipe or a FIFO, and shutting a connection down, are done as they are
  *     performed.
  */
object EventPollingBackend extends Backend {
  type Interface = EventPollingOps.type

  def pool(bufferSize: Int, buffers: Int): BufferPool = new NativePool(bufferSize, buffers)

  /** Runs `program` on a pool of one worker for each processor, shutting it down afterwards. The program runs under the
    * pool's group: a fatal error in a worker cancels it, and is thrown from here once it has unwound.
    */
  private[io] def run[T](program: Io[Interface] ?=> Async.Spawn ?=> T): T =
    runOn(Runtime.getRuntime.availableProcessors, shareable.Starvation.printWarning)(program)

  /** As `run`, on a pool of `workers` workers whose reports of starved workers go to `onStarvation`. */
  private[io] def runOn[T](workers: Int, onStarvation: shareable.Starvation.Handler)(
      program: Io[Interface] ?=> Async.Spawn ?=> T
  ): T = {
    posix.FileLimit.raised: Unit // a shell's limit on open descriptors is far below what a server's sockets need
    shareable.PoolRun(scheduler(workers, onStarvation))(program)
  }

  /** The pool for the system the program is linked for: over epoll on Linux, and over kqueue anywhere else. Decided as
    * the program is linked, so that the other's system calls are not in it.
    */
  private[io] def scheduler(workers: Int, onStarvation: shareable.Starvation.Handler): EventPollingScheduler =
    if LinktimeInfo.isLinux then new epoll.EpollScheduler(workers, onStarvation)
    else new kqueue.KqueueScheduler(workers, onStarvation)
}
