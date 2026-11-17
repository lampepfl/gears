package gears.async.io.nio

import gears.async.Async
import gears.async.io.Deadline
import gears.async.io.Received
import gears.async.io.Slot
import gears.async.io.shareable.AbstractCloseOp
import gears.async.io.shareable.AbstractGatherWriteOp
import gears.async.io.shareable.AbstractMakeDeadlineOp
import gears.async.io.shareable.AbstractMakePipeOp
import gears.async.io.shareable.AbstractMakeTimerOp
import gears.async.io.shareable.AbstractPooledReadOp
import gears.async.io.shareable.AbstractReadOp
import gears.async.io.shareable.AbstractScatterReadOp
import gears.async.io.shareable.AbstractWriteOp
import gears.async.io.shareable.HandleOwner
import gears.async.io.shareable.LendingPool
import gears.async.io.shareable.TimerHeapOp

import java.nio.ByteBuffer
import java.nio.channels.Pipe
import scala.concurrent.duration.FiniteDuration

sealed trait NioOp extends TimerHeapOp:

  /** A worker takes any op of this backend's among its timers (see `NioPoller.Timer`). */
  type Source = NioPoller
  final def _holdTimer(worker: NioPoller): Unit = worker.addTimer(this)

/** An op that waits for its handle to be ready, on the worker that listens for it (see `ReadyWaiting`). */
sealed trait NioReadinessOp extends NioOp:
  def performWhenReady(self: NioPoller)(using Async): Unit

/** An op done as it is performed, with nothing to wait for (see `ImmediateOp`). */
sealed trait NioImmediateOp extends NioOp:
  def perform(owner: HandleOwner): Unit

sealed trait NioTimerOp extends NioOp

sealed trait NioDeadlineOp extends NioOp with Deadline

sealed trait NioCloseOp extends NioOp:
  def performClose(self: NioPoller)(using Async): Unit

/** A stream read's calls on the JDK's channels: mixed into the op. A channel that has nothing to read reads nothing. */
trait NioReadOps[H <: NioReadable]:
  final def _readHandle(handle: H, buf: ByteBuffer): Int = handle.reads.read(buf)
  final def _readvHandle(handle: H, bufs: Array[ByteBuffer]): Int = handle.reads.read(bufs).toInt

/** A stream write's calls, as `NioReadOps` has a read's. A channel with no room writes nothing, which is a write that
  * would block.
  */
trait NioWriteOps[H <: NioWritable]:
  final def _writeHandle(handle: H, buf: ByteBuffer): Int =
    val written = handle.writes.write(buf)
    if written == 0 then -1 else written
  final def _writevHandle(handle: H, bufs: Array[ByteBuffer]): Int =
    val written = handle.writes.write(bufs).toInt
    if written == 0 then -1 else written

private[nio] final class NioRead(handle: NioReadable, buf: ByteBuffer)
    extends AbstractReadOp(handle, buf),
      NioReadinessOp,
      NioReadOps[NioReadable]

private[nio] final class NioWrite(handle: NioWritable, buf: ByteBuffer)
    extends AbstractWriteOp(handle, buf),
      NioReadinessOp,
      NioWriteOps[NioWritable]

private[nio] final class NioScatterRead(handle: NioReadable, bufs: Array[ByteBuffer])
    extends AbstractScatterReadOp(handle, bufs),
      NioReadinessOp,
      NioReadOps[NioReadable]

private[nio] final class NioPooledRead(handle: NioReadable, pool: LendingPool, into: Slot[Received])
    extends AbstractPooledReadOp(handle, pool, into),
      NioReadinessOp,
      NioReadOps[NioReadable]

private[nio] final class NioGatherWrite(handle: NioWritable, bufs: Array[ByteBuffer])
    extends AbstractGatherWriteOp(handle, bufs),
      NioReadinessOp,
      NioWriteOps[NioWritable]

private[nio] final class NioCloseHandle(handle: NioHandle) extends AbstractCloseOp(handle) with NioCloseOp

private[nio] final class NioMakePipe(readSlot: Slot[NioReadable], writeSlot: Slot[NioWritable])
    extends AbstractMakePipeOp(readSlot, writeSlot),
      NioImmediateOp:
  def _pipe(owner: HandleOwner): (NioReadable, NioWritable) =
    val pipe = Pipe.open()
    try (new PipeRead(pipe.source(), owner), new PipeWrite(pipe.sink(), owner))
    catch
      case failure: Throwable =>
        try pipe.source().close()
        finally pipe.sink().close()
        throw failure

private[nio] final class NioMakeTimer(duration: FiniteDuration) extends AbstractMakeTimerOp(duration) with NioTimerOp

private[nio] final class NioMakeDeadline(initial: Long) extends AbstractMakeDeadlineOp(initial) with NioDeadlineOp
