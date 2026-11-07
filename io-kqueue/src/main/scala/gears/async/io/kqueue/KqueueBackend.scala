package gears.async.io
package kqueue

import gears.async.Async
import gears.async.AsyncOperations
import gears.async.AsyncSupport
import gears.async.Future
import gears.async.default.DefaultSupport

import java.nio.ByteBuffer
import java.nio.channels.NetworkChannel
import scala.annotation.targetName
import scala.concurrent.duration.FiniteDuration

/** The kqueue backend
  *
  * A program runs on a pool of workers (`KqueueScheduler`), each with its own inbox and heap of timers. An op is
  * performed on the worker of the thread performing it, whose heap holds its deadline, and which resumes the performer
  * once it completes. So far only timers are implemented, and a worker waits on a monitor rather than on kqueue.
  */
object KqueueBackend
    extends Backend
    // with IpStreams
    // with IpDatagrams
    // with UnixStreams
    // with RegularFiles
    with Pipes
    // with Resolving
    // with Blocking
    {
  type Op = KqueueOp
  type Handle = KqueueHandle
  type Readable = KqueueReadable
  type Writable = KqueueWritable
  // type Connection = ConnectionHandle
  // type Listener = ListenerHandle
  // type BoundDatagram = BoundDatagramHandle
  // type ConnectedDatagram = ConnectedDatagramHandle
  // type File = FileHandle

  val ops: KqueueOps.type = KqueueOps

  def pool(bufferSize: Int, buffers: Int): BufferPool = new NativePool(bufferSize, buffers)

  /** Runs `program` on a pool of one worker for each processor, shutting it down afterwards. */
  private[io] def run[T](program: Io[this.type] ?=> Async.Spawn ?=> T): T = {
    val pool = KqueueScheduler(Runtime.getRuntime.availableProcessors)
    given poolSupport: (AsyncSupport { type Scheduler = pool.Scheduler }) = pool
    given poolScheduler: pool.Scheduler = pool
    // `Async.blocking` runs its body on the calling thread.
    try Async.blocking(Future(program(using pool)).await)
    finally pool.shutdown()
  }
}
