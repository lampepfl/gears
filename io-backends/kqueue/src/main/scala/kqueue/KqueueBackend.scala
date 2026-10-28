package gears.async.io
package kqueue

import gears.async.io.Blocking
import gears.async.io.BufferPool
import gears.async.io.Fifos
import gears.async.io.IpDatagrams
import gears.async.io.IpStreams
import gears.async.io.Pipes
import gears.async.io.RegularFiles
import gears.async.io.Resolving
import gears.async.io.UnixDatagrams
import gears.async.io.UnixStreams
import gears.async.io.internal.Reactor
import gears.async.io.internal.ReactorBackend

/** Kqueue's reactors, with every capability a backend may have: IP, Unix stream and Unix datagram sockets, regular
  * files and name resolution on the blocker pool, pipes, blocking ops on blocker threads, and FIFOs; and, as kqueue's
  * own, outside the interface, readiness (`KqueueOps.ready`, `readNow`, `writeNow`) and commands of its own (`Command`)
  * on any descriptor. Kqueue watches POSIX file descriptors, so a handle holds the descriptor. The reactors all run the
  * same ops, which keep no state of one reactor between submissions, so an op may go to any of them in turn; what
  * kqueue keeps about a descriptor stays with the reactor that registered it.
  */
object KqueueBackend
    extends ReactorBackend
    with Blocking
    with IpStreams
    with IpDatagrams
    with UnixStreams
    with UnixDatagrams
    with Fifos
    with RegularFiles
    with Resolving
    with Pipes {
  type Op = KqueueOp
  type Readable = KqueueKinds.Readable
  type Writable = KqueueKinds.Writable
  type Connection = KqueueKinds.Connection
  type Listener = KqueueKinds.Listener
  type BoundDatagram = KqueueKinds.BoundDatagram
  type ConnectedDatagram = KqueueKinds.ConnectedDatagram
  type UnixBoundDatagram = KqueueKinds.UnixBoundDatagram
  type UnixConnectedDatagram = KqueueKinds.UnixConnectedDatagram
  type File = KqueueKinds.File

  /** An open descriptor, as this backend's ops name it: made only by the ops that open one (and `KqueueHandles`, for
    * kqueue's own code), so that a program cannot forge a handle from a number. Every use of the descriptor (a
    * command's system call and registration, a file op's on its blocker thread, `readNow` and `writeNow`) takes the
    * handle for its length (`acquire`), which fails once the handle is closed, so that a stale handle fails its op
    * rather than reaching whatever reuses the descriptor's number. Closing it is Go's: the handle is closed at once, so
    * that no use starts after, and the descriptor itself once no use is in progress, by the closer if none is, and
    * otherwise by the last use to end, on its own thread, so that the number is not reused under a use and nobody waits
    * for one (`close`).
    */
  final class Handle private[kqueue] (private[kqueue] val fd: Int) {
    // The sign bit: closed. The rest: how many uses of the descriptor are in progress.
    private val state = new java.util.concurrent.atomic.AtomicInteger(0)

    // The turns of the ops pending on it, one reading and one writing at a time, on any submitter: each takes its
    // direction's as it is submitted, or waits for it if it may, and lets it go as its completion is taken (`Turns`).
    private[io] val reader = new gears.async.io.internal.Turns
    private[io] val writer = new gears.async.io.internal.Turns

    // What the last use to end runs, once the handle is closed while uses were in progress.
    @volatile private var finisher: Runnable | Null = null

    /** Whether it has not been closed. */
    private[io] def isOpen: Boolean = state.get() >= 0

    /** Starts a use of the descriptor; false, starting none, if the handle is closed. */
    private[kqueue] def acquire(): Boolean = {
      var current = state.get()
      while current >= 0 && !state.compareAndSet(current, current + 1) do current = state.get()
      current >= 0
    }

    /** Ends a use `acquire` started: the last of a closed handle's runs what `close` left for it. */
    private[kqueue] def release(): Unit =
      if state.decrementAndGet() == Int.MinValue then {
        val finish = finisher.nn
        finisher = null
        finish.run()
      }

    /** Marks it closed, so that no use starts after, counting itself a use while it leaves `finish` for the last use:
      * `Handle.Idle` if none was in progress, so that the caller closes the descriptor now, and `finish` never runs;
      * `Handle.InUse` if some were, the last of which runs `finish`; `Handle.AlreadyClosed` if it was.
      */
    private[io] def close(finish: Runnable): Int = {
      var current = state.get()
      while current >= 0 && !state.compareAndSet(current, (current + 1) | Int.MinValue) do current = state.get()
      if current < 0 then Handle.AlreadyClosed
      else {
        finisher = finish // before the closer's own use ends, so that whichever use ends last finds it
        if state.decrementAndGet() == Int.MinValue then {
          finisher = null
          Handle.Idle
        } else Handle.InUse
      }
    }

    override def toString: String = s"Handle($fd)"
  }

  private[io] object Handle {
    inline val Idle = 0
    inline val InUse = 1
    inline val AlreadyClosed = 2
  }

  def ops: KqueueOps.type = KqueueOps
  def pool(bufferSize: Int, buffers: Int): BufferPool = new gears.async.io.internal.NativePool(bufferSize, buffers)

  /** Opens a reactor with `KqueueReactor.open`'s defaults. */
  def open(): Reactor.Of[Op, Handle] = KqueueReactor.open()
}
